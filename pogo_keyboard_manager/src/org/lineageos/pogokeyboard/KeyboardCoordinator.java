package org.lineageos.pogokeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure logic coordinator for Pogo / BLE keyboard integration.
 * Evaluates inputs into next immutable state and a list of actions to perform.
 * No framework, Binder, or platform GATT dependencies.
 */
public final class KeyboardCoordinator {

    public static final long SCAN_BUDGET_MS = 5000L;
    public static final int MAX_RETRY_COUNT = 3;
    public static final long[] RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

    // Tracks internal cycle state across calls (whether a transition from DETACHED to ATTACHED has been observed)
    private boolean detachedObservedForTarget = false;

    public static final class TransitionResult {
        private final KeyboardState nextState;
        private final List<KeyboardAction> actions;

        public TransitionResult(KeyboardState nextState, List<KeyboardAction> actions) {
            this.nextState = Objects.requireNonNull(nextState, "nextState must not be null");
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }

        public KeyboardState getNextState() {
            return nextState;
        }

        public List<KeyboardAction> getActions() {
            return actions;
        }
    }

    public TransitionResult handle(KeyboardEvent event, KeyboardState state, long nowMs) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(state, "state must not be null");

        List<KeyboardAction> actions = new ArrayList<>();
        KeyboardState.Builder next = state.toBuilder();

        // 1. Generation and User checks (except for USER_SWITCHED or initial attachment)
        if (event.getType() != KeyboardEvent.Type.USER_SWITCHED) {
            if (event.getGeneration() != 0 && event.getGeneration() != state.getCurrentGeneration()) {
                // Outdated generation, drop
                return new TransitionResult(state, Collections.emptyList());
            }
            if (event.getUserId() != state.getCurrentUserId()) {
                // Outdated user, drop
                return new TransitionResult(state, Collections.emptyList());
            }
        }

        // 2. Target check: if event has a targetIdentity and current state has a targetRecord, check match
        if (event.getTargetIdentity() != null && state.getTargetRecord() != null) {
            if (event.getType() != KeyboardEvent.Type.UEVENT_ATTACHED
                    && !state.getTargetRecord().getIdentity().equals(event.getTargetIdentity())) {
                // Event belongs to another device, ignore
                return new TransitionResult(state, Collections.emptyList());
            }
        }

        switch (event.getType()) {
            case USER_SWITCHED: {
                int newUserId = event.getIntValue();
                long newGeneration = state.getCurrentGeneration() + 1;
                next.currentUserId(newUserId);
                next.currentGeneration(newGeneration);
                next.isScanning(false);
                next.gattLatencyActive(false);

                // Stop pending tasks on old user executor
                if (state.isScanning()) {
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                }
                if (state.isGattLatencyActive()) {
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.STOP_GATT_LATENCY)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                }
                actions.add(KeyboardAction.builder(KeyboardAction.Type.CANCEL_RETRY)
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());

                actions.add(KeyboardAction.builder(KeyboardAction.Type.NOTIFY_USER_SWITCH)
                        .userId(newUserId)
                        .generation(newGeneration)
                        .build());
                break;
            }

