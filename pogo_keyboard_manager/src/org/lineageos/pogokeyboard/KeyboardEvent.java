package org.lineageos.pogokeyboard;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Immutable representation of an event in the keyboard management lifecycle.
 */
public final class KeyboardEvent implements Parcelable {


    public enum Type {
        UEVENT_ATTACHED,
        UEVENT_DETACHED,
        BT_STATE_CHANGED,
        PROFILE_AVAILABILITY_CHANGED,
        BOND_STATE_CHANGED,
        HID_STATE_CHANGED,
        RETRY_TIMER_EXPIRED,
        USER_SWITCHED,
        TARGET_FORGOTTEN,
        RETRY_REQUESTED,
        SCAN_RESULT_MATCHED,
        SCAN_TIMEOUT
    }

    private final Type type;
    private final KeyboardIdentity targetIdentity;
    private final int userId;
    private final long generation;
    private final long timestampMs;
    // Optional event-specific payloads:
    private final boolean flag; // e.g., btEnabled, profileReady
    private final int intValue; // e.g., bondState, hidState, newUserId
    private final String stringValue; // e.g., reason

    private KeyboardEvent(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.targetIdentity = builder.targetIdentity;
        this.userId = builder.userId;
        this.generation = builder.generation;
        this.timestampMs = builder.timestampMs;
        this.flag = builder.flag;
        this.intValue = builder.intValue;
        this.stringValue = builder.stringValue != null ? builder.stringValue : "";
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

    public long getTimestampMs() {
        return timestampMs;
    }

    public boolean isFlag() {
        return flag;
    }

    public int getIntValue() {
        return intValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public static Builder builder(Type type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final Type type;
        private KeyboardIdentity targetIdentity;
        private int userId;
        private long generation;
        private long timestampMs;
        private boolean flag;
        private int intValue;
        private String stringValue;

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

        public Builder timestampMs(long timestampMs) {
            this.timestampMs = timestampMs;
            return this;
        }

        public Builder flag(boolean flag) {
            this.flag = flag;
            return this;
        }

        public Builder intValue(int intValue) {
            this.intValue = intValue;
            return this;
        }

        public Builder stringValue(String stringValue) {
            this.stringValue = stringValue;
            return this;
        }

        public KeyboardEvent build() {
            return new KeyboardEvent(this);
        }
    }

    @Override
    public String toString() {
        return "KeyboardEvent{" +
                "type=" + type +
                ", targetIdentity=" + targetIdentity +
                ", userId=" + userId +
                ", generation=" + generation +
                ", timestampMs=" + timestampMs +
                ", flag=" + flag +
                ", intValue=" + intValue +
                ", stringValue='" + stringValue + '\'' +
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
        dest.writeLong(timestampMs);
        dest.writeByte((byte) (flag ? 1 : 0));
        dest.writeInt(intValue);
        dest.writeString(stringValue);
    }

    public static final Parcelable.Creator<KeyboardEvent> CREATOR = new Parcelable.Creator<KeyboardEvent>() {
        @Override
        public KeyboardEvent createFromParcel(Parcel in) {
            String typeName = in.readString();
            Type type = typeName != null && !typeName.isEmpty() ? Type.valueOf(typeName) : Type.RETRY_TIMER_EXPIRED;
            KeyboardIdentity targetIdentity = in.readParcelable(KeyboardIdentity.class.getClassLoader(), KeyboardIdentity.class);
            int userId = in.readInt();
            long generation = in.readLong();
            long timestampMs = in.readLong();
            boolean flag = in.readByte() != 0;
            int intValue = in.readInt();
            String stringValue = in.readString();

            return KeyboardEvent.builder(type)
                    .targetIdentity(targetIdentity)
                    .userId(userId)
                    .generation(generation)
                    .timestampMs(timestampMs)
                    .flag(flag)
                    .intValue(intValue)
                    .stringValue(stringValue)
                    .build();
        }

        @Override
        public KeyboardEvent[] newArray(int size) {
            return new KeyboardEvent[size];
        }
    };
}

