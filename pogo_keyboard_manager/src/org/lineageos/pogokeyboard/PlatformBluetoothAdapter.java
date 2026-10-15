package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import java.util.List;

/**
 * Abstraction layer over Android platform Bluetooth subsystems.
 * Allows decoupling platform Binder calls and facilitating deterministic unit tests.
 */
public interface PlatformBluetoothAdapter {

    interface Dispatcher {
        boolean post(Runnable r);
        boolean postDelayed(Runnable r, long delayMillis);
        void removeCallbacks(Runnable r);
    }

    Dispatcher getDispatcher();

    // Broadcast registration
    void registerReceiver(BroadcastReceiver receiver, IntentFilter filter);
    void unregisterReceiver(BroadcastReceiver receiver);

    // Profile proxy
    boolean getProfileProxy(int profile, BluetoothProfile.ServiceListener listener);
    void closeProfileProxy(int profile, BluetoothProfile proxy);

    // Bluetooth state
    boolean isBluetoothEnabled();

    // Scanner
    boolean isScannerAvailable();
    void startScan(List<ScanFilter> filters, ScanSettings settings, ScanCallback callback);
    void stopScan(ScanCallback callback);

    // Device operations
    BluetoothDevice getRemoteDevice(String address);
    int getBondState(BluetoothDevice device);
    boolean createBond(BluetoothDevice device, int transport);
    boolean cancelBondProcess(BluetoothDevice device);

    // HID operations
    boolean setHidConnectionPolicy(BluetoothHidHost hidHost, BluetoothDevice device, int policy);
    int getHidConnectionState(BluetoothHidHost hidHost, BluetoothDevice device);
    boolean connectHid(BluetoothHidHost hidHost, BluetoothDevice device);
    boolean disconnectHid(BluetoothHidHost hidHost, BluetoothDevice device);

    // GATT operations
    BluetoothGatt connectGatt(BluetoothDevice device, boolean autoConnect, BluetoothGattCallback callback, int transport);
    void closeGatt(BluetoothGatt gatt);
    boolean requestConnectionPriority(BluetoothGatt gatt, int priority);
}
