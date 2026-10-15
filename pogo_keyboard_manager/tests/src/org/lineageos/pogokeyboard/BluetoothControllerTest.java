package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class BluetoothControllerTest {

    private static final String TARGET_MAC = "7C:6C:F0:E3:D4:7A";
    private static final String OTHER_MAC = "AA:BB:CC:DD:EE:FF";

    private FakePlatformBluetoothAdapter fakeAdapter;
    private List<KeyboardEvent> emittedEvents;
    private BluetoothController controller;
    private KeyboardIdentity targetIdentity;
    private KeyboardIdentity otherIdentity;

    @Before
    public void setUp() {
        fakeAdapter = new FakePlatformBluetoothAdapter();
        emittedEvents = new ArrayList<>();
        targetIdentity = KeyboardIdentity.parseDriverAddress(TARGET_MAC).orElseThrow();
        otherIdentity = KeyboardIdentity.parseDriverAddress(OTHER_MAC).orElseThrow();
        controller = new BluetoothController(fakeAdapter, emittedEvents::add);
    }

    @Test
    public void testScanFiltersExactMacAddressOnly() {
        // Step 1: Filter scans only by exact target MAC
        KeyboardAction action = KeyboardAction.builder(KeyboardAction.Type.SCAN_START)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .delayMs(5000L)
                .build();

        controller.execute(action);

        assertTrue("Scan should be started", fakeAdapter.scanStarted);
        assertNotNull("Filters should not be null", fakeAdapter.scanFilters);
        assertEquals("Should have exactly 1 filter", 1, fakeAdapter.scanFilters.size());
        ScanFilter filter = fakeAdapter.scanFilters.get(0);
        assertEquals("Filter address must match exact target MAC", TARGET_MAC, filter.getDeviceAddress());

        // Now test SCAN_STOP
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build());
        assertFalse("Scan should be stopped", fakeAdapter.scanStarted);
    }

    @Test
    public void testScanResultDiscardsOtherDevicesAndMatchesTarget() {
        KeyboardAction action = KeyboardAction.builder(KeyboardAction.Type.SCAN_START)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build();
        controller.execute(action);

        // Scan callback receives other device
        fakeAdapter.scanCallback.onScanResult(0, TestObjectFactory.createScanResult(TestObjectFactory.createBluetoothDevice(OTHER_MAC)));
        assertTrue("Scan should still be running after non-matching device", fakeAdapter.scanStarted);
        assertTrue("No events should be emitted for non-matching device", emittedEvents.isEmpty());

        // Scan callback receives target device
        fakeAdapter.scanCallback.onScanResult(0, TestObjectFactory.createScanResult(TestObjectFactory.createBluetoothDevice(TARGET_MAC)));
        assertFalse("Scan should stop upon matching target", fakeAdapter.scanStarted);
        assertEquals(1, emittedEvents.size());
        KeyboardEvent event = emittedEvents.get(0);
        assertEquals(KeyboardEvent.Type.SCAN_RESULT_MATCHED, event.getType());
        assertEquals(targetIdentity, event.getTargetIdentity());
        assertEquals(10, event.getUserId());
        assertEquals(1L, event.getGeneration());
    }

    @Test
    public void testCreateBondReturnHandling() {
        // Step 1: createBond returns true does NOT mean success immediately
        KeyboardAction action = KeyboardAction.builder(KeyboardAction.Type.CREATE_BOND)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build();

        fakeAdapter.createBondResult = true;
        controller.execute(action);

        assertEquals(1, fakeAdapter.createBondCalls.size());
        assertEquals(TARGET_MAC, fakeAdapter.createBondCalls.get(0));
        assertEquals(Integer.valueOf(BluetoothDevice.TRANSPORT_LE), fakeAdapter.createBondTransports.get(0));
        // No BOND_BONDED event should be emitted just because createBond returned true!
        assertTrue("createBond returning true should not emit success event immediately", emittedEvents.isEmpty());

        // createBond returning false means immediate failure
        fakeAdapter.createBondResult = false;
        controller.execute(action);
        assertEquals(1, emittedEvents.size());
        KeyboardEvent failEvent = emittedEvents.get(0);
        assertEquals(KeyboardEvent.Type.BOND_STATE_CHANGED, failEvent.getType());
        assertEquals(KeyboardState.BondState.NONE.ordinal(), failEvent.getIntValue());
    }

    @Test
    public void testCreateBondDoesNotDuplicateIfBondingOrBonded() {
        // If already BONDING, do not call createBond again
        fakeAdapter.bondStates.put(TARGET_MAC, BluetoothDevice.BOND_BONDING);
        KeyboardAction action = KeyboardAction.builder(KeyboardAction.Type.CREATE_BOND)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build();
        controller.execute(action);
        assertTrue("Should not call createBond if already BONDING", fakeAdapter.createBondCalls.isEmpty());

        // If already BONDED, emit BOND_STATE_CHANGED(BONDED) directly without calling createBond
        fakeAdapter.bondStates.put(TARGET_MAC, BluetoothDevice.BOND_BONDED);
        controller.execute(action);
        assertTrue("Should not call createBond if already BONDED", fakeAdapter.createBondCalls.isEmpty());
        assertEquals(1, emittedEvents.size());
        assertEquals(KeyboardState.BondState.BONDED.ordinal(), emittedEvents.get(0).getIntValue());
    }

    @Test
    public void testCancelBondCancelsIfBonding() {
        fakeAdapter.bondStates.put(TARGET_MAC, BluetoothDevice.BOND_BONDING);
        KeyboardAction action = KeyboardAction.builder(KeyboardAction.Type.CANCEL_BOND)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build();
        controller.execute(action);
        assertEquals(1, fakeAdapter.cancelBondCalls.size());
        assertEquals(TARGET_MAC, fakeAdapter.cancelBondCalls.get(0));
    }

    @Test
    public void testPairingRequestDoesNotSwallowDialogs() {
        // ACTION_PAIRING_REQUEST received
        Intent intent = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, TestObjectFactory.createBluetoothDevice(TARGET_MAC));
        intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, 2);

        fakeAdapter.registeredReceivers.get(0).onReceive(null, intent);
        // Does not alter flow, does not swallow confirmation
        assertTrue(emittedEvents.isEmpty());
    }

    @Test
    public void testBondBroadcastFromOtherDeviceOrUnparseableIsDropped() {
        // Set active target, user, generation via an action
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(3)
                .hidPolicy(KeyboardState.HidConnectionPolicy.ALLOWED)
                .build());
        emittedEvents.clear();

        // 1. Unparseable broadcast is dropped
        Intent unparseableIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        unparseableIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, TestObjectFactory.createBluetoothDevice("00:00:00:00:00:00"));
        fakeAdapter.registeredReceivers.get(0).onReceive(null, unparseableIntent);
        assertTrue("Unparseable broadcast must be dropped", emittedEvents.isEmpty());

        // 2. Broadcast from foreign device is dropped
        Intent otherIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        otherIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, TestObjectFactory.createBluetoothDevice(OTHER_MAC));
        otherIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED);
        fakeAdapter.registeredReceivers.get(0).onReceive(null, otherIntent);
        assertTrue("Foreign device broadcast must be dropped", emittedEvents.isEmpty());

        // 3. Target device broadcast passes with tagged userId and generation
        Intent targetIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        targetIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, TestObjectFactory.createBluetoothDevice(TARGET_MAC));
        targetIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED);
        fakeAdapter.registeredReceivers.get(0).onReceive(null, targetIntent);
        assertEquals(1, emittedEvents.size());
        KeyboardEvent event = emittedEvents.get(0);
        assertEquals(targetIdentity, event.getTargetIdentity());
        assertEquals(10, event.getUserId());
        assertEquals(3L, event.getGeneration());
        assertEquals(KeyboardState.BondState.BONDED.ordinal(), event.getIntValue());
    }

    @Test
    public void testHidProfileProxyLifecycleAndPolicyUpdates() {
        // Connect profile proxy
        BluetoothHidHost mockProxy = TestObjectFactory.createBluetoothHidHost();
        fakeAdapter.profileListener.onServiceConnected(BluetoothProfile.HID_HOST, mockProxy);

        assertEquals(1, emittedEvents.size());
        KeyboardEvent readyEvent = emittedEvents.get(0);
        assertEquals(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED, readyEvent.getType());
        assertTrue(readyEvent.isFlag());

        // Set policy ALLOWED
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                .targetIdentity(targetIdentity)
                .hidPolicy(KeyboardState.HidConnectionPolicy.ALLOWED)
                .userId(10)
                .generation(1)
                .build());
        assertEquals(Integer.valueOf(BluetoothProfile.CONNECTION_POLICY_ALLOWED), fakeAdapter.hidPolicies.get(TARGET_MAC));

        // Connect HID
        fakeAdapter.hidConnectionStates.put(TARGET_MAC, BluetoothProfile.STATE_DISCONNECTED);
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.HID_CONNECT)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build());
        assertEquals(1, fakeAdapter.hidConnectCalls.size());
        assertEquals(TARGET_MAC, fakeAdapter.hidConnectCalls.get(0));

        // Duplicate connect when already CONNECTED is skipped
        fakeAdapter.hidConnectionStates.put(TARGET_MAC, BluetoothProfile.STATE_CONNECTED);
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.HID_CONNECT)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build());
        assertEquals("Should not call connect again if already connected", 1, fakeAdapter.hidConnectCalls.size());

        // Attachment: Set policy FORBIDDEN and HID_DISCONNECT
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                .targetIdentity(targetIdentity)
                .hidPolicy(KeyboardState.HidConnectionPolicy.FORBIDDEN)
                .userId(10)
                .generation(1)
                .build());
        assertEquals(Integer.valueOf(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN), fakeAdapter.hidPolicies.get(TARGET_MAC));

        controller.execute(KeyboardAction.builder(KeyboardAction.Type.HID_DISCONNECT)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build());
        assertEquals(1, fakeAdapter.hidDisconnectCalls.size());
        assertEquals(TARGET_MAC, fakeAdapter.hidDisconnectCalls.get(0));
    }

    @Test
    public void testNullProxySafety() {
        // If proxy is disconnected/null
        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                .targetIdentity(targetIdentity)
                .hidPolicy(KeyboardState.HidConnectionPolicy.ALLOWED)
                .userId(10)
                .generation(1)
                .build());
        // Emits profile false event without throwing NullPointerException
        assertEquals(1, emittedEvents.size());
        assertEquals(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED, emittedEvents.get(0).getType());
        assertFalse(emittedEvents.get(0).isFlag());
    }

    @Test
    public void testCloseCleansUpAllResources() {
        BluetoothHidHost mockProxy = TestObjectFactory.createBluetoothHidHost();
        fakeAdapter.profileListener.onServiceConnected(BluetoothProfile.HID_HOST, mockProxy);

        controller.execute(KeyboardAction.builder(KeyboardAction.Type.SCAN_START)
                .targetIdentity(targetIdentity)
                .userId(10)
                .generation(1)
                .build());
        assertTrue(fakeAdapter.scanStarted);
        assertFalse(fakeAdapter.registeredReceivers.isEmpty());

        controller.close();

        assertFalse("Scan must be stopped on close", fakeAdapter.scanStarted);
        assertTrue("Receivers must be unregistered on close", fakeAdapter.registeredReceivers.isEmpty());
        assertTrue("Profile proxy must be closed on close", fakeAdapter.profileProxyClosed);
    }

    @Test
    public void testProfileProxyConnectedAfterCloseImmediatelyClosesProxy() {
        // Controller is closed BEFORE profile proxy connects
        controller.close();
        emittedEvents.clear();
        fakeAdapter.profileProxyClosed = false;

        BluetoothHidHost mockProxy = TestObjectFactory.createBluetoothHidHost();
        fakeAdapter.profileListener.onServiceConnected(BluetoothProfile.HID_HOST, mockProxy);

        assertTrue("Late-arriving profile proxy must be closed immediately", fakeAdapter.profileProxyClosed);
        assertTrue("No PROFILE_AVAILABILITY_CHANGED events should be emitted after close", emittedEvents.isEmpty());
    }

    @Test
    public void testInitialBtEnabledEmitsBtStateChanged() {
        FakePlatformBluetoothAdapter btOnAdapter = new FakePlatformBluetoothAdapter();
        btOnAdapter.btEnabled = true;
        List<KeyboardEvent> events = new ArrayList<>();
        BluetoothController c = new BluetoothController(btOnAdapter, events::add);

        assertEquals(1, events.size());
        assertEquals(KeyboardEvent.Type.BT_STATE_CHANGED, events.get(0).getType());
        assertTrue(events.get(0).isFlag());
        c.close();
    }

    @Test
    public void testActionStateChangedOnAndOff() {
        android.content.Intent onIntent = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        onIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.STATE_ON);

        fakeAdapter.registeredReceivers.get(0).onReceive(null, onIntent);

        assertFalse(emittedEvents.isEmpty());
        KeyboardEvent lastEvent = emittedEvents.get(emittedEvents.size() - 1);
        assertEquals(KeyboardEvent.Type.BT_STATE_CHANGED, lastEvent.getType());
        assertTrue(lastEvent.isFlag());

        android.content.Intent offIntent = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        offIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.STATE_OFF);

        fakeAdapter.registeredReceivers.get(0).onReceive(null, offIntent);

        lastEvent = emittedEvents.get(emittedEvents.size() - 1);
        assertEquals(KeyboardEvent.Type.BT_STATE_CHANGED, lastEvent.getType());
        assertFalse(lastEvent.isFlag());
    }

    @Test
    public void testSetActiveSessionPropagatesToEvents() {
        controller.setActiveSession(targetIdentity, 10, 42L);
        android.content.Intent onIntent = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        onIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.STATE_ON);

        fakeAdapter.registeredReceivers.get(0).onReceive(null, onIntent);

        KeyboardEvent event = emittedEvents.get(emittedEvents.size() - 1);
        assertEquals(KeyboardEvent.Type.BT_STATE_CHANGED, event.getType());
        assertEquals(10, event.getUserId());
        assertEquals(42L, event.getGeneration());
        assertEquals(targetIdentity, event.getTargetIdentity());
    }

    @Test
    public void testProfileProxyConnectUpdatesStateAndEmitsEvent() {
        controller.setActiveSession(targetIdentity, 5, 99L);
        controller.ensureProfileProxy();

        android.bluetooth.BluetoothProfile fakeProxy = TestObjectFactory.createBluetoothHidHost();
        fakeAdapter.profileListener.onServiceConnected(android.bluetooth.BluetoothProfile.HID_HOST, fakeProxy);

        assertTrue("Profile proxy must be ready after onServiceConnected", controller.isProfileProxyReady());
        KeyboardEvent event = emittedEvents.get(emittedEvents.size() - 1);
        assertEquals(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED, event.getType());
        assertTrue(event.isFlag());
        assertEquals(5, event.getUserId());
        assertEquals(99L, event.getGeneration());
        assertEquals(targetIdentity, event.getTargetIdentity());
    }
}
