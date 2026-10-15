package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.util.Log;

import java.util.Objects;

/**
 * Manages dedicated GATT client for low-latency interval (~15ms) on the target keyboard
 * when detached and HID connected.
 *
 * Runs on the executor's serial Handler/Dispatcher.
 */
public class LatencyClient {

    private static final String TAG = "LatencyClient";
    public static final long LATENCY_REQUEST_RETRY_DELAY_MS = 2000L;

    private final PlatformBluetoothAdapter adapter;

    private KeyboardIdentity currentTarget;
    private int currentUserId;
    private long currentGeneration;

    private BluetoothGatt activeGatt;
    private Runnable pendingFollowUpRunnable;

    public LatencyClient(PlatformBluetoothAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    /**
     * Start low-latency GATT connection for the given target keyboard.
     * Guaranteed single instance: closes previous GATT client before connecting new one.
     */
    public synchronized void start(KeyboardIdentity target, int userId, long generation) {
        Objects.requireNonNull(target, "target must not be null");

        // Single instance guarantee: stop any active GATT client
        stop();

        this.currentTarget = target;
        this.currentUserId = userId;
        this.currentGeneration = generation;

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) {
            Log.w(TAG, "Cannot get remote device for target: " + target.getAnonymizedAddress());
            return;
        }

        final long sessionGen = generation;
        final int sessionUser = userId;
        final KeyboardIdentity sessionTarget = target;

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                adapter.getDispatcher().post(() -> {
                    // Check if callback belongs to current session
                    if (sessionGen != currentGeneration || sessionUser != currentUserId
                            || !sessionTarget.equals(currentTarget) || gatt != activeGatt) {
                        Log.d(TAG, "Dropping stale GATT connection state change (status=" + status + ", newState=" + newState + ")");
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT connected for " + sessionTarget.getAnonymizedAddress() + ", requesting HIGH priority");
                        // 1. Request HIGH immediately
                        boolean requested = adapter.requestConnectionPriority(gatt, BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        if (!requested) {
                            Log.w(TAG, "Initial HIGH connection priority request failed for " + sessionTarget.getAnonymizedAddress());
                        }

                        // 2. Schedule follow-up request 2000ms later
                        cancelPendingFollowUp();
                        pendingFollowUpRunnable = () -> {
                            if (sessionGen == currentGeneration && sessionUser == currentUserId
                                    && sessionTarget.equals(currentTarget) && gatt == activeGatt) {
                                Log.i(TAG, "Requesting follow-up HIGH priority (2000ms delay) for " + sessionTarget.getAnonymizedAddress());
                                boolean followUpRequested = adapter.requestConnectionPriority(gatt, BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                                if (!followUpRequested) {
                                    Log.w(TAG, "Follow-up HIGH priority request failed for " + sessionTarget.getAnonymizedAddress());
                                }
                            }
                        };
                        adapter.getDispatcher().postDelayed(pendingFollowUpRunnable, LATENCY_REQUEST_RETRY_DELAY_MS);

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "GATT disconnected for " + sessionTarget.getAnonymizedAddress() + ", closing client");
                        stop();
                    }
                });
            }

            // Callback for connection parameter updates on platforms supporting it
            public void onConnectionUpdated(BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
                adapter.getDispatcher().post(() -> {
                    if (sessionGen != currentGeneration || sessionUser != currentUserId
                            || !sessionTarget.equals(currentTarget) || gatt != activeGatt) {
                        return;
                    }
                    Log.i(TAG, "GATT connection updated for " + sessionTarget.getAnonymizedAddress()
                            + ": interval=" + interval + " (" + (interval * 1.25) + "ms)"
                            + ", latency=" + latency
                            + ", timeout=" + timeout + " (" + (timeout * 10) + "ms)"
                            + ", status=" + status);
                });
            }
        };

        Log.i(TAG, "Connecting GATT client (TRANSPORT_LE) for " + target.getAnonymizedAddress()
                + " (user=" + userId + ", gen=" + generation + ")");
        activeGatt = adapter.connectGatt(device, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (activeGatt == null) {
            Log.w(TAG, "Failed to initiate GATT connection for " + target.getAnonymizedAddress());
        }
    }

    /**
     * Closes GATT client, cancels any pending 2000ms runnable, resets state.
     */
    public synchronized void stop() {
        cancelPendingFollowUp();
        if (activeGatt != null) {
            Log.i(TAG, "Stopping and closing active GATT client");
            adapter.closeGatt(activeGatt);
            activeGatt = null;
        }
        currentTarget = null;
        currentUserId = 0;
        currentGeneration = 0;
    }

    private void cancelPendingFollowUp() {
        if (pendingFollowUpRunnable != null) {
            adapter.getDispatcher().removeCallbacks(pendingFollowUpRunnable);
            pendingFollowUpRunnable = null;
        }
    }

    public synchronized boolean isActive() {
        return activeGatt != null;
    }

    public synchronized KeyboardIdentity getCurrentTarget() {
        return currentTarget;
    }
}
