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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import org.lineageos.pogokeyboard.aidl.IBluetoothExecutor;
import org.lineageos.pogokeyboard.aidl.IBluetoothExecutorCallback;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manages the binding between System User Coordinator and active foreground user's BluetoothExecutorService.
 * Enforces single active session, tracks PendingStopSession, and prevents race conditions during user switching.
 */
public class ActiveUserBinding {

    private static final String TAG = "ActiveUserBinding";
    public static final long STOP_TIMEOUT_MS = 3000L;

    public static final class PendingStopSession {
        public final int oldUserId;
        public final int oldUid;
        public final long oldGeneration;
        public final long stopRequestId;
        public final IBinder oldBinder;
        public final IBluetoothExecutor oldExecutor;
        public final ServiceConnection connection;

        public PendingStopSession(int oldUserId, int oldUid, long oldGeneration, long stopRequestId,
                                  IBinder oldBinder, IBluetoothExecutor oldExecutor, ServiceConnection connection) {
            this.oldUserId = oldUserId;
            this.oldUid = oldUid;
            this.oldGeneration = oldGeneration;
            this.stopRequestId = stopRequestId;
            this.oldBinder = oldBinder;
            this.oldExecutor = oldExecutor;
            this.connection = connection;
        }
    }

    private final Context context;
    private final Handler handler;
    private final Consumer<KeyboardEvent> eventConsumer;
    private final AtomicLong stopRequestIdGenerator = new AtomicLong(1);

    // Active session state
    private int currentUserId = -1;
    private long currentGeneration = -1;
    private KeyboardIdentity currentIdentity;
    private IBluetoothExecutor currentExecutor;
    private IBinder currentBinder;
    private ServiceConnection currentConnection;

    // Pending target to activate once pending stop is confirmed
    private int pendingTargetUserId = -1;
    private long pendingTargetGeneration = -1;
    private KeyboardIdentity pendingTargetIdentity;

    // Pending stop tracking
    private PendingStopSession pendingStopSession;
    private Runnable stopTimeoutRunnable;

