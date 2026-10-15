package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controller managing Bluetooth scanning, bonding, and HID Host profile operations.
 * Receives actions via execute(KeyboardAction) and emits events to Consumer<KeyboardEvent>.
 * Runs entirely on the executor's serial Handler/Dispatcher.
 */
public class BluetoothController {

    private static final String TAG = "BluetoothController";

    private final PlatformBluetoothAdapter adapter;
    private final Consumer<KeyboardEvent> eventConsumer;

    private BluetoothHidHost hidHostProxy;
    private boolean profileProxyRegistered = false;
    private boolean receiverRegistered = false;
    private boolean closed = false;

    // Active session tracking
    private KeyboardIdentity activeTarget;
    private int activeUserId = 0;
    private long activeGeneration = 0;

    private boolean isScanning = false;
    private KeyboardIdentity scanningTarget;
    private int scanningUserId;
    private long scanningGeneration;

    private ScanCallback scanCallback;
    private Runnable scanTimeout;

    private ScanCallback newScanCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                adapter.getDispatcher().post(() -> {
                    if (scanCallback == this && !closed) handleScanResult(result);
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                adapter.getDispatcher().post(() -> {
                    if (scanCallback != this || closed || results == null) return;
                    for (ScanResult result : results) {
                        if (scanCallback != this) break;
                        handleScanResult(result);
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                adapter.getDispatcher().post(() -> {
                    if (scanCallback != this || closed) return;
                    Log.w(TAG, "BLE scan failed with error: " + errorCode);
                    finishScanTimeout(this);
                });
            }
        };
    }

    private void finishScanTimeout(ScanCallback callback) {
        if (closed || !isScanning || scanCallback != callback) return;
        KeyboardEvent timeout = KeyboardEvent.builder(KeyboardEvent.Type.SCAN_TIMEOUT)
                .targetIdentity(scanningTarget)
                .userId(scanningUserId)
                .generation(scanningGeneration)
                .timestampMs(System.currentTimeMillis())
                .build();
        handleScanStop();
        eventConsumer.accept(timeout);
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.i(TAG, "profileListener.onServiceConnected: profile=" + profile + ", closed=" + closed);
            adapter.getDispatcher().post(() -> {
                if (profile == BluetoothProfile.HID_HOST) {
                    if (closed) {
                        Log.i(TAG, "HID_HOST proxy connected after controller was closed, closing immediately");
                        adapter.closeProfileProxy(BluetoothProfile.HID_HOST, proxy);
                        return;
                    }
                    Log.i(TAG, "HID_HOST profile proxy connected successfully");
                    hidHostProxy = (BluetoothHidHost) proxy;
                    eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                            .targetIdentity(activeTarget)
                            .userId(activeUserId)
                            .generation(activeGeneration)
                            .flag(true)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                }
            });
        }

        @Override
        public void onServiceDisconnected(int profile) {
            adapter.getDispatcher().post(() -> {
                if (profile == BluetoothProfile.HID_HOST) {
                    Log.i(TAG, "HID_HOST profile proxy disconnected");
                    hidHostProxy = null;
                    if (!closed) {
                        eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                                .targetIdentity(activeTarget)
                                .userId(activeUserId)
                                .generation(activeGeneration)
                                .flag(false)
                                .timestampMs(System.currentTimeMillis())
                                .build());
                    }
                }
            });
        }
    };

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (closed || intent == null || intent.getAction() == null) return;
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                Log.d(TAG, "ACTION_STATE_CHANGED: state=" + state);
                if (state == BluetoothAdapter.STATE_ON) {
                    ensureProfileProxy();
                    eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                            .targetIdentity(activeTarget)
                            .userId(activeUserId)
                            .generation(activeGeneration)
                            .flag(true)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (hidHostProxy != null) {
                        adapter.closeProfileProxy(BluetoothProfile.HID_HOST, hidHostProxy);
                        hidHostProxy = null;
                    }
                    eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                            .targetIdentity(activeTarget)
                            .userId(activeUserId)
                            .generation(activeGeneration)
                            .flag(false)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                }
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null || device.getAddress() == null) {
                // Ignore intents with no device payload
                return;
            }

            KeyboardIdentity devIdentity = KeyboardIdentity.parseDriverAddress(device.getAddress()).orElse(null);
            // Drop broadcast events if devIdentity is null or does not match activeTarget (Finding 3)
            if (devIdentity == null) {
                Log.d(TAG, "Dropping broadcast with unparseable/invalid address: " + device.getAddress());
                return;
            }
            if (activeTarget != null && !activeTarget.equals(devIdentity)) {
                Log.d(TAG, "Dropping broadcast from non-target device: " + devIdentity.getAnonymizedAddress());
                return;
            }

            switch (action) {
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    int prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                    Log.d(TAG, "ACTION_BOND_STATE_CHANGED: device=" + anonymizeMac(device.getAddress())
                            + " bondState=" + bondState + " prev=" + prevBondState);

                    // Map Android bond state to KeyboardState.BondState ordinal
                    int bondOrdinal = KeyboardState.BondState.NONE.ordinal();
                    if (bondState == BluetoothDevice.BOND_BONDING) {
                        bondOrdinal = KeyboardState.BondState.BONDING.ordinal();
                    } else if (bondState == BluetoothDevice.BOND_BONDED) {
                        bondOrdinal = KeyboardState.BondState.BONDED.ordinal();
                    }

                    eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                            .targetIdentity(devIdentity)
                            .userId(activeUserId)
                            .generation(activeGeneration)
                            .intValue(bondOrdinal)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                    break;
                }

                case BluetoothDevice.ACTION_PAIRING_REQUEST: {
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    Log.i(TAG, "ACTION_PAIRING_REQUEST: device=" + anonymizeMac(device.getAddress())
                            + " variant=" + variant + " (do not swallow dialogs)");
                    // Spec 6.3: Do NOT swallow PIN/confirmation dialogs. Keep standard interactive flow.
                    break;
                }

                case BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED: {
                    int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                    int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
                    Log.d(TAG, "HID ACTION_CONNECTION_STATE_CHANGED: device=" + anonymizeMac(device.getAddress())
                            + " state=" + state + " prev=" + prevState);

                    // Map Android BluetoothProfile state to KeyboardState.HidState ordinal
                    int hidOrdinal = KeyboardState.HidState.DISCONNECTED.ordinal();
                    if (state == BluetoothProfile.STATE_CONNECTING) {
                        hidOrdinal = KeyboardState.HidState.CONNECTING.ordinal();
                    } else if (state == BluetoothProfile.STATE_CONNECTED) {
                        hidOrdinal = KeyboardState.HidState.CONNECTED.ordinal();
                    } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
                        hidOrdinal = KeyboardState.HidState.DISCONNECTING.ordinal();
                    }

                    eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.HID_STATE_CHANGED)
                            .targetIdentity(devIdentity)
                            .userId(activeUserId)
                            .generation(activeGeneration)
                            .intValue(hidOrdinal)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                    break;
                }
            }
        }
    };

    public BluetoothController(PlatformBluetoothAdapter adapter, Consumer<KeyboardEvent> eventConsumer) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");

        // Register broadcast receivers for bluetooth state, bond state, and HID connection state
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        adapter.registerReceiver(broadcastReceiver, filter);
        receiverRegistered = true;

        // Register profile proxy listener
        ensureProfileProxy();

        // Emit initial bluetooth state if adapter is already enabled
        if (adapter.isBluetoothEnabled()) {
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                    .targetIdentity(activeTarget)
                    .userId(activeUserId)
                    .generation(activeGeneration)
                    .flag(true)
                    .timestampMs(System.currentTimeMillis())
                    .build());
        }
    }

    /**
     * Attempts to acquire HID_HOST profile proxy if not already bound and Bluetooth is enabled.
     */
    public synchronized void ensureProfileProxy() {
        if (closed) return;
        boolean btEnabled = adapter.isBluetoothEnabled();
        Log.i(TAG, "ensureProfileProxy: hidHostProxy=" + (hidHostProxy != null) + ", btEnabled=" + btEnabled);
        if (hidHostProxy == null && btEnabled) {
            profileProxyRegistered = adapter.getProfileProxy(BluetoothProfile.HID_HOST, profileListener);
            Log.i(TAG, "ensureProfileProxy requested HID_HOST profile proxy, result=" + profileProxyRegistered);
        }
    }

    /**
     * Updates active session target, user ID, and generation on executor activation.
     */
    public synchronized void setActiveSession(KeyboardIdentity identity, int userId, long generation) {
        Log.i(TAG, "setActiveSession: target=" + (identity != null ? identity.getAnonymizedAddress() : "null")
                + ", userId=" + userId + ", gen=" + generation);
        this.activeTarget = identity;
        this.activeUserId = userId;
        this.activeGeneration = generation;
    }

    /**
     * Executes actions emitted by KeyboardCoordinator on the serial executor.
     */
    public void execute(KeyboardAction action) {
        Objects.requireNonNull(action, "action must not be null");
        if (closed) {
            Log.w(TAG, "Controller is closed, ignoring execute: " + action.getType());
            return;
        }

        // Update active session metadata
        if (action.getTargetIdentity() != null) {
            this.activeTarget = action.getTargetIdentity();
        }
        if (action.getUserId() != 0) {
            this.activeUserId = action.getUserId();
        }
        if (action.getGeneration() != 0) {
            this.activeGeneration = action.getGeneration();
        }

        switch (action.getType()) {
            case SCAN_START: {
                handleScanStart(action);
                break;
            }

            case SCAN_STOP: {
                handleScanStop();
                break;
            }

            case CREATE_BOND: {
                handleCreateBond(action);
                break;
            }

            case CANCEL_BOND: {
                handleCancelBond(action);
                break;
            }

            case SET_HID_POLICY: {
                handleSetHidPolicy(action);
                break;
            }

            case HID_CONNECT: {
                handleHidConnect(action);
                break;
            }

            case HID_DISCONNECT: {
                handleHidDisconnect(action);
                break;
            }

            default:
                // Other actions (PERSIST_RECORD, SCHEDULE_RETRY, GATT, etc.) are handled by coordinator/other services
                break;
        }
    }

    private void handleScanStart(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) {
            Log.w(TAG, "SCAN_START received with null targetIdentity, ignoring");
            return;
        }

        if (isScanning && target.equals(scanningTarget)
                && scanningUserId == action.getUserId()
                && scanningGeneration == action.getGeneration()) {
            return; // Duplicate snapshots must not restart the scan or its budget.
        }
        handleScanStop();
        if (!adapter.isScannerAvailable()) {
            Log.w(TAG, "BLE Scanner not available for SCAN_START");
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.SCAN_TIMEOUT)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .timestampMs(System.currentTimeMillis())
                    .build());
            return;
        }

        this.isScanning = true;
        this.scanningTarget = target;
        this.scanningUserId = action.getUserId();
        this.scanningGeneration = action.getGeneration();

        // Exact MAC address filter (Section 6.3)
        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceAddress(target.getFormattedAddress())
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        Log.i(TAG, "Starting BLE scan for exact target: " + target.getAnonymizedAddress()
                + " (user=" + action.getUserId() + ", gen=" + action.getGeneration() + ")");
        ScanCallback callback = newScanCallback();
        scanCallback = callback;
        scanTimeout = () -> finishScanTimeout(callback);
        adapter.getDispatcher().postDelayed(scanTimeout,
                action.getDelayMs() > 0 ? action.getDelayMs() : 5000L);
        adapter.startScan(Collections.singletonList(filter), settings, callback);
    }

    private void handleScanStop() {
        if (scanTimeout != null) {
            adapter.getDispatcher().removeCallbacks(scanTimeout);
            scanTimeout = null;
        }
        ScanCallback callback = scanCallback;
        scanCallback = null;
        if (isScanning) {
            Log.i(TAG, "Stopping active BLE scan");
            isScanning = false;
            adapter.stopScan(callback);
            scanningTarget = null;
            scanningUserId = 0;
            scanningGeneration = 0;
        }
    }

    private void handleScanResult(ScanResult result) {
        if (!isScanning || result == null || result.getDevice() == null) {
            return;
        }

        BluetoothDevice device = result.getDevice();
        if (scanningTarget != null && scanningTarget.getFormattedAddress().equalsIgnoreCase(device.getAddress())) {
            Log.i(TAG, "BLE scan matched target: " + scanningTarget.getAnonymizedAddress());
            KeyboardIdentity matchedTarget = scanningTarget;
            int userId = scanningUserId;
            long gen = scanningGeneration;

            // Stop scan upon match
            handleScanStop();

            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.SCAN_RESULT_MATCHED)
                    .targetIdentity(matchedTarget)
                    .userId(userId)
                    .generation(gen)
                    .timestampMs(System.currentTimeMillis())
                    .build());
        }
    }

    private void handleCreateBond(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) return;

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) {
            Log.w(TAG, "Cannot get BluetoothDevice for CREATE_BOND: " + target.getAnonymizedAddress());
            return;
        }

        int currentBond = adapter.getBondState(device);
        if (currentBond == BluetoothDevice.BOND_BONDING) {
            Log.d(TAG, "Device already bonding, not calling createBond again: " + target.getAnonymizedAddress());
            return;
        }
        if (currentBond == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "Device already bonded: " + target.getAnonymizedAddress());
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .intValue(KeyboardState.BondState.BONDED.ordinal())
                    .timestampMs(System.currentTimeMillis())
                    .build());
            return;
        }

        Log.i(TAG, "Initiating createBond(TRANSPORT_LE) for: " + target.getAnonymizedAddress());
        boolean initiated = adapter.createBond(device, BluetoothDevice.TRANSPORT_LE);
        if (!initiated) {
            Log.w(TAG, "createBond(TRANSPORT_LE) returned false for " + target.getAnonymizedAddress());
            // Returned false means immediate failure
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .intValue(KeyboardState.BondState.NONE.ordinal())
                    .timestampMs(System.currentTimeMillis())
                    .build());
        }
    }

    private void handleCancelBond(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) return;

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) return;

        int currentBond = adapter.getBondState(device);
        if (currentBond == BluetoothDevice.BOND_BONDING) {
            Log.i(TAG, "Canceling bond process for: " + target.getAnonymizedAddress());
            adapter.cancelBondProcess(device);
        }
    }

    private void handleSetHidPolicy(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) return;

        if (hidHostProxy == null) {
            ensureProfileProxy();
        }
        if (hidHostProxy == null) {
            Log.w(TAG, "HID profile proxy is null, cannot setConnectionPolicy for " + target.getAnonymizedAddress());
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .flag(false)
                    .timestampMs(System.currentTimeMillis())
                    .build());
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) return;

        int policy = (action.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED)
                ? BluetoothProfile.CONNECTION_POLICY_ALLOWED
                : BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

        Log.i(TAG, "Setting HID connection policy to " + policy + " (" + action.getHidPolicy() + ") for "
                + target.getAnonymizedAddress());
        boolean success = adapter.setHidConnectionPolicy(hidHostProxy, device, policy);
        if (!success) {
            Log.w(TAG, "setConnectionPolicy failed for " + target.getAnonymizedAddress());
        }
    }

    private void handleHidConnect(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) return;

        if (hidHostProxy == null) {
            ensureProfileProxy();
        }
        if (hidHostProxy == null) {
            Log.w(TAG, "HID profile proxy is null, cannot connect HID for " + target.getAnonymizedAddress());
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .flag(false)
                    .timestampMs(System.currentTimeMillis())
                    .build());
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) return;

        int connState = adapter.getHidConnectionState(hidHostProxy, device);
        if (connState == BluetoothProfile.STATE_CONNECTED || connState == BluetoothProfile.STATE_CONNECTING) {
            Log.d(TAG, "HID already connected or connecting (" + connState + "), skipping duplicate connect: "
                    + target.getAnonymizedAddress());
            return;
        }

        Log.i(TAG, "Initiating HID connect for " + target.getAnonymizedAddress());
        boolean success = adapter.connectHid(hidHostProxy, device);
        if (!success) {
            Log.w(TAG, "connectHid returned false for " + target.getAnonymizedAddress());
            eventConsumer.accept(KeyboardEvent.builder(KeyboardEvent.Type.HID_STATE_CHANGED)
                    .targetIdentity(target)
                    .userId(action.getUserId())
                    .generation(action.getGeneration())
                    .intValue(KeyboardState.HidState.DISCONNECTED.ordinal())
                    .timestampMs(System.currentTimeMillis())
                    .build());
        }
    }

    private void handleHidDisconnect(KeyboardAction action) {
        KeyboardIdentity target = action.getTargetIdentity();
        if (target == null) return;

        if (hidHostProxy == null) {
            ensureProfileProxy();
        }
        if (hidHostProxy == null) {
            Log.w(TAG, "HID profile proxy is null, cannot disconnect HID for " + target.getAnonymizedAddress());
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(target.getFormattedAddress());
        if (device == null) return;

        int connState = adapter.getHidConnectionState(hidHostProxy, device);
        if (connState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d(TAG, "HID already disconnected for " + target.getAnonymizedAddress());
            return;
        }

        Log.i(TAG, "Initiating HID disconnect for " + target.getAnonymizedAddress());
        adapter.disconnectHid(hidHostProxy, device);
    }

    public void close() {
        Log.i(TAG, "Closing BluetoothController");
        closed = true;
        handleScanStop();

        if (receiverRegistered) {
            adapter.unregisterReceiver(broadcastReceiver);
            receiverRegistered = false;
        }

        if (profileProxyRegistered && hidHostProxy != null) {
            adapter.closeProfileProxy(BluetoothProfile.HID_HOST, hidHostProxy);
            hidHostProxy = null;
            profileProxyRegistered = false;
        }

        activeTarget = null;
        activeUserId = 0;
        activeGeneration = 0;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isProfileProxyReady() {
        return hidHostProxy != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter.isBluetoothEnabled();
    }

    // Helper for anonymizing MAC
    private static String anonymizeMac(String address) {
        if (address != null && address.length() == 17) {
            return "XX:XX:XX:XX:" + address.substring(12);
        }
        return "XX:XX:XX:XX:XX:XX";
    }
}