            case BT_STATE_CHANGED: {
                boolean btEnabled = event.isFlag();
                next.btEnabled(btEnabled);
                if (!btEnabled) {
                    next.profileReady(false);
                    next.isScanning(false);
                    next.gattLatencyActive(false);
                    next.retryCount(0);

                    actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.CANCEL_RETRY)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                    if (state.isGattLatencyActive()) {
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.STOP_GATT_LATENCY)
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .build());
                    }
                } else {
                    if (next.build().isProfileReady() && next.build().getPhysicalState() == KeyboardState.PhysicalState.DETACHED) {
                        triggerDetachedConnection(next, actions, nowMs);
                    }
                }
                break;
            }

            case PROFILE_AVAILABILITY_CHANGED: {
                boolean profileReady = event.isFlag();
                next.profileReady(profileReady);
                if (profileReady && next.build().isBtEnabled() && next.build().getPhysicalState() == KeyboardState.PhysicalState.DETACHED) {
                    triggerDetachedConnection(next, actions, nowMs);
                }
                break;
            }

            case UEVENT_ATTACHED: {
                KeyboardIdentity target = event.getTargetIdentity();
                if (target == null && state.getTargetRecord() != null) {
                    target = state.getTargetRecord().getIdentity();
                }
                if (target == null) {
                    break;
                }

                // If switching target or new target
                TargetRecord record = state.getTargetRecord();
                if (record == null || !record.getIdentity().equals(target)) {
                    record = TargetRecord.createInitial(target);
                    detachedObservedForTarget = false;
                } else {
                    if (state.getPhysicalState() == KeyboardState.PhysicalState.DETACHED) {
                        detachedObservedForTarget = true;
                    }
                }

                next.physicalState(KeyboardState.PhysicalState.ATTACHED);
                next.targetRecord(record);
                next.isScanning(false);
                next.retryCount(0);
                next.hidConnectionPolicy(KeyboardState.HidConnectionPolicy.FORBIDDEN);

                // Stop scanning and retries
                actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                        .targetIdentity(target)
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());
                actions.add(KeyboardAction.builder(KeyboardAction.Type.CANCEL_RETRY)
                        .targetIdentity(target)
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());

                // Stop latency GATT if active
                if (state.isGattLatencyActive()) {
                    next.gattLatencyActive(false);
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.STOP_GATT_LATENCY)
                            .targetIdentity(target)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                }

                // Set policy FORBIDDEN and disconnect HID
                actions.add(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                        .targetIdentity(target)
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .hidPolicy(KeyboardState.HidConnectionPolicy.FORBIDDEN)
                        .build());
                actions.add(KeyboardAction.builder(KeyboardAction.Type.HID_DISCONNECT)
                        .targetIdentity(target)
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());
                break;
            }

            case UEVENT_DETACHED: {
                KeyboardIdentity target = event.getTargetIdentity();
                if (target == null && state.getTargetRecord() != null) {
                    target = state.getTargetRecord().getIdentity();
                }
                if (target == null) {
                    break;
                }

                TargetRecord record = state.getTargetRecord();
                if (record == null) {
                    record = TargetRecord.createInitial(target);
                }

                // Check cycle clearance: DETACHED -> ATTACHED -> DETACHED
                if (detachedObservedForTarget && record.isAutoPairSuppressed()) {
                    record = record.withSuppressionCleared();
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.PERSIST_RECORD)
                            .targetIdentity(target)
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .targetRecord(record)
                            .build());
                    detachedObservedForTarget = false;
                }

                next.physicalState(KeyboardState.PhysicalState.DETACHED);
                next.targetRecord(record);

                if (state.isBtEnabled()) {
                    boolean bonded = state.getBondState() == KeyboardState.BondState.BONDED || record.isLastKnownBonded();
                    if (bonded) {
                        if (state.isProfileReady()) {
                            triggerDetachedConnection(next, actions, nowMs);
                            next.hidConnectionPolicy(KeyboardState.HidConnectionPolicy.ALLOWED);
                        }
                    } else {
                        triggerDetachedConnection(next, actions, nowMs);
                    }
                }
                break;
            }

            case SCAN_RESULT_MATCHED: {
                if (state.getPhysicalState() == KeyboardState.PhysicalState.ATTACHED) {
                    break; // Ignore if re-attached
                }
                next.isScanning(false);
                actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                        .targetIdentity(state.getTargetRecord().getIdentity())
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());
                actions.add(KeyboardAction.builder(KeyboardAction.Type.CREATE_BOND)
                        .targetIdentity(state.getTargetRecord().getIdentity())
                        .userId(state.getCurrentUserId())
                        .generation(state.getCurrentGeneration())
                        .build());
                break;
            }

            case SCAN_TIMEOUT: {
                if (state.getPhysicalState() == KeyboardState.PhysicalState.ATTACHED) {
                    break;
                }
                next.isScanning(false);
                int retries = state.getRetryCount();
                if (retries < MAX_RETRY_COUNT) {
                    long backoffMs = RETRY_BACKOFF_MS[retries];
                    next.retryCount(retries + 1);
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.SCHEDULE_RETRY)
                            .targetIdentity(state.getTargetRecord().getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .delayMs(backoffMs)
                            .build());
                }
                break;
            }

            case RETRY_TIMER_EXPIRED: {
                if (state.getPhysicalState() == KeyboardState.PhysicalState.DETACHED
                        && state.isBtEnabled()
                        && state.isProfileReady()
                        && state.getTargetRecord() != null
                        && !state.getTargetRecord().isAutoPairSuppressed()) {
                    triggerDetachedConnection(next, actions, nowMs);
                }
                break;
            }

            case BOND_STATE_CHANGED: {
                int bondOrdinal = event.getIntValue();
                KeyboardState.BondState newBond = KeyboardState.BondState.values()[bondOrdinal];
                next.bondState(newBond);

                TargetRecord record = state.getTargetRecord();
                if (newBond == KeyboardState.BondState.BONDED) {
                    if (record != null && !record.isLastKnownBonded()) {
                        record = record.withLastKnownBonded(true);
                        next.targetRecord(record);
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.PERSIST_RECORD)
                                .targetIdentity(record.getIdentity())
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .targetRecord(record)
                                .build());
                    }

                    // Only connect if DETACHED!
                    if (state.getPhysicalState() == KeyboardState.PhysicalState.DETACHED
                            && state.isBtEnabled()
                            && state.isProfileReady()
                            && record != null
                            && !record.isAutoPairSuppressed()) {
                        next.hidConnectionPolicy(KeyboardState.HidConnectionPolicy.ALLOWED);
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                                .targetIdentity(record.getIdentity())
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .hidPolicy(KeyboardState.HidConnectionPolicy.ALLOWED)
                                .build());
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.HID_CONNECT)
                                .targetIdentity(record.getIdentity())
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .build());
                    }
                } else if (newBond == KeyboardState.BondState.NONE) {
                    if (record != null && record.isLastKnownBonded()) {
                        record = record.withLastKnownBonded(false);
                        next.targetRecord(record);
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.PERSIST_RECORD)
                                .targetIdentity(record.getIdentity())
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .targetRecord(record)
                                .build());
                    }
                }
                break;
            }

            case HID_STATE_CHANGED: {
                int hidOrdinal = event.getIntValue();
                KeyboardState.HidState newHid = KeyboardState.HidState.values()[hidOrdinal];
                next.hidState(newHid);

                if (newHid == KeyboardState.HidState.CONNECTED) {
                    if (state.getPhysicalState() == KeyboardState.PhysicalState.DETACHED) {
                        next.gattLatencyActive(true);
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.START_GATT_LATENCY)
                                .targetIdentity(state.getTargetRecord() != null ? state.getTargetRecord().getIdentity() : null)
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .build());
                    }
                } else if (newHid == KeyboardState.HidState.DISCONNECTED) {
                    if (state.isGattLatencyActive()) {
                        next.gattLatencyActive(false);
                        actions.add(KeyboardAction.builder(KeyboardAction.Type.STOP_GATT_LATENCY)
                                .targetIdentity(state.getTargetRecord() != null ? state.getTargetRecord().getIdentity() : null)
                                .userId(state.getCurrentUserId())
                                .generation(state.getCurrentGeneration())
                                .build());
                    }
                }
                break;
            }

            case TARGET_FORGOTTEN: {
                TargetRecord record = state.getTargetRecord();
                if (record != null) {
                    record = record.withLastKnownBonded(false).withSuppression(TargetRecord.SUPPRESSION_REASON_USER);
                    next.targetRecord(record);
                    next.bondState(KeyboardState.BondState.NONE);
                    next.isScanning(false);

                    actions.add(KeyboardAction.builder(KeyboardAction.Type.PERSIST_RECORD)
                            .targetIdentity(record.getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .targetRecord(record)
                            .build());

                    actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_STOP)
                            .targetIdentity(record.getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.CANCEL_RETRY)
                            .targetIdentity(record.getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                    actions.add(KeyboardAction.builder(KeyboardAction.Type.HID_DISCONNECT)
                            .targetIdentity(record.getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .build());
                }
                break;
            }

            case RETRY_REQUESTED: {
                TargetRecord record = state.getTargetRecord();
                if (record != null) {
                    record = record.withSuppressionCleared();
                    next.targetRecord(record);
                    next.retryCount(0);

                    actions.add(KeyboardAction.builder(KeyboardAction.Type.PERSIST_RECORD)
                            .targetIdentity(record.getIdentity())
                            .userId(state.getCurrentUserId())
                            .generation(state.getCurrentGeneration())
                            .targetRecord(record)
                            .build());

                    if (state.getPhysicalState() == KeyboardState.PhysicalState.DETACHED
                            && state.isBtEnabled()
                            && state.isProfileReady()) {
                        triggerDetachedConnection(next, actions, nowMs);
                    }
                }
                break;
            }
        }

        return new TransitionResult(next.build(), actions);
    }

    private void triggerDetachedConnection(KeyboardState.Builder next, List<KeyboardAction> actions, long nowMs) {
        KeyboardState state = next.build();
        TargetRecord record = state.getTargetRecord();
        if (record == null || record.isAutoPairSuppressed() || state.isScanning()
                || state.getBondState() == KeyboardState.BondState.BONDING
                || state.getHidState() == KeyboardState.HidState.CONNECTING
                || state.getHidState() == KeyboardState.HidState.CONNECTED) {
            return;
        }

        if (state.getBondState() == KeyboardState.BondState.BONDED || record.isLastKnownBonded()) {
            actions.add(KeyboardAction.builder(KeyboardAction.Type.SET_HID_POLICY)
                    .targetIdentity(record.getIdentity())
                    .userId(state.getCurrentUserId())
                    .generation(state.getCurrentGeneration())
                    .hidPolicy(KeyboardState.HidConnectionPolicy.ALLOWED)
                    .build());
            actions.add(KeyboardAction.builder(KeyboardAction.Type.HID_CONNECT)
                    .targetIdentity(record.getIdentity())
                    .userId(state.getCurrentUserId())
                    .generation(state.getCurrentGeneration())
                    .build());
        } else {
            next.isScanning(true);
            actions.add(KeyboardAction.builder(KeyboardAction.Type.SCAN_START)
                    .targetIdentity(record.getIdentity())
                    .userId(state.getCurrentUserId())
                    .generation(state.getCurrentGeneration())
                    .delayMs(SCAN_BUDGET_MS)
                    .build());
        }
    }
}
