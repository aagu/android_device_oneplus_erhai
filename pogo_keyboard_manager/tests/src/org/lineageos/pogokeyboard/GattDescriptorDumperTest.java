package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class GattDescriptorDumperTest {

    private static final String TAG = "GattDescDumper";
    private static final String TARGET_MAC = "7C:6C:F0:E3:D4:7A";
    private static final UUID HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID REPORT_MAP_CHAR_UUID = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb");

    @org.junit.Ignore("Manual diagnostic tool to dump HOGP Report Map")
    @Test
    public void dumpReportMapDescriptor() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        if (bm == null) {
            Log.e(TAG, "BluetoothManager is null");
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "BluetoothAdapter is null or disabled");
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(TARGET_MAC);
        Log.i(TAG, "Target device: " + device.getAddress() + " (bondState=" + device.getBondState() + ")");

        CountDownLatch latch = new CountDownLatch(1);
        final byte[][] captured = new byte[1][];
        final AtomicBoolean connected = new AtomicBoolean(false);

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.i(TAG, "onConnectionStateChange: status=" + status + ", newState=" + newState);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Connection failed with status " + status);
                    connected.set(false);
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connected.set(true);
                    Log.i(TAG, "Connected to GATT! Requesting MTU 517...");
                    gatt.requestMtu(517);
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT");
                    connected.set(false);
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                Log.i(TAG, "onMtuChanged: mtu=" + mtu + ", status=" + status);
                gatt.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Log.i(TAG, "onServicesDiscovered: status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "discoverServices failed with status " + status);
                    return;
                }

                BluetoothGattService hidService = gatt.getService(HID_SERVICE_UUID);
                if (hidService == null) {
                    Log.e(TAG, "HID Service 0x1812 not found!");
                    for (BluetoothGattService s : gatt.getServices()) {
                        Log.i(TAG, "Discovered service: " + s.getUuid());
                    }
                    return;
                }

                BluetoothGattCharacteristic reportMapChar = hidService.getCharacteristic(REPORT_MAP_CHAR_UUID);
                if (reportMapChar == null) {
                    Log.e(TAG, "Report Map characteristic 0x2A4B not found in HID Service!");
                    for (BluetoothGattCharacteristic c : hidService.getCharacteristics()) {
                        Log.i(TAG, "Discovered char: " + c.getUuid());
                    }
                    return;
                }

                Log.i(TAG, "Found Report Map characteristic! Initiating readCharacteristic...");
                boolean readOk = gatt.readCharacteristic(reportMapChar);
                Log.i(TAG, "readCharacteristic returned " + readOk);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                byte[] val = characteristic.getValue();
                Log.i(TAG, "onCharacteristicRead (legacy callback): status=" + status + ", len=" + (val != null ? val.length : 0));
                handleDescriptorData(gatt, val, status);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
                Log.i(TAG, "onCharacteristicRead (API 33+ callback): status=" + status + ", len=" + (value != null ? value.length : 0));
                handleDescriptorData(gatt, value, status);
            }

            private void handleDescriptorData(BluetoothGatt gatt, byte[] value, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Characteristic read failed with status " + status);
                    return;
                }
                if (value == null || value.length == 0) {
                    Log.w(TAG, "Characteristic read returned empty data");
                    return;
                }

                captured[0] = value;
                StringBuilder sb = new StringBuilder();
                for (byte b : value) {
                    sb.append(String.format("%02x", b));
                }
                String hex = sb.toString();

                Log.i(TAG, "=== RAW HOGP DESCRIPTOR CAPTURED ===");
                Log.i(TAG, "LENGTH: " + value.length + " bytes");
                Log.i(TAG, "HEX: " + hex);
                Log.i(TAG, "=== END HOGP DESCRIPTOR ===");

                try (FileOutputStream fos = new FileOutputStream("/data/local/tmp/hogp_desc.hex")) {
                    fos.write(hex.getBytes());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to write /data/local/tmp/hogp_desc.hex", e);
                }

                try (FileOutputStream fos = new FileOutputStream("/data/local/tmp/hogp_desc.bin")) {
                    fos.write(value);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to write /data/local/tmp/hogp_desc.bin", e);
                }

                gatt.disconnect();
                gatt.close();
                latch.countDown();
            }
        };

        // Start scanning for the keyboard
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                Log.i(TAG, "onScanResult: " + result.getDevice().getAddress() + ", rssi=" + result.getRssi());
                if (!connected.get()) {
                    Log.i(TAG, "Connecting to scanned device " + result.getDevice().getAddress());
                    result.getDevice().connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                }
            }
        };

        if (scanner != null) {
            ScanFilter filter = new ScanFilter.Builder()
                    .setDeviceAddress(TARGET_MAC)
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            Log.i(TAG, "Starting low-latency scan for " + TARGET_MAC + "...");
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        }

        // Also initiate direct/autoconnect GATT in parallel
        Log.i(TAG, "Connecting autoConnect GATT to " + TARGET_MAC + "...");
        BluetoothGatt autoGatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE);

        Log.i(TAG, "Waiting up to 60 seconds for detachment and descriptor capture...");
        boolean completed = latch.await(60, TimeUnit.SECONDS);

        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }
        if (autoGatt != null) {
            autoGatt.close();
        }

        Log.i(TAG, "Dumper test finished, completed=" + completed + ", captured="
                + (captured[0] != null ? captured[0].length : 0) + " bytes");
    }
}
