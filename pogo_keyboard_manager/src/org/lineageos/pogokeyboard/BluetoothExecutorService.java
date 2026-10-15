/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.pogokeyboard;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import org.lineageos.pogokeyboard.aidl.IBluetoothExecutor;
import org.lineageos.pogokeyboard.aidl.IBluetoothExecutorCallback;

import java.util.Objects;

/**
 * Service running in the active user's execution domain.
 * Creates and holds BluetoothController and LatencyClient on a dedicated serial HandlerThread.
 * Executes bluetooth operations under its own user UID.
 */
public class BluetoothExecutorService extends Service {

    private static final String TAG = "BluetoothExecutor";

    private HandlerThread executorThread;
    private Handler executorHandler;
    private BluetoothController bluetoothController;
    private LatencyClient latencyClient;

    private int activeUserId = -1;
    private long activeGeneration = -1;
    private IBluetoothExecutorCallback currentCallback;

    private final IBluetoothExecutor.Stub binder = new IBluetoothExecutor.Stub() {
        @Override
        public void activate(KeyboardIdentity identity, int userId, long generation, IBluetoothExecutorCallback callback) {
            validateCaller();
            executorHandler.post(() -> handleActivate(identity, userId, generation, callback));
        }

        @Override
        public void execute(KeyboardAction action) {
            validateCaller();
            executorHandler.post(() -> handleExecute(action));
        }

        @Override
        public void stop(long generation, long stopRequestId) {
            validateCaller();
            executorHandler.post(() -> handleStop(generation, stopRequestId));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating BluetoothExecutorService for user " + getUserId());

        executorThread = new HandlerThread("BluetoothExecutor-" + getUserId());
        executorThread.start();
        executorHandler = new Handler(executorThread.getLooper());

        DefaultPlatformBluetoothAdapter adapter = new DefaultPlatformBluetoothAdapter(this, executorHandler);
        latencyClient = new LatencyClient(adapter);
        bluetoothController = new BluetoothController(adapter, this::onControllerEvent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind for user " + getUserId());
        return binder;
    }

    private void validateCaller() {
        int callingUid = Binder.getCallingUid();
        int myUid = Process.myUid();
        // Coordinator runs as user 0 with package UID appId.
        // User 0 UID is appId, secondary user UID is (userId * 100000 + appId).
        int callingAppId = callingUid % 100000;
        int myAppId = myUid % 100000;
        if (callingAppId != myAppId) {
            throw new SecurityException("Calling UID " + callingUid + " does not match package appId " + myAppId);
        }
    }

    private void handleActivate(KeyboardIdentity identity, int userId, long generation, IBluetoothExecutorCallback callback) {
        Log.i(TAG, "handleActivate: target=" + (identity != null ? identity.getAnonymizedAddress() : "null")
                + ", userId=" + userId + ", gen=" + generation);
        this.activeUserId = userId;
        this.activeGeneration = generation;
        this.currentCallback = callback;

        if (bluetoothController != null) {
            bluetoothController.setActiveSession(identity, userId, generation);
            bluetoothController.ensureProfileProxy();
        }

        // Full state synchronization on activate (prevents dropped events if BT was on at service start)
        if (callback != null) {
            try {
                boolean btOn = (bluetoothController != null && bluetoothController.isBluetoothEnabled());
                Log.i(TAG, "handleActivate: syncing initial BT_STATE_CHANGED=" + btOn);
                callback.onEvent(KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                        .targetIdentity(identity)
                        .userId(userId)
                        .generation(generation)
                        .flag(btOn)
                        .timestampMs(System.currentTimeMillis())
                        .build());

                boolean profileReady = (bluetoothController != null && bluetoothController.isProfileProxyReady());
                Log.i(TAG, "handleActivate: syncing initial PROFILE_AVAILABILITY_CHANGED=" + profileReady);
                if (profileReady) {
                    callback.onEvent(KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                            .targetIdentity(identity)
                            .userId(userId)
                            .generation(generation)
                            .flag(true)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to sync initial state to coordinator callback", e);
            }
        }
    }

    private void handleExecute(KeyboardAction action) {
        if (action == null) return;
        // Verify session generation & user
        if (action.getUserId() != 0 && action.getUserId() != activeUserId) {
            Log.w(TAG, "Dropping action for inactive user: " + action.getUserId() + " vs " + activeUserId);
            return;
        }
        if (action.getGeneration() != 0 && action.getGeneration() != activeGeneration) {
            Log.w(TAG, "Dropping action for outdated generation: " + action.getGeneration() + " vs " + activeGeneration);
            return;
        }

        if (action.getType() == KeyboardAction.Type.START_GATT_LATENCY) {
            if (action.getTargetIdentity() != null) {
                latencyClient.start(action.getTargetIdentity(), activeUserId, activeGeneration);
            }
        } else if (action.getType() == KeyboardAction.Type.STOP_GATT_LATENCY) {
            latencyClient.stop();
        } else {
            bluetoothController.execute(action);
        }
    }

    private void handleStop(long generation, long stopRequestId) {
        Log.i(TAG, "handleStop: generation=" + generation + ", stopRequestId=" + stopRequestId);
        // Perform local cleanup
        if (latencyClient != null) {
            latencyClient.stop();
        }
        if (bluetoothController != null) {
            bluetoothController.close();
            // Reinitialize controller for possible future activation
            DefaultPlatformBluetoothAdapter adapter = new DefaultPlatformBluetoothAdapter(this, executorHandler);
            bluetoothController = new BluetoothController(adapter, this::onControllerEvent);
        }

        IBluetoothExecutorCallback cb = currentCallback;
        int stoppedUser = activeUserId;
        long stoppedGen = activeGeneration;

        // Reset session state
        activeUserId = -1;
        activeGeneration = -1;
        currentCallback = null;

        if (cb != null) {
            try {
                cb.onStopped(stoppedUser, stoppedGen, stopRequestId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to call onStopped callback", e);
            }
        }
    }

    private void onControllerEvent(KeyboardEvent event) {
        IBluetoothExecutorCallback cb = currentCallback;
        if (cb != null) {
            try {
                cb.onEvent(event);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to dispatch onEvent to coordinator", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Destroying BluetoothExecutorService");
        if (latencyClient != null) {
            latencyClient.stop();
        }
        if (bluetoothController != null) {
            bluetoothController.close();
        }
        if (executorThread != null) {
            executorThread.quitSafely();
        }
    }
}
