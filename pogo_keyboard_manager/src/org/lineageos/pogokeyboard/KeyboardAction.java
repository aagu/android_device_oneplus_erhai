package org.lineageos.pogokeyboard;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Immutable action emitted by KeyboardCoordinator to be executed by platform adapters.
 */
public final class KeyboardAction implements Parcelable {


    public enum Type {
        PERSIST_RECORD,
        SCAN_START,
        SCAN_STOP,
        CREATE_BOND,
        CANCEL_BOND,
        SET_HID_POLICY,
        HID_CONNECT,
        HID_DISCONNECT,
        START_GATT_LATENCY,
        STOP_GATT_LATENCY,
        SCHEDULE_RETRY,
        CANCEL_RETRY,
        NOTIFY_USER_SWITCH
    }

    private final Type type;
    private final KeyboardIdentity targetIdentity;
    private final int userId;
    private final long generation;
    // Parameter payloads:
    private final TargetRecord targetRecord; // for PERSIST_RECORD
    private final KeyboardState.HidConnectionPolicy hidPolicy; // for SET_HID_POLICY
    private final long delayMs; // for SCHEDULE_RETRY or SCAN_START budget
    private final String reason; // for logging or diagnostics

    private KeyboardAction(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.targetIdentity = builder.targetIdentity;
        this.userId = builder.userId;
        this.generation = builder.generation;
        this.targetRecord = builder.targetRecord;
        this.hidPolicy = builder.hidPolicy;
        this.delayMs = builder.delayMs;
        this.reason = builder.reason != null ? builder.reason : "";
    }

    public Type getType() {
        return type;
    }

    public KeyboardIdentity getTargetIdentity() {
        return targetIdentity;
    }

    public int getUserId() {
        return userId;
    }

    public long getGeneration() {
        return generation;
    }

    public TargetRecord getTargetRecord() {
        return targetRecord;
    }

    public KeyboardState.HidConnectionPolicy getHidPolicy() {
        return hidPolicy;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public String getReason() {
        return reason;
    }

    public static Builder builder(Type type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final Type type;
        private KeyboardIdentity targetIdentity;
        private int userId;
        private long generation;
        private TargetRecord targetRecord;
        private KeyboardState.HidConnectionPolicy hidPolicy;
        private long delayMs;
        private String reason;

        public Builder(Type type) {
            this.type = type;
        }

        public Builder targetIdentity(KeyboardIdentity targetIdentity) {
            this.targetIdentity = targetIdentity;
            return this;
        }

        public Builder userId(int userId) {
            this.userId = userId;
            return this;
        }

        public Builder generation(long generation) {
            this.generation = generation;
            return this;
        }

        public Builder targetRecord(TargetRecord targetRecord) {
            this.targetRecord = targetRecord;
            return this;
        }

        public Builder hidPolicy(KeyboardState.HidConnectionPolicy hidPolicy) {
            this.hidPolicy = hidPolicy;
            return this;
        }

        public Builder delayMs(long delayMs) {
            this.delayMs = delayMs;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public KeyboardAction build() {
            return new KeyboardAction(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyboardAction)) return false;
        KeyboardAction that = (KeyboardAction) o;
        return userId == that.userId &&
                generation == that.generation &&
                delayMs == that.delayMs &&
                type == that.type &&
                Objects.equals(targetIdentity, that.targetIdentity) &&
                Objects.equals(targetRecord, that.targetRecord) &&
                hidPolicy == that.hidPolicy &&
                Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, targetIdentity, userId, generation, targetRecord, hidPolicy, delayMs, reason);
    }

    @Override
    public String toString() {
        return "KeyboardAction{" +
                "type=" + type +
                ", targetIdentity=" + targetIdentity +
                ", userId=" + userId +
                ", generation=" + generation +
                ", targetRecord=" + targetRecord +
                ", hidPolicy=" + hidPolicy +
                ", delayMs=" + delayMs +
                ", reason='" + reason + '\'' +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }


    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type != null ? type.name() : "");
        dest.writeParcelable(targetIdentity, flags);
        dest.writeInt(userId);
        dest.writeLong(generation);
        dest.writeString(hidPolicy != null ? hidPolicy.name() : "");
        dest.writeLong(delayMs);
        dest.writeString(reason);
    }

    public static final Parcelable.Creator<KeyboardAction> CREATOR = new Parcelable.Creator<KeyboardAction>() {
        @Override
        public KeyboardAction createFromParcel(Parcel in) {
            String typeName = in.readString();
            Type type = typeName != null && !typeName.isEmpty() ? Type.valueOf(typeName) : Type.CANCEL_RETRY;
            KeyboardIdentity targetIdentity = in.readParcelable(KeyboardIdentity.class.getClassLoader(), KeyboardIdentity.class);
            int userId = in.readInt();
            long generation = in.readLong();
            String hidPolicyName = in.readString();
            KeyboardState.HidConnectionPolicy hidPolicy = (hidPolicyName != null && !hidPolicyName.isEmpty())
                    ? KeyboardState.HidConnectionPolicy.valueOf(hidPolicyName) : null;
            long delayMs = in.readLong();
            String reason = in.readString();

            return KeyboardAction.builder(type)
                    .targetIdentity(targetIdentity)
                    .userId(userId)
                    .generation(generation)
                    .hidPolicy(hidPolicy)
                    .delayMs(delayMs)
                    .reason(reason)
                    .build();
        }

        @Override
        public KeyboardAction[] newArray(int size) {
            return new KeyboardAction[size];
        }
    };
}

