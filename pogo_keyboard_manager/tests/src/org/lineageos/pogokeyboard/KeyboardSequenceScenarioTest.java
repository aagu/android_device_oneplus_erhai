package org.lineageos.pogokeyboard;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class KeyboardSequenceScenarioTest {

    private KeyboardIdentity target;
    private KeyboardCoordinator coordinator;

    @Before
    public void setUp() {
        coordinator = new KeyboardCoordinator();
        target = KeyboardIdentity.parseDriverAddress("7c6cf0e3d47a", "OnePlus Pad 2 Pro Keyboard", "SN1").get();
    }

    @Test
    public void testStartupUnknownResyncDetached() {
        // Initial state on boot: UNKNOWN physical state, Bluetooth initially disabled
        KeyboardState state = KeyboardState.initial(0);
        assertEquals(KeyboardState.PhysicalState.UNKNOWN, state.getPhysicalState());
        assertFalse(state.isBtEnabled());

        // 1. Bluetooth turns ON
        KeyboardEvent btOn = KeyboardEvent.builder(KeyboardEvent.Type.BT_STATE_CHANGED)
                .flag(true)
                .userId(0)
                .generation(1L)
                .timestampMs(100L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(btOn, state, 100L);
        assertTrue(r1.getNextState().isBtEnabled());
        // Since physical state is still UNKNOWN, no connect action must be taken!
        assertTrue(r1.getActions().isEmpty());

        // 2. Profile becomes ready
        KeyboardEvent profileReady = KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                .flag(true)
                .userId(0)
                .generation(1L)
                .timestampMs(200L)
                .build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(profileReady, r1.getNextState(), 200L);
        assertTrue(r2.getNextState().isProfileReady());
        assertTrue(r2.getActions().isEmpty());

        // 3. Resync snapshot uevent arrives: DETACHED
        KeyboardEvent detached = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target)
                .userId(0)
                .generation(1L)
                .timestampMs(300L)
                .build();
        KeyboardCoordinator.TransitionResult r3 = coordinator.handle(detached, r2.getNextState(), 300L);
        assertEquals(KeyboardState.PhysicalState.DETACHED, r3.getNextState().getPhysicalState());
        // Target is unbonded, should start directed scan
        assertTrue(r3.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START));
    }

    @Test
    public void testSnapshotOrDuplicateAttachDoesNotClearSuppression() {
        // Target is suppressed
        TargetRecord suppressed = TargetRecord.createInitial(target).withSuppression("USER_SUPPRESSED");
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.ATTACHED)
                .btEnabled(true)
                .profileReady(true)
                .targetRecord(suppressed)
                .build();

        // Repeated UEVENT_ATTACHED (e.g. init snapshot re-issuing attached event)
        KeyboardEvent attachSnapshot = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                .targetIdentity(target)
                .userId(0)
                .generation(1L)
                .timestampMs(500L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(attachSnapshot, state, 500L);

        // Suppression MUST remain!
        assertTrue(r1.getNextState().getTargetRecord().isAutoPairSuppressed());

        // If it now detaches directly without a prior DETACHED state observed before ATTACHED
        KeyboardEvent detach = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                .targetIdentity(target)
                .userId(0)
                .generation(1L)
                .timestampMs(600L)
                .build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(detach, r1.getNextState(), 600L);
        // Suppression still NOT cleared because cycle started at ATTACHED (snapshot), not DETACHED!
        assertTrue(r2.getNextState().getTargetRecord().isAutoPairSuppressed());
        // And therefore no auto-connect scan
        assertFalse(r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SCAN_START));
    }

    @Test
    public void testBondingCancelledByAttachThenLateBondedIgnored() {
        // In progress bonding
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDING)
                .targetRecord(TargetRecord.createInitial(target))
                .build();

        // Keyboard is suddenly attached
        KeyboardEvent attach = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                .targetIdentity(target)
                .userId(0)
                .generation(1L)
                .timestampMs(1000L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(attach, state, 1000L);
        assertEquals(KeyboardState.PhysicalState.ATTACHED, r1.getNextState().getPhysicalState());

        // Late BONDED callback arrives while attached
        KeyboardEvent lateBonded = KeyboardEvent.builder(KeyboardEvent.Type.BOND_STATE_CHANGED)
                .targetIdentity(target)
                .userId(0)
                .generation(1L)
                .intValue(KeyboardState.BondState.BONDED.ordinal())
                .timestampMs(1100L)
                .build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(lateBonded, r1.getNextState(), 1100L);

        // Crucial assertions: attached state must strictly prevent HID_CONNECT and ALLOWED policy
        assertFalse(r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT));
        assertFalse(r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED));
    }

    @Test
    public void testProfileLostAndRestored() {
        KeyboardState state = KeyboardState.initial(0).toBuilder()
                .physicalState(KeyboardState.PhysicalState.DETACHED)
                .btEnabled(true)
                .profileReady(true)
                .bondState(KeyboardState.BondState.BONDED)
                .targetRecord(TargetRecord.createInitial(target).withLastKnownBonded(true))
                .build();

        // Profile lost (e.g. bluetooth service restart or crash)
        KeyboardEvent profileLost = KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                .flag(false)
                .userId(0)
                .generation(1L)
                .timestampMs(2000L)
                .build();
        KeyboardCoordinator.TransitionResult r1 = coordinator.handle(profileLost, state, 2000L);
        assertFalse(r1.getNextState().isProfileReady());

        // Profile restored
        KeyboardEvent profileRestored = KeyboardEvent.builder(KeyboardEvent.Type.PROFILE_AVAILABILITY_CHANGED)
                .flag(true)
                .userId(0)
                .generation(1L)
                .timestampMs(3000L)
                .build();
        KeyboardCoordinator.TransitionResult r2 = coordinator.handle(profileRestored, r1.getNextState(), 3000L);
        assertTrue(r2.getNextState().isProfileReady());

        // Since it's detached and bonded, restoring profile should trigger HID connect!
        assertTrue(r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.HID_CONNECT));
        assertTrue(r2.getActions().stream().anyMatch(a -> a.getType() == KeyboardAction.Type.SET_HID_POLICY
                && a.getHidPolicy() == KeyboardState.HidConnectionPolicy.ALLOWED));
    }
}
