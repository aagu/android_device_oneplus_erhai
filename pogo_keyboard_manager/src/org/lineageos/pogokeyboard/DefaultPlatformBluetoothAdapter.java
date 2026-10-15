package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Standard implementation of PlatformBluetoothAdapter backed by real Android platform Bluetooth APIs.
 */
public class DefaultPlatformBluetoothAdapter implements PlatformBluetoothAdapter {

    private static final String TAG = "DefaultPlatformBtAdapter";

    private final Context context;
    private final Handler handler;
    private final Dispatcher dispatcher;

    public DefaultPlatformBluetoothAdapter(Context context, Handler handler) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.dispatcher = new Dispatcher() {
            @Override
            public boolean post(Runnable r) {
                return handler.post(r);
            }

            @Override
            public boolean postDelayed(Runnable r, long delayMillis) {
                return handler.postDelayed(r, delayMillis);
            }

            @Override
            public void removeCallbacks(Runnable r) {
                handler.removeCallbacks(r);
            }
        };
    }

    private BluetoothAdapter getBluetoothAdapter() {
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            if (bm != null) {
                BluetoothAdapter adapter = bm.getAdapter();
                if (adapter != null) {
                    return adapter;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get BluetoothAdapter from BluetoothManager", e);
        }
        return BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        context.registerReceiver(receiver, filter, null, handler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered
        }
    }

    @Override
    public boolean getProfileProxy(int profile, BluetoothProfile.ServiceListener listener) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) {
            Log.w(TAG, "getProfileProxy: BluetoothAdapter is null, cannot request profile " + profile);
            return false;
        }
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean result = adapter.getProfileProxy(appContext, listener, profile);
        Log.i(TAG, "getProfileProxy profile=" + profile + " returned " + result);
        return result;
    }

    @Override
    public void closeProfileProxy(int profile, BluetoothProfile proxy) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter != null && proxy != null) {
            adapter.closeProfileProxy(profile, proxy);
        }
    }

    @Override
    public boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        boolean enabled = adapter != null && adapter.isEnabled();
        Log.d(TAG, "isBluetoothEnabled: adapter=" + (adapter != null) + ", isEnabled=" + enabled);
        return enabled;
    }

    @Override
    public boolean isScannerAvailable() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        return adapter != null && adapter.isEnabled() && adapter.getBluetoothLeScanner() != null;
    }

    @Override
    public void startScan(List<ScanFilter> filters, ScanSettings settings, ScanCallback callback) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter != null) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.startScan(filters, settings, callback);
            }
        }
    }

    @Override
    public void stopScan(ScanCallback callback) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter != null) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.stopScan(callback);
            }
        }
    }

    @Override
    public BluetoothDevice getRemoteDevice(String address) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) return null;
        return adapter.getRemoteDevice(address);
    }

    @Override
    public int getBondState(BluetoothDevice device) {
        if (device == null) return BluetoothDevice.BOND_NONE;
        return device.getBondState();
    }

    @Override
    public boolean createBond(BluetoothDevice device, int transport) {
        if (device == null) return false;
        return device.createBond(transport);
    }

    @Override
    public boolean cancelBondProcess(BluetoothDevice device) {
        if (device == null) return false;
        return device.cancelBondProcess();
    }

    @Override
    public boolean setHidConnectionPolicy(BluetoothHidHost hidHost, BluetoothDevice device, int policy) {
        if (hidHost == null || device == null) return false;
        return hidHost.setConnectionPolicy(device, policy);
    }

    @Override
    public int getHidConnectionState(BluetoothHidHost hidHost, BluetoothDevice device) {
        if (hidHost == null || device == null) return BluetoothProfile.STATE_DISCONNECTED;
        return hidHost.getConnectionState(device);
    }

    @Override
    public boolean connectHid(BluetoothHidHost hidHost, BluetoothDevice device) {
        if (hidHost == null || device == null) return false;
        try {
            java.lang.reflect.Method m = hidHost.getClass().getMethod("connect", BluetoothDevice.class);
            return (Boolean) m.invoke(hidHost, device);
        } catch (Exception e) {
            Log.w(TAG, "Reflection call connect failed", e);
            return false;
        }
    }

    @Override
    public boolean disconnectHid(BluetoothHidHost hidHost, BluetoothDevice device) {
        if (hidHost == null || device == null) return false;
        try {
            java.lang.reflect.Method m = hidHost.getClass().getMethod("disconnect", BluetoothDevice.class);
            return (Boolean) m.invoke(hidHost, device);
        } catch (Exception e) {
            Log.w(TAG, "Reflection call disconnect failed", e);
            return false;
        }
    }

    @Override
    public BluetoothGatt connectGatt(BluetoothDevice device, boolean autoConnect, BluetoothGattCallback callback, int transport) {
        if (device == null) return null;
        return device.connectGatt(context, autoConnect, callback, transport);
    }

    @Override
    public void closeGatt(BluetoothGatt gatt) {
        if (gatt != null) {
            gatt.close();
        }
    }

    @Override
    public boolean requestConnectionPriority(BluetoothGatt gatt, int priority) {
        if (gatt == null) return false;
        return gatt.requestConnectionPriority(priority);
    }
}
