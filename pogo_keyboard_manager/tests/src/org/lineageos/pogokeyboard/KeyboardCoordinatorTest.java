package org.lineageos.pogokeyboard;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class KeyboardCoordinatorTest {

    private KeyboardIdentity target1;
    private KeyboardIdentity target2;
    private KeyboardCoordinator coordinator;

    @Before
    public void setUp() {
        coordinator = new KeyboardCoordinator();
        target1 = KeyboardIdentity.parseDriverAddress("7c6cf0e3d47a", "OnePlus Pad 2 Pro Keyboard", "SN1").get();
        target2 = KeyboardIdentity.parseDriverAddress("001122334455", "Different Keyboard", "SN2").get();
    }

    @Test
    public void testAttachCancelsScanAndSetsForbidden() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .isScanning(true)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(1000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(event, state, 1000L);
        KeyboardState nextState = result.getNextState();
        List<KeyboardAction> actions = result.getActions();

        assertEquals(KeyboardState.PhysicalState.ATTACHED, nextState.getPhysicalState());
        assertFalse(nextState.isScanning());
        assertEquals(KeyboardState.HidConnectionPolicy.FORBIDDEN, nextState.getHidConnectionPolicy());

        // Actions should include SCAN_STOP, CANCEL_RETRY, SET_HID_POLICY(FORBIDDEN), HID_DISCONNECT
        boolean hasScanStop = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_STOP);
        boolean hasSetForbidden = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.FORBIDDEN);
        boolean hasDisconnect = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_DISCONNECT);
        boolean hasCancelRetry = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.CANCEL_RETRY);

        assertTrue(hasScanStop);
        assertTrue(hasSetForbidden);
        assertTrue(hasDisconnect);
        assertTrue(hasCancelRetry);
    }

    @Test
    public void testAttachPrioritizesOverLateWirelessCallbacks() {
        // State is ATTACHED
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.ATTACHED)
                .btEnabled(true)
                .profileReady(true)
                .targetRecord(TargetRecord.createInitial(target1))
                .currentGeneration(1L)
                .build();

        // Late BOND_STATE_CHANGED (BONDED) arrives while ATTACHED
        KeyboardEvent lateBondEvent = KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .intValue(KeyboardState.BondState.BONDED.ordinal())
                .timestampMs(2000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(lateBondEvent, state, 2000L);
        // While attached, should NOT initiate HID connect or set ALLOWED!
        boolean hasConnect = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT);
        boolean hasAllowed = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED);

        assertFalse("Attached keyboard must never connect HID", hasConnect);
        assertFalse("Attached keyboard must never set ALLOWED policy", hasAllowed);
    }

    @Test
    public void testOldGenerationIgnored() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .currentGeneration(5L)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        // Callback from old generation 4
        KeyboardEvent oldEvent = KeyboardEvent.builder(KeyboardEvent.Type.HID_STATE_CHANGED)
                .targetIdentity(target1)
                .userId(0)
                .generation(4L)
                .intValue(KeyboardState.HidState.CONNECTED.ordinal())
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(oldEvent, state, 1000L);
        assertEquals(state, result.getNextState());
        assertTrue(result.getActions().isEmpty());
    }

    @Test
    public void testOtherDeviceEventIgnored() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent otherDeviceEvent = KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                .targetIdentity(target2)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .intValue(KeyboardState.BondState.BONDED.ordinal())
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(otherDeviceEvent, state, 1000L);
        assertEquals(state, result.getNextState());
        assertTrue(result.getActions().isEmpty());
    }

    @Test
    public void testDetachedBondedConnectsHidAndSetsAllowed() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.ATTACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target1).withLastKnownBonded(true))
                .build();

        KeyboardEvent detachEvent = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(1000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(detachEvent, state, 1000L);
        KeyboardState next = result.getNextState();
        List<KeyboardAction> actions = result.getActions();

        assertEquals(KeyboardState.PhysicalState.DETACHED, next.getPhysicalState());
        assertEquals(KeyboardState.HidConnectionPolicy.ALLOWED, next.getHidConnectionPolicy());

        boolean hasSetAllowed = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED);
        boolean hasConnect = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT);

        assertTrue(hasSetAllowed);
        assertTrue(hasConnect);
    }

    @Test
    public void testDetachedUnbondedStartsScanWith5000msBudget() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.ATTACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.NONE)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent detachEvent = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(1000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(detachEvent, state, 1000L);
        KeyboardState next = result.getNextState();
        List<KeyboardAction> actions = result.getActions();

        assertEquals(KeyboardState.PhysicalState.DETACHED, next.getPhysicalState());
        assertTrue(next.isScanning());

        boolean hasScanStart = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START
                && a.getDelayMs() == 5000L);
        assertTrue(hasScanStart);
    }

    @Test
    public void testScanMatchedCreatesBond() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .isScanning(true)
                .bondState(KeyboardState.BondState.NONE)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent matchEvent = KeyboardEvent.builder(KeyboardEvent.Type.SCAN_RESULT_MATCHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(2000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(matchEvent, state, 2000L);
        KeyboardState next = result.getNextState();
        List<KeyboardAction> actions = result.getActions();

        assertFalse(next.isScanning());
        boolean hasScanStop = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_STOP);
        boolean hasCreateBond = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.CREATE_BOND);

        assertTrue(hasScanStop);
        assertTrue(hasCreateBond);
    }

    @Test
    public void testScanTimeoutSchedulesBackoffRetryMax3Times() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .isScanning(true)
                .retryCount(0)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        // 1st retry: backoff 1000ms
        KeyboardEvent timeout1 = KeyboardEvent.builder(KeyboardEvent.Type.SCAN_TIMEOUT)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(timeout1, state, 6000L);
        assertEquals(1, r1.getNextState().getRetryCount());
        assertFalse(r1.getNextState().isScanning());
        boolean retry1 = r1.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCHEDULE_RETRY
                && a.getDelayMs() == 1000L);
        assertTrue(retry1);

        // 2nd retry: backoff 2000ms
        KeyboardState state1 = r1.getNextState().toBuilder().isScanning(true).build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(timeout1, state1, 8000L);
        assertEquals(2, r2.getNextState().getRetryCount());
        boolean retry2 = r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCHEDULE_RETRY
                && a.getDelayMs() == 2000L);
        assertTrue(retry2);

        // 3rd retry: backoff 4000ms
        KeyboardState state2 = r2.getNextState().toBuilder().isScanning(true).build();
        KeyboardCoordinator.TransitionResult r3 = coordinator.handle(timeout1, state2, 11000L);
        assertEquals(3, r3.getNextState().getRetryCount());
        boolean retry3 = r3.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCHEDULE_RETRY
                && a.getDelayMs() == 4000L);
        assertTrue(retry3);

        // 4th retry: exceeded max 3 retries, no more SCHEDULE_RETRY
        KeyboardState state3 = r3.getNextState().toBuilder().isScanning(true).build();
        KeyboardCoordinator.TransitionResult r4 = coordinator.handle(timeout1, state3, 16000L);
        boolean retry4 = r4.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCHEDULE_RETRY);
        assertFalse("Must not schedule retry beyond max 3 retries", retry4);
    }

    @Test
    public void testUserCancelledOrForgottenSuppressesAutoPair() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target1).withLastKnownBonded(true))
                .build();

        // User unpairs/forgets target
        KeyboardEvent forgetEvent = KeyboardEvent.builder(KeyboardEvent.Type.TARGET_FORGOTTEN)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(3000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(forgetEvent, state, 3000L);
        KeyboardState next = result.getNextState();
        List<KeyboardAction> actions = result.getActions();

        assertTrue(next.getTargetRecord().isAutoPairSuppressed());
        assertEquals("USER_SUPPRESSED", next.getTargetRecord().getSuppressionReason());
        assertFalse(next.getTargetRecord().isLastKnownBonded());

        // Must persist suppression record
        boolean hasPersist = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.PERSIST_RECORD
                && a.getTargetRecord().isAutoPairSuppressed());
        assertTrue(hasPersist);

        // Must stop any active scans or connections
        boolean hasScan = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        boolean hasConnect = actions.stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT);
        assertFalse(hasScan);
        assertFalse(hasConnect);
    }

    @Test
    public void testSuppressedDeviceDoesNotAutoConnectWhenDetached() {
        TargetRecord suppressedRecord = TargetRecord.createInitial(target1).withSuppression("USER_SUPPRESSED");
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.ATTACHED)
                .btEnabled(true)
                .profileReady(true)
                .targetRecord(suppressedRecord)
                .build();

        KeyboardEvent detachEvent = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(5000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(detachEvent, state, 5000L);
        // Should NOT start scan or HID connect
        boolean hasScan = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        boolean hasConnect = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT);
        assertFalse(hasScan);
        assertFalse(hasConnect);
    }

    @Test
    public void testExplicitUserRetryClearsSuppression() {
        TargetRecord suppressedRecord = TargetRecord.createInitial(target1).withSuppression("USER_SUPPRESSED");
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .targetRecord(suppressedRecord)
                .build();

        KeyboardEvent retryEvent = KeyboardEvent.builder(KeyboardEvent.Type.RETRY_REQUESTED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(6000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(retryEvent, state, 6000L);
        assertFalse(result.getNextState().getTargetRecord().isAutoPairSuppressed());

        boolean hasPersist = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.PERSIST_RECORD
                && !a.getTargetRecord().isAutoPairSuppressed());
        assertTrue(hasPersist);

        // Since it's detached, clearing suppression triggers discovery/connect
        boolean hasScan = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        assertTrue(hasScan);
    }

    @Test
    public void testCycleDetachedAttachedDetachedClearsSuppression() {
        TargetRecord suppressedRecord = TargetRecord.createInitial(target1).withSuppression("USER_SUPPRESSED");
        // Start in DETACHED and suppressed
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .targetRecord(suppressedRecord)
                .build();

        // 1. ATTACHED
        KeyboardEvent attachEvent = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(7000L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(attachEvent, state, 7000L);
        assertEquals(KeyboardState.PhysicalState.ATTACHED, r1.getNextState().getPhysicalState());

        // 2. DETACHED again -> cycle completed! Suppression must be cleared!
        KeyboardEvent detachEvent = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target1)
                .userId(0)
                .generation(1L)
                .timestampMs(8000L)
                .build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(detachEvent, r1.getNextState(), 8000L);
        assertFalse(r2.getNextState().getTargetRecord().isAutoPairSuppressed());

        boolean hasScan = r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        assertTrue(hasScan);
    }

    @Test
    public void testUserSwitchInvalidatesGenerationAndNotifies() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .currentGeneration(1L)
                .currentUserId(0)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent userSwitch = KeyboardEvent.builder(KeyboardEvent.Type.USER_SWITCHED)
                .intValue(10) // new user 10
                .timestampMs(9000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(userSwitch, state, 9000L);
        assertEquals(10, result.getNextState().getCurrentUserId());
        assertEquals(2L, result.getNextState().getCurrentGeneration()); // Generation incremented

        boolean hasUserNotify = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.NOTIFY_USER_SWITCH
                && a.getUserId() == 10);
        assertTrue(hasUserNotify);
    }

    @Test
    public void testTargetSwitchCancelsPendingTasks() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .isScanning(true)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        // New keyboard attached
        KeyboardEvent newTargetAttach = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                .targetIdentity(target2)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .timestampMs(10000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(newTargetAttach, state, 10000L);
        KeyboardState next = result.getNextState();
        assertEquals(target2, next.getTargetRecord().getIdentity());
        assertFalse(next.isScanning());

        // Must cancel old tasks
        boolean hasScanStop = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_STOP);
        boolean hasCancelRetry = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.CANCEL_RETRY);
        assertTrue(hasScanStop);
        assertTrue(hasCancelRetry);
    }

    @Test
    public void testHidConnectedStartsGattLatency() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target1))
                .hidState(KeyboardState.HidState.CONNECTING)
                .build();

        KeyboardEvent connectedEvent = KeyboardEvent.builder(KeyboardEvent.Type.HID_STATE_CHANGED)
                .targetIdentity(target1)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .intValue(KeyboardState.HidState.CONNECTED.ordinal())
                .timestampMs(11000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(connectedEvent, state, 11000L);
        assertTrue(result.getNextState().isGattLatencyActive());
        boolean hasStartGatt = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.START_GATT_LATENCY);
        assertTrue(hasStartGatt);
    }

    @Test
    public void testHidDisconnectedStopsGattLatency() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target1))
                .hidState(KeyboardState.HidState.CONNECTED)
                .gattLatencyActive(true)
                .build();

        KeyboardEvent disconnectedEvent = KeyboardEvent.builder(KeyboardEvent.Type.HID_STATE_CHANGED)
                .targetIdentity(target1)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .intValue(KeyboardState.HidState.DISCONNECTED.ordinal())
                .timestampMs(12000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(disconnectedEvent, state, 12000L);
        assertFalse(result.getNextState().isGattLatencyActive());
        boolean hasStopGatt = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.STOP_GATT_LATENCY);
        assertTrue(hasStopGatt);
    }

    @Test
    public void testBluetoothTurnedOffCleansUpTasks() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .isScanning(true)
                .gattLatencyActive(true)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent btOff = KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                .flag(false)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .timestampMs(13000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(btOff, state, 13000L);
        assertFalse(result.getNextState().isBtEnabled());
        assertFalse(result.getNextState().isScanning());
        assertFalse(result.getNextState().isGattLatencyActive());

        boolean hasScanStop = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_STOP);
        boolean hasStopGatt = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.STOP_GATT_LATENCY);
        boolean hasCancelRetry = result.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.CANCEL_RETRY);

        assertTrue(hasScanStop);
        assertTrue(hasStopGatt);
        assertTrue(hasCancelRetry);
    }

    @Test
    public void testBtStateChangedOnWhileDetachedAndProfileReadyTriggersConnection() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(false)
                .profileReady(true)
                .bondState(KeyboardState.BondState.NONE)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent btOn = KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                .flag(true)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .timestampMs(14000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(btOn, state, 14000L);
        assertTrue(result.getNextState().isBtEnabled());
        assertTrue(result.getNextState().isScanning());

        boolean hasScanStart = result.getActions().stream()
                .anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        assertTrue("Late BT_STATE_CHANGED(true) while detached must trigger SCAN_START", hasScanStart);
    }

    @Test
    public void testProfileAvailabilityChangedOnWhileDetachedAndBtEnabledTriggersScan() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(false)
                .bondState(KeyboardState.BondState.NONE)
                .targetRecord(TargetRecord.createInitial(target1))
                .build();

        KeyboardEvent profileReady = KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                .flag(true)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .timestampMs(15000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(profileReady, state, 15000L);
        assertTrue(result.getNextState().isProfileReady());
        assertTrue(result.getNextState().isScanning());

        boolean hasScanStart = result.getActions().stream()
                .anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START);
        assertTrue("Late PROFILE_AVAILABILITY_CHANGED(true) while detached must trigger SCAN_START", hasScanStart);
    }

    @Test
    public void testProfileAvailabilityChangedOnWhileDetachedAndBondedTriggersHidConnect() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(false)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target1).withLastKnownBonded(true))
                .build();

        KeyboardEvent profileReady = KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                .flag(true)
                .userId(0)
                .generation(state.getCurrentGeneration())
                .timestampMs(16000L)
                .build();

        KeyboardCoordinator.TransitionResult result = coordinator.handle(profileReady, state, 16000L);
        assertTrue(result.getNextState().isProfileReady());

        boolean hasSetHidPolicy = result.getActions().stream()
                .anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                        && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED);
        boolean hasHidConnect = result.getActions().stream()
                .anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT);
        assertTrue("Late PROFILE_AVAILABILITY_CHANGED(true) for bonded device must set policy ALLOWED", hasSetHidPolicy);
        assertTrue("Late PROFILE_AVAILABILITY_CHANGED(true) for bonded device must trigger HID_CONNECT", hasHidConnect);
    }
}
