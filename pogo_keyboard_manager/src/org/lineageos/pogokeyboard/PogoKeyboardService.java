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

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import org.lineageos.pogokeyboard.aidl.IKeyboardCoordinator;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Optional;

/**
 * Main coordinator service running in the System User (User 0).
 * Holds KeyboardCoordinator, IdentityStore, PogoEventSource, and ActiveUserBinding.
 * DUMP protected by android.permission.DUMP.
 */
public class PogoKeyboardService extends Service {

    private static final String TAG = "PogoKeyboardService";

    private HandlerThread coordinatorThread;
    private Handler coordinatorHandler;

    private KeyboardCoordinator coordinator;
    private KeyboardState state;
    private IdentityStore identityStore;
    private PogoEventSource pogoEventSource;
    private ActiveUserBinding userBinding;

    private final IKeyboardCoordinator.Stub binder = new IKeyboardCoordinator.Stub() {
        @Override
        public void requestRetry(KeyboardIdentity identity) {
            validateCaller();
            coordinatorHandler.post(() -> handleRetryRequest(identity));
        }
    };

    private final BroadcastReceiver userReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_USER_FOREGROUND.equals(action) || Intent.ACTION_USER_SWITCHED.equals(action)) {
                int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, ActivityManager.getCurrentUser());
                coordinatorHandler.post(() -> handleUserSwitched(newUserId));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        int myUser = getUserId();
        Log.i(TAG, "Creating PogoKeyboardService in user " + myUser);

        if (myUser != UserHandle.USER_SYSTEM) {
            Log.w(TAG, "Coordinator service must only run in user 0. Stopping.");
            stopSelf();
            return;
        }

        coordinatorThread = new HandlerThread("PogoKeyboardCoordinator");
        coordinatorThread.start();
        coordinatorHandler = new Handler(coordinatorThread.getLooper());

        coordinator = new KeyboardCoordinator();
        state = KeyboardState.initial(0);
        identityStore = new IdentityStore(this);

        pogoEventSource = new PogoEventSource(this, coordinatorHandler);
        userBinding = new ActiveUserBinding(this, coordinatorHandler, this::onKeyboardEvent);

        // Load persisted TargetRecord
        try {
            Optional<TargetRecord> recordOpt = identityStore.load();
            if (recordOpt.isPresent()) {
                TargetRecord record = recordOpt.get();
                Log.i(TAG, "Loaded target record: " + record.getIdentity().getAnonymizedAddress()
                        + ", suppressed=" + record.isAutoPairSuppressed()
                        + ", bonded=" + record.isLastKnownBonded());
                state = state.toBuilder().targetRecord(record).build();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TargetRecord from store. Entering suppressed state.", e);
            // Conservative suppression on storage error
        }

        // Register user switch receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_FOREGROUND);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(userReceiver, filter);

        // Start PogoEventSource and request snapshot
        pogoEventSource.start(this::onKeyboardEvent);
        pogoEventSource.requestSnapshot();

        // Activate user binding for current foreground user
        int initialUser = ActivityManager.getCurrentUser();
        coordinatorHandler.post(() -> handleUserSwitched(initialUser));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void validateCaller() {
        int callingUid = Binder.getCallingUid();
        int myUid = Process.myUid();
        int callingAppId = callingUid % 100000;
        int myAppId = myUid % 100000;
        if (callingAppId != myAppId) {
            throw new SecurityException("Caller UID " + callingUid + " does not belong to Pogo Keyboard Manager");
        }
    }

    private void handleRetryRequest(KeyboardIdentity identity) {
        Log.i(TAG, "handleRetryRequest for " + (identity != null ? identity.getAnonymizedAddress() : "null"));
        KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.RETRY_REQUESTED)
                .targetIdentity(identity)
                .userId(state.getCurrentUserId())
                .generation(state.getCurrentGeneration())
                .timestampMs(System.currentTimeMillis())
                .build();
        onKeyboardEvent(event);
    }

    private void handleUserSwitched(int newUserId) {
        Log.i(TAG, "handleUserSwitched: newUserId=" + newUserId);
        KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.USER_SWITCHED)
                .intValue(newUserId)
                .timestampMs(System.currentTimeMillis())
                .build();
        onKeyboardEvent(event);
    }

    private void onKeyboardEvent(KeyboardEvent event) {
        if (event == null) return;
        long now = System.currentTimeMillis();
        KeyboardCoordinator.TransitionResult result = coordinator.handle(event, state, now);
        this.state = result.getNextState();

        for (KeyboardAction action : result.getActions()) {
            executeAction(action);
        }
    }

    private void executeAction(KeyboardAction action) {
        Log.d(TAG, "executeAction: " + action.getType());
        switch (action.getType()) {
            case PERSIST_RECORD: {
                if (action.getTargetRecord() != null) {
                    try {
                        identityStore.save(action.getTargetRecord());
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to persist TargetRecord", e);
                        // Enter suppression on storage failure
                        onKeyboardEvent(KeyboardEvent.builder(KeyboardEvent.Type.TARGET_FORGOTTEN)
                                .targetIdentity(action.getTargetIdentity())
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .stringValue(TargetRecord.SUPPRESSION_REASON_STORAGE_FAILURE)
                                .timestampMs(System.currentTimeMillis())
                                .build());
                    }
                }
                break;
            }
            case NOTIFY_USER_SWITCH: {
                KeyboardIdentity target = state.getTargetRecord() != null
                        ? state.getTargetRecord().getIdentity() : null;
                userBinding.activate(action.getUserId(), action.getGeneration(), target);
                break;
            }
            case SCHEDULE_RETRY: {
                final long gen = action.getGeneration();
                final int user = action.getUserId();
                final KeyboardIdentity target = action.getTargetIdentity();
                coordinatorHandler.postDelayed(() -> {
                    if (state.getCurrentGeneration() == gen && state.getCurrentUserId() == user) {
                        onKeyboardEvent(KeyboardEvent.builder(KeyboardEvent.Type.RETRY_TIMER_EXPIRED)
                                .targetIdentity(target)
                                .userId(user)
                                .generation(gen)
                                .timestampMs(System.currentTimeMillis())
                                .build());
                    }
                }, action.getDelayMs());
                break;
            }
            case CANCEL_RETRY: {
                // Handled in coordinator state
                break;
            }
            default: {
                // Forward bluetooth action to ActiveUserBinding
                userBinding.execute(action);
                break;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            writer.println("Permission Denial: can't dump PogoKeyboardService from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }

        writer.println("Pogo Keyboard Manager Dump:");
        writer.println("  Active User ID: " + state.getCurrentUserId());
        writer.println("  Generation: " + state.getCurrentGeneration());
        writer.println("  Physical State: " + state.getPhysicalState());
        writer.println("  BT Enabled: " + state.isBtEnabled());
        writer.println("  Profile Ready: " + state.isProfileReady());
        writer.println("  Bond State: " + state.getBondState());
        writer.println("  HID State: " + state.getHidState());

        writer.println("  Is Scanning: " + state.isScanning());
        writer.println("  GATT Latency Active: " + state.isGattLatencyActive());
        writer.println("  Retry Count: " + state.getRetryCount());

        TargetRecord rec = state.getTargetRecord();
        if (rec != null) {
            writer.println("  Target Record:");
            writer.println("    Target Address: " + rec.getIdentity().getAnonymizedAddress());
            writer.println("    Auto Pair Suppressed: " + rec.isAutoPairSuppressed());
            writer.println("    Suppression Reason: " + rec.getSuppressionReason());
            writer.println("    Last Known Bonded: " + rec.isLastKnownBonded());
            writer.println("    Record Version: " + rec.getRecordVersion());
        } else {
            writer.println("  Target Record: null");
        }

        ActiveUserBinding.PendingStopSession pendingStop = userBinding.getPendingStopSession();
        if (pendingStop != null) {
            writer.println("  Pending Stop Session: oldUser=" + pendingStop.oldUserId
                    + ", oldGen=" + pendingStop.oldGeneration
                    + ", stopRequestId=" + pendingStop.stopRequestId);
        } else {
            writer.println("  Pending Stop Session: none");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Destroying PogoKeyboardService");
        unregisterReceiver(userReceiver);
        pogoEventSource.close();
        userBinding.deactivate();
        if (coordinatorThread != null) {
            coordinatorThread.quitSafely();
        }
    }
}
