package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake implementation of PlatformBluetoothAdapter for unit tests.
 */
public class FakePlatformBluetoothAdapter implements PlatformBluetoothAdapter {

    public static class FakeDispatcher implements Dispatcher {
        public static class ScheduledTask {
            public final Runnable runnable;
            public final long delayMillis;

            public ScheduledTask(Runnable runnable, long delayMillis) {
                this.runnable = runnable;
                this.delayMillis = delayMillis;
            }
        }

        public final List<Runnable> postedTasks = new ArrayList<>();
        public final List<ScheduledTask> scheduledTasks = new ArrayList<>();

        @Override
        public boolean post(Runnable r) {
            postedTasks.add(r);
            r.run();
            return true;
        }

        @Override
        public boolean postDelayed(Runnable r, long delayMillis) {
            scheduledTasks.add(new ScheduledTask(r, delayMillis));
            return true;
        }

        @Override
        public void removeCallbacks(Runnable r) {
            scheduledTasks.removeIf(task -> task.runnable == r);
            postedTasks.removeIf(task -> task == r);
        }

        public void runScheduledTasks() {
            List<ScheduledTask> copy = new ArrayList<>(scheduledTasks);
            scheduledTasks.clear();
            for (ScheduledTask task : copy) {
                task.runnable.run();
            }
        }
    }

    public final FakeDispatcher dispatcher = new FakeDispatcher();

    public boolean scannerAvailable = true;
    public boolean scanStarted = false;
    public List<ScanFilter> scanFilters;
    public ScanSettings scanSettings;
    public ScanCallback scanCallback;

    public final List<BroadcastReceiver> registeredReceivers = new ArrayList<>();
    public final List<IntentFilter> registeredFilters = new ArrayList<>();

    public BluetoothProfile.ServiceListener profileListener;
    public BluetoothProfile activeProfileProxy;
    public boolean profileProxyClosed = false;

    public final Map<String, Integer> bondStates = new HashMap<>();
    public final List<String> createBondCalls = new ArrayList<>();
    public final List<Integer> createBondTransports = new ArrayList<>();
    public final List<String> cancelBondCalls = new ArrayList<>();
    public boolean createBondResult = true;

    public final Map<String, Integer> hidPolicies = new HashMap<>();
    public final Map<String, Integer> hidConnectionStates = new HashMap<>();
    public final List<String> hidConnectCalls = new ArrayList<>();
    public final List<String> hidDisconnectCalls = new ArrayList<>();
    public boolean connectHidResult = true;

    // GATT mocks
    public BluetoothGattCallback lastGattCallback;
    public BluetoothGatt activeGatt;
    public boolean gattClosed = false;
    public final List<Integer> requestedPriorities = new ArrayList<>();
    public boolean requestPriorityResult = true;

    @Override
    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        registeredReceivers.add(receiver);
        registeredFilters.add(filter);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        registeredReceivers.remove(receiver);
    }

    @Override
    public boolean getProfileProxy(int profile, BluetoothProfile.ServiceListener listener) {
        this.profileListener = listener;
        return true;
    }

    @Override
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {
        this.profileProxyClosed = true;
        this.activeProfileProxy = null;
    }

    public boolean btEnabled = false;

    @Override
    public boolean isBluetoothEnabled() {
        return btEnabled;
    }

    @Override
    public boolean isScannerAvailable() {
        return scannerAvailable;
    }

    @Override
    public void startScan(List<ScanFilter> filters, ScanSettings settings, ScanCallback callback) {
        this.scanStarted = true;
        this.scanFilters = filters;
        this.scanSettings = settings;
        this.scanCallback = callback;
    }

    @Override
    public void stopScan(ScanCallback callback) {
        this.scanStarted = false;
        this.scanCallback = null;
    }

    @Override
    public BluetoothDevice getRemoteDevice(String address) {
        return TestObjectFactory.createBluetoothDevice(address);
    }

    @Override
    public int getBondState(BluetoothDevice device) {
        return bondStates.getOrDefault(device.getAddress(), BluetoothDevice.BOND_NONE);
    }

    @Override
    public boolean createBond(BluetoothDevice device, int transport) {
        createBondCalls.add(device.getAddress());
        createBondTransports.add(transport);
        return createBondResult;
    }

    @Override
    public boolean cancelBondProcess(BluetoothDevice device) {
        cancelBondCalls.add(device.getAddress());
        return true;
    }

    @Override
    public boolean setHidConnectionPolicy(BluetoothHidHost hidHost, BluetoothDevice device, int policy) {
        hidPolicies.put(device.getAddress(), policy);
        return true;
    }

    @Override
    public int getHidConnectionState(BluetoothHidHost hidHost, BluetoothDevice device) {
        return hidConnectionStates.getOrDefault(device.getAddress(), BluetoothProfile.STATE_DISCONNECTED);
    }

    @Override
    public boolean connectHid(BluetoothHidHost hidHost, BluetoothDevice device) {
        hidConnectCalls.add(device.getAddress());
        return connectHidResult;
    }

    @Override
    public boolean disconnectHid(BluetoothHidHost hidHost, BluetoothDevice device) {
        hidDisconnectCalls.add(device.getAddress());
        return true;
    }

    public final List<BluetoothGatt> closedGatts = new ArrayList<>();

    @Override
    public BluetoothGatt connectGatt(BluetoothDevice device, boolean autoConnect, BluetoothGattCallback callback, int transport) {
        this.lastGattCallback = callback;
        this.activeGatt = TestObjectFactory.createBluetoothGatt();
        this.gattClosed = false;
        return activeGatt;
    }

    @Override
    public void closeGatt(BluetoothGatt gatt) {
        this.gattClosed = true;
        this.closedGatts.add(gatt);
        if (this.activeGatt == gatt) {
            this.activeGatt = null;
        }
    }

    @Override
    public boolean requestConnectionPriority(BluetoothGatt gatt, int priority) {
        requestedPriorities.add(priority);
        return requestPriorityResult;
    }
}
