package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothHidHost;

import java.lang.reflect.Constructor;

/**
 * Reflection helper to construct framework mock/test instances without depending on package-private constructors.
 */
public final class TestObjectFactory {

    private TestObjectFactory() {}

    public static BluetoothDevice createBluetoothDevice(String address) {
        try {
            Constructor<BluetoothDevice> c = BluetoothDevice.class.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            return c.newInstance(address);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct BluetoothDevice for test", e);
        }
    }

    public static BluetoothHidHost createBluetoothHidHost() {
        try {
            Constructor<?>[] constructors = BluetoothHidHost.class.getDeclaredConstructors();
            if (constructors.length == 0) {
                throw new IllegalStateException("No constructors in BluetoothHidHost");
            }
            Constructor<?> c = constructors[0];
            c.setAccessible(true);
            Class<?>[] types = c.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) args[i] = 0;
                else if (types[i] == boolean.class) args[i] = false;
                else args[i] = null;
            }
            return (BluetoothHidHost) c.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct BluetoothHidHost for test", e);
        }
    }

    public static BluetoothGatt createBluetoothGatt() {
        try {
            Constructor<?>[] constructors = BluetoothGatt.class.getDeclaredConstructors();
            if (constructors.length == 0) {
                throw new IllegalStateException("No constructors in BluetoothGatt");
            }
            Constructor<?> c = constructors[0];
            c.setAccessible(true);
            Class<?>[] types = c.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) args[i] = 0;
                else if (types[i] == boolean.class) args[i] = false;
                else args[i] = null;
            }
            return (BluetoothGatt) c.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct BluetoothGatt for test", e);
        }
    }

    public static android.bluetooth.le.ScanResult createScanResult(BluetoothDevice device) {
        try {
            Constructor<?>[] constructors = android.bluetooth.le.ScanResult.class.getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                Class<?>[] types = c.getParameterTypes();
                if (types.length == 1 && types[0] == BluetoothDevice.class) {
                    c.setAccessible(true);
                    return (android.bluetooth.le.ScanResult) c.newInstance(device);
                }
                if (types.length == 4 && types[0] == BluetoothDevice.class) {
                    c.setAccessible(true);
                    return (android.bluetooth.le.ScanResult) c.newInstance(device, null, -50, System.currentTimeMillis());
                }
            }
            throw new IllegalStateException("No matching ScanResult constructor found");
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct ScanResult for test", e);
        }
    }
}