    public ActiveUserBinding(Context context, Handler handler, Consumer<KeyboardEvent> eventConsumer) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
    }

    /**
     * Activates executor for the specified user and generation.
     */
    public synchronized void activate(int userId, long generation, KeyboardIdentity identity) {
        Log.i(TAG, "activate: user=" + userId + ", gen=" + generation);
        if (currentUserId == userId && currentGeneration == generation && currentExecutor != null) {
            Log.d(TAG, "Already active for user=" + userId + ", gen=" + generation);
            return;
        }

        // If there is an active session, initiate stop first
        if (currentExecutor != null || currentBinder != null) {
            initiateStop(userId, generation, identity);
            return;
        }

        // If a stop is currently in progress, update pending target to latest (Fast A->B->C transition)
        if (pendingStopSession != null) {
            Log.i(TAG, "Stop already pending, updating target user to " + userId + ", gen=" + generation);
            pendingTargetUserId = userId;
            pendingTargetGeneration = generation;
            pendingTargetIdentity = identity;
            return;
        }

        // Directly bind new session
        bindToUser(userId, generation, identity);
    }

    private void initiateStop(int nextUserId, long nextGeneration, KeyboardIdentity nextIdentity) {
        pendingTargetUserId = nextUserId;
        pendingTargetGeneration = nextGeneration;
        pendingTargetIdentity = nextIdentity;

        long stopReqId = stopRequestIdGenerator.getAndIncrement();
        int oldUserId = currentUserId;
        long oldGen = currentGeneration;
        IBinder oldBinder = currentBinder;
        IBluetoothExecutor oldExec = currentExecutor;
        ServiceConnection oldConn = currentConnection;

        // Invalidate active session immediately
        currentUserId = -1;
        currentGeneration = -1;
        currentIdentity = null;
        currentExecutor = null;
        currentBinder = null;
        currentConnection = null;

        pendingStopSession = new PendingStopSession(oldUserId, 0, oldGen, stopReqId, oldBinder, oldExec, oldConn);
        Log.i(TAG, "Initiated stop for user=" + oldUserId + ", gen=" + oldGen + ", stopReqId=" + stopReqId);

        // Schedule timeout
        stopTimeoutRunnable = () -> handleStopTimeout(stopReqId);
        handler.postDelayed(stopTimeoutRunnable, STOP_TIMEOUT_MS);

        try {
            oldExec.stop(oldGen, stopReqId);
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception calling stop, old executor might be dead", e);
            // Binder dead or broken, verify through death recipient or abort
        }
    }

    private void handleStopTimeout(long stopReqId) {
        synchronized (this) {
            if (pendingStopSession != null && pendingStopSession.stopRequestId == stopReqId) {
                Log.w(TAG, "Stop request timed out for user " + pendingStopSession.oldUserId + ", not activating new session");
                // Timeout is not equivalent to clean shutdown per design spec. Pause automatic transition.
                cleanupPendingStop();
            }
        }
    }

    /**
     * Called when onStopped is received from an executor.
     */
    public synchronized void onStoppedReceived(int userId, long generation, long stopRequestId, IBinder senderBinder) {
        if (pendingStopSession == null) {
            Log.w(TAG, "Received unexpected onStopped: user=" + userId + ", gen=" + generation + ", reqId=" + stopRequestId);
            return;
        }

        if (pendingStopSession.stopRequestId != stopRequestId
                || pendingStopSession.oldUserId != userId
                || pendingStopSession.oldGeneration != generation) {
            Log.w(TAG, "onStopped parameters do not match pendingStopSession");
            return;
        }

        if (pendingStopSession.oldBinder != null && senderBinder != null
                && !pendingStopSession.oldBinder.equals(senderBinder)) {
            Log.w(TAG, "onStopped senderBinder mismatch");
            return;
        }

        Log.i(TAG, "Confirmed stop for user " + userId + ", activating pending target user " + pendingTargetUserId);
        if (stopTimeoutRunnable != null) {
            handler.removeCallbacks(stopTimeoutRunnable);
            stopTimeoutRunnable = null;
        }

        cleanupPendingStop();

        if (pendingTargetUserId >= 0) {
            int nextUser = pendingTargetUserId;
            long nextGen = pendingTargetGeneration;
            KeyboardIdentity nextId = pendingTargetIdentity;
            pendingTargetUserId = -1;
            pendingTargetGeneration = -1;
            pendingTargetIdentity = null;
            bindToUser(nextUser, nextGen, nextId);
        }
    }

    private void cleanupPendingStop() {
        if (pendingStopSession != null) {
            if (pendingStopSession.connection != null) {
                try {
                    context.unbindService(pendingStopSession.connection);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to unbind old connection", e);
                }
            }
            pendingStopSession = null;
        }
    }

    private void bindToUser(int userId, long generation, KeyboardIdentity identity) {
        this.currentUserId = userId;
        this.currentGeneration = generation;
        this.currentIdentity = identity;

        Intent intent = new Intent(context, BluetoothExecutorService.class);
        final int targetUser = userId;
        final long targetGen = generation;

        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                handler.post(() -> handleServiceConnected(targetUser, targetGen, service, this));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                handler.post(() -> handleServiceDisconnected(targetUser, targetGen));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                handler.post(() -> handleBindingDied(targetUser, targetGen));
            }
        };

        this.currentConnection = conn;
        Log.i(TAG, "Binding to BluetoothExecutorService for user " + userId + " (gen=" + generation + ")");
        try {
            boolean bound = context.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE, UserHandle.of(userId));
            if (!bound) {
                Log.w(TAG, "bindServiceAsUser returned false for user " + userId);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to bind to user " + userId, e);
        }
    }

    private void handleServiceConnected(int userId, long generation, IBinder binder, ServiceConnection connection) {
        if (currentUserId != userId || currentGeneration != generation) {
            Log.w(TAG, "Ignoring service connection for outdated session: " + userId + ", gen=" + generation);
            try {
                context.unbindService(connection);
            } catch (RuntimeException ignored) {}
            return;
        }

        this.currentBinder = binder;
        this.currentExecutor = IBluetoothExecutor.Stub.asInterface(binder);

        final long sessionGen = generation;
        final int sessionUser = userId;

        // Dedicated callback instance per binding session
        IBluetoothExecutorCallback callback = new IBluetoothExecutorCallback.Stub() {
            @Override
            public void onEvent(KeyboardEvent event) {
                handler.post(() -> {
                    if (currentUserId == sessionUser && currentGeneration == sessionGen) {
                        eventConsumer.accept(event);
                    } else {
                        Log.d(TAG, "Dropping event from superseded session");
                    }
                });
            }

            @Override
            public void onStopped(int user, long gen, long stopRequestId) {
                handler.post(() -> onStoppedReceived(user, gen, stopRequestId, binder));
            }
        };

        try {
            currentExecutor.activate(currentIdentity, currentUserId, currentGeneration, callback);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activate on executor", e);
        }
    }

    private void handleServiceDisconnected(int userId, long generation) {
        Log.i(TAG, "handleServiceDisconnected: user=" + userId + ", gen=" + generation);
        if (currentUserId == userId && currentGeneration == generation) {
            currentExecutor = null;
            currentBinder = null;
        }
    }

    private void handleBindingDied(int userId, long generation) {
        Log.i(TAG, "handleBindingDied: user=" + userId + ", gen=" + generation);
        if (currentUserId == userId && currentGeneration == generation) {
            currentExecutor = null;
            currentBinder = null;
        }
        if (pendingStopSession != null && pendingStopSession.oldUserId == userId) {
            // Old executor died during stop transition, confirm termination
            onStoppedReceived(userId, pendingStopSession.oldGeneration, pendingStopSession.stopRequestId, null);
        }
    }

    public synchronized void execute(KeyboardAction action) {
        if (currentExecutor != null) {
            try {
                currentExecutor.execute(action);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send execute to executor", e);
            }
        } else {
            Log.w(TAG, "No active executor to execute action: " + action.getType());
        }
    }

    public synchronized void deactivate() {
        if (currentExecutor != null || currentBinder != null) {
            initiateStop(-1, -1, null);
        }
    }

    public synchronized boolean hasActiveExecutor() {
        return currentExecutor != null;
    }

    public synchronized int getCurrentUserId() {
        return currentUserId;
    }

    public synchronized long getCurrentGeneration() {
        return currentGeneration;
    }

    public synchronized PendingStopSession getPendingStopSession() {
        return pendingStopSession;
    }
}
