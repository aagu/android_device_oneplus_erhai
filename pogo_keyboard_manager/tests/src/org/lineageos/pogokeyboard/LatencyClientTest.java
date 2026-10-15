package org.lineageos.pogokeyboard;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.*;

public class LatencyClientTest {

    private static final String TARGET_MAC = "7C:6C:F0:E3:D4:7A";
    private static final String OTHER_MAC = "AA:BB:CC:DD:EE:FF";

    private FakePlatformBluetoothAdapter fakeAdapter;
    private LatencyClient latencyClient;
    private KeyboardIdentity targetIdentity;
    private KeyboardIdentity otherIdentity;

    @Before
    public void setUp() {
        fakeAdapter = new FakePlatformBluetoothAdapter();
        latencyClient = new LatencyClient(fakeAdapter);
        targetIdentity = KeyboardIdentity.parseDriverAddress(TARGET_MAC).orElseThrow();
        otherIdentity = KeyboardIdentity.parseDriverAddress(OTHER_MAC).orElseThrow();
    }

    @Test
    public void testSingleGattClientGuarantee() {
        // Start client 1
        latencyClient.start(targetIdentity, 10, 1L);
        assertTrue(latencyClient.isActive());
        assertEquals(targetIdentity, latencyClient.getCurrentTarget());
        BluetoothGatt firstGatt = fakeAdapter.activeGatt;
        assertNotNull(firstGatt);

        // Start client 2 with different target or generation
        latencyClient.start(otherIdentity, 10, 2L);
        assertTrue("Previous GATT must be closed", fakeAdapter.closedGatts.contains(firstGatt));
        assertTrue(latencyClient.isActive());
        assertEquals(otherIdentity, latencyClient.getCurrentTarget());
    }

    @Test
    public void testConnectedRequestsHighImmediatelyAndDelayedFollowUpOnce() {
        latencyClient.start(targetIdentity, 10, 1L);
        assertNotNull(fakeAdapter.lastGattCallback);

        // Simulate STATE_CONNECTED callback
        fakeAdapter.lastGattCallback.onConnectionStateChange(fakeAdapter.activeGatt, 0, BluetoothProfile.STATE_CONNECTED);

        // 1. HIGH priority requested immediately
        assertEquals(1, fakeAdapter.requestedPriorities.size());
        assertEquals(Integer.valueOf(BluetoothGatt.CONNECTION_PRIORITY_HIGH), fakeAdapter.requestedPriorities.get(0));

        // 2. Exactly one 2000ms delayed task scheduled
        assertEquals(1, fakeAdapter.dispatcher.scheduledTasks.size());
        assertEquals(2000L, fakeAdapter.dispatcher.scheduledTasks.get(0).delayMillis);

        // Trigger the 2000ms delayed task
        fakeAdapter.dispatcher.runScheduledTasks();

        // 3. Follow-up HIGH priority request sent
        assertEquals(2, fakeAdapter.requestedPriorities.size());
        assertEquals(Integer.valueOf(BluetoothGatt.CONNECTION_PRIORITY_HIGH), fakeAdapter.requestedPriorities.get(1));

        // 4. No additional delayed tasks scheduled (at most ONE follow-up)
        assertTrue("No further delayed tasks should be scheduled", fakeAdapter.dispatcher.scheduledTasks.isEmpty());
    }

    @Test
    public void testStopCancelsPendingTasksAndClosesGatt() {
        latencyClient.start(targetIdentity, 10, 1L);
        fakeAdapter.lastGattCallback.onConnectionStateChange(fakeAdapter.activeGatt, 0, BluetoothProfile.STATE_CONNECTED);
        assertEquals(1, fakeAdapter.dispatcher.scheduledTasks.size());

        // Stop
        latencyClient.stop();
        assertFalse(latencyClient.isActive());
        assertNull(latencyClient.getCurrentTarget());
        assertTrue("GATT must be closed", fakeAdapter.gattClosed);
        assertTrue("Pending 2000ms runnable must be removed", fakeAdapter.dispatcher.scheduledTasks.isEmpty());
    }

    @Test
    public void testStaleGattCallbackIgnored() {
        latencyClient.start(targetIdentity, 10, 1L);
        BluetoothGatt oldGatt = fakeAdapter.activeGatt;
        android.bluetooth.BluetoothGattCallback oldCallback = fakeAdapter.lastGattCallback;

        // Switch generation
        latencyClient.start(targetIdentity, 10, 2L);
        fakeAdapter.requestedPriorities.clear();
        fakeAdapter.dispatcher.scheduledTasks.clear();

        // Stale callback on oldGatt with generation 1
        oldCallback.onConnectionStateChange(oldGatt, 0, BluetoothProfile.STATE_CONNECTED);

        // Must be ignored!
        assertTrue(fakeAdapter.requestedPriorities.isEmpty());
        assertTrue(fakeAdapter.dispatcher.scheduledTasks.isEmpty());
    }

    @Test
    public void testDisconnectedStateClosesGattClient() {
        latencyClient.start(targetIdentity, 10, 1L);
        fakeAdapter.lastGattCallback.onConnectionStateChange(fakeAdapter.activeGatt, 0, BluetoothProfile.STATE_DISCONNECTED);

        assertFalse("Client should become inactive after disconnect", latencyClient.isActive());
        assertTrue("GATT must be closed", fakeAdapter.gattClosed);
    }

    @Test
    public void testConnectionUpdateCallbackLoggedSafely() {
        latencyClient.start(targetIdentity, 10, 1L);
        // Invokes onConnectionUpdated if supported
        try {
            java.lang.reflect.Method m = fakeAdapter.lastGattCallback.getClass().getMethod("onConnectionUpdated",
                    BluetoothGatt.class, int.class, int.class, int.class, int.class);
            m.invoke(fakeAdapter.lastGattCallback, fakeAdapter.activeGatt, 12, 0, 400, 0);
        } catch (Exception ignored) {}
    }


    @Test
    public void testRequestConnectionPriorityFailureOnlyLogsDiagnostics() {
        fakeAdapter.requestPriorityResult = false;
        latencyClient.start(targetIdentity, 10, 1L);

        // STATE_CONNECTED with requestPriority failing
        fakeAdapter.lastGattCallback.onConnectionStateChange(fakeAdapter.activeGatt, 0, BluetoothProfile.STATE_CONNECTED);

        // Priority requested despite failure
        assertEquals(1, fakeAdapter.requestedPriorities.size());
        assertEquals(Integer.valueOf(BluetoothGatt.CONNECTION_PRIORITY_HIGH), fakeAdapter.requestedPriorities.get(0));

        // Client must remain active (does not disconnect or close GATT)
        assertTrue("LatencyClient must remain active even if priority request fails", latencyClient.isActive());
        assertFalse("GATT must not be closed when priority request fails", fakeAdapter.gattClosed);

        // Follow-up task is still scheduled
        assertEquals(1, fakeAdapter.dispatcher.scheduledTasks.size());
        fakeAdapter.dispatcher.runScheduledTasks();

        assertEquals(2, fakeAdapter.requestedPriorities.size());
        assertTrue("LatencyClient still active after follow-up", latencyClient.isActive());
        assertFalse("GATT still not closed", fakeAdapter.gattClosed);
    }
}
