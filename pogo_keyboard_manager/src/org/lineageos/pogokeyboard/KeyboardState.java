package org.lineageos.pogokeyboard;

import java.util.Objects;

/**
 * Immutable state of the Keyboard Coordinator.
 */
public final class KeyboardState {

    public enum PhysicalState {
        UNKNOWN,
        ATTACHED,
        DETACHED
    }

    public enum BondState {
        NONE,
        BONDING,
        BONDED
    }

    public enum HidConnectionPolicy {
        UNKNOWN,
        FORBIDDEN,
        ALLOWED
    }

    public enum HidState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private final PhysicalState physicalState;
    private final boolean btEnabled;
    private final boolean profileReady;
    private final BondState bondState;
    private final HidConnectionPolicy hidConnectionPolicy;
    private final HidState hidState;
    private final int retryCount;
    private final long currentGeneration;
    private final int currentUserId;
    private final TargetRecord targetRecord;
    private final boolean isScanning;
    private final boolean gattLatencyActive;

    public KeyboardState(
            PhysicalState physicalState,
            boolean btEnabled,
            boolean profileReady,
            BondState bondState,
            HidConnectionPolicy hidConnectionPolicy,
            HidState hidState,
            int retryCount,
            long currentGeneration,
            int currentUserId,
            TargetRecord targetRecord,
            boolean isScanning,
            boolean gattLatencyActive) {
        this.physicalState = Objects.requireNonNull(physicalState, "physicalState must not be null");
        this.btEnabled = btEnabled;
        this.profileReady = profileReady;
        this.bondState = Objects.requireNonNull(bondState, "bondState must not be null");
        this.hidConnectionPolicy = Objects.requireNonNull(hidConnectionPolicy, "hidConnectionPolicy must not be null");
        this.hidState = Objects.requireNonNull(hidState, "hidState must not be null");
        this.retryCount = retryCount;
        this.currentGeneration = currentGeneration;
        this.currentUserId = currentUserId;
        this.targetRecord = targetRecord;
        this.isScanning = isScanning;
        this.gattLatencyActive = gattLatencyActive;
    }

    public static KeyboardState initial(int userId) {
        return new KeyboardState(
                PhysicalState.UNKNOWN,
                false,
                false,
                BondState.NONE,
                HidConnectionPolicy.UNKNOWN,
                HidState.DISCONNECTED,
                0,
                1L,
                userId,
                null,
                false,
                false
        );
    }

    public PhysicalState getPhysicalState() {
        return physicalState;
    }

    public boolean isBtEnabled() {
        return btEnabled;
    }

    public boolean isProfileReady() {
        return profileReady;
    }

    public BondState getBondState() {
        return bondState;
    }

    public HidConnectionPolicy getHidConnectionPolicy() {
        return hidConnectionPolicy;
    }

    public HidState getHidState() {
        return hidState;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getCurrentGeneration() {
        return currentGeneration;
    }

    public int getCurrentUserId() {
        return currentUserId;
    }

    public TargetRecord getTargetRecord() {
        return targetRecord;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isGattLatencyActive() {
        return gattLatencyActive;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private PhysicalState physicalState;
        private boolean btEnabled;
        private boolean profileReady;
        private BondState bondState;
        private HidConnectionPolicy hidConnectionPolicy;
        private HidState hidState;
        private int retryCount;
        private long currentGeneration;
        private int currentUserId;
        private TargetRecord targetRecord;
        private boolean isScanning;
        private boolean gattLatencyActive;

        public Builder(KeyboardState state) {
            this.physicalState = state.physicalState;
            this.btEnabled = state.btEnabled;
            this.profileReady = state.profileReady;
            this.bondState = state.bondState;
            this.hidConnectionPolicy = state.hidConnectionPolicy;
            this.hidState = state.hidState;
            this.retryCount = state.retryCount;
            this.currentGeneration = state.currentGeneration;
            this.currentUserId = state.currentUserId;
            this.targetRecord = state.targetRecord;
            this.isScanning = state.isScanning;
            this.gattLatencyActive = state.gattLatencyActive;
        }

        public Builder physicalState(PhysicalState physicalState) {
            this.physicalState = physicalState;
            return this;
        }

        public Builder btEnabled(boolean btEnabled) {
            this.btEnabled = btEnabled;
            return this;
        }

        public Builder profileReady(boolean profileReady) {
            this.profileReady = profileReady;
            return this;
        }

        public Builder bondState(BondState bondState) {
            this.bondState = bondState;
            return this;
        }

        public Builder hidConnectionPolicy(HidConnectionPolicy hidConnectionPolicy) {
            this.hidConnectionPolicy = hidConnectionPolicy;
            return this;
        }

        public Builder hidState(HidState hidState) {
            this.hidState = hidState;
            return this;
        }

        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Builder currentGeneration(long currentGeneration) {
            this.currentGeneration = currentGeneration;
            return this;
        }

        public Builder currentUserId(int currentUserId) {
            this.currentUserId = currentUserId;
            return this;
        }

        public Builder targetRecord(TargetRecord targetRecord) {
            this.targetRecord = targetRecord;
            return this;
        }

        public Builder isScanning(boolean isScanning) {
            this.isScanning = isScanning;
            return this;
        }

        public Builder gattLatencyActive(boolean gattLatencyActive) {
            this.gattLatencyActive = gattLatencyActive;
            return this;
        }

        public KeyboardState build() {
            return new KeyboardState(
                    physicalState,
                    btEnabled,
                    profileReady,
                    bondState,
                    hidConnectionPolicy,
                    hidState,
                    retryCount,
                    currentGeneration,
                    currentUserId,
                    targetRecord,
                    isScanning,
                    gattLatencyActive
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyboardState)) return false;
        KeyboardState that = (KeyboardState) o;
        return btEnabled == that.btEnabled &&
                profileReady == that.profileReady &&
                retryCount == that.retryCount &&
                currentGeneration == that.currentGeneration &&
                currentUserId == that.currentUserId &&
                isScanning == that.isScanning &&
                gattLatencyActive == that.gattLatencyActive &&
                physicalState == that.physicalState &&
                bondState == that.bondState &&
                hidConnectionPolicy == that.hidConnectionPolicy &&
                hidState == that.hidState &&
                Objects.equals(targetRecord, that.targetRecord);
    }

    @Override
    public int hashCode() {
        return Objects.hash(physicalState, btEnabled, profileReady, bondState, hidConnectionPolicy,
                hidState, retryCount, currentGeneration, currentUserId, targetRecord, isScanning, gattLatencyActive);
    }
}
