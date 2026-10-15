package org.lineageos.pogokeyboard;

import java.util.Objects;

/**
 * Immutable persistent record for a keyboard target.
 * Stores target identity, suppression state, last known bond state, and monotonic record version.
 */
public final class TargetRecord {

    public static final String SUPPRESSION_REASON_USER = "USER_SUPPRESSED";
    public static final String SUPPRESSION_REASON_STORAGE_FAILURE = "STORAGE_FAILURE";
    public static final String SUPPRESSION_REASON_OFFLINE_UNBOND = "OFFLINE_UNBOND";

    private final KeyboardIdentity identity;
    private final boolean autoPairSuppressed;
    private final String suppressionReason;
    private final boolean lastKnownBonded;
    private final long recordVersion;

    public TargetRecord(KeyboardIdentity identity, boolean autoPairSuppressed, String suppressionReason,
                        boolean lastKnownBonded, long recordVersion) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.autoPairSuppressed = autoPairSuppressed;
        this.suppressionReason = suppressionReason != null ? suppressionReason : "";
        this.lastKnownBonded = lastKnownBonded;
        this.recordVersion = recordVersion;
    }

    public static TargetRecord createInitial(KeyboardIdentity identity) {
        return new TargetRecord(identity, false, "", false, 1L);
    }

    public KeyboardIdentity getIdentity() {
        return identity;
    }

    public boolean isAutoPairSuppressed() {
        return autoPairSuppressed;
    }

    public String getSuppressionReason() {
        return suppressionReason;
    }

    public boolean isLastKnownBonded() {
        return lastKnownBonded;
    }

    public long getRecordVersion() {
        return recordVersion;
    }

    public TargetRecord withSuppression(String reason) {
        return new TargetRecord(this.identity, true, reason, this.lastKnownBonded, this.recordVersion + 1);
    }

    public TargetRecord withSuppressionCleared() {
        return new TargetRecord(this.identity, false, "", this.lastKnownBonded, this.recordVersion + 1);
    }

    public TargetRecord withLastKnownBonded(boolean bonded) {
        return new TargetRecord(this.identity, this.autoPairSuppressed, this.suppressionReason, bonded, this.recordVersion + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetRecord)) return false;
        TargetRecord that = (TargetRecord) o;
        return autoPairSuppressed == that.autoPairSuppressed &&
                lastKnownBonded == that.lastKnownBonded &&
                recordVersion == that.recordVersion &&
                identity.equals(that.identity) &&
                suppressionReason.equals(that.suppressionReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, autoPairSuppressed, suppressionReason, lastKnownBonded, recordVersion);
    }

    @Override
    public String toString() {
        return "TargetRecord{" +
                "identity=" + identity +
                ", autoPairSuppressed=" + autoPairSuppressed +
                ", suppressionReason='" + suppressionReason + '\'' +
                ", lastKnownBonded=" + lastKnownBonded +
                ", recordVersion=" + recordVersion +
                '}';
    }
}
