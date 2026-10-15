package org.lineageos.pogokeyboard;
 
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable representation of a physical Pogo / BLE keyboard identity.
 * Contains validated raw address, formatted MAC XX:XX:XX:XX:XX:XX, BLE name, and serial number.
 */
public final class KeyboardIdentity implements Parcelable {



    private static final Pattern HEX_12_PATTERN = Pattern.compile("^[0-9A-Fa-f]{12}$");
    private static final Pattern COLON_MAC_PATTERN = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");

    private final String rawAddress;
    private final String formattedAddress;
    private final String bleName;
    private final String reportSn;

    public KeyboardIdentity(String rawAddress, String formattedAddress, String bleName, String reportSn) {
        this.rawAddress = Objects.requireNonNull(rawAddress, "rawAddress must not be null");
        this.formattedAddress = Objects.requireNonNull(formattedAddress, "formattedAddress must not be null");
        this.bleName = bleName != null ? bleName : "";
        this.reportSn = reportSn != null ? reportSn : "";
    }

    public String getRawAddress() {
        return rawAddress;
    }

    public String getFormattedAddress() {
        return formattedAddress;
    }

    public String getBleName() {
        return bleName;
    }

    public String getReportSn() {
        return reportSn;
    }

    /**
     * Returns an anonymized representation of the address (e.g. "XX:XX:XX:XX:D4:7A") for safe logging.
     */
    public String getAnonymizedAddress() {
        if (formattedAddress.length() == 17) {
            return "XX:XX:XX:XX:" + formattedAddress.substring(12);
        }
        return "XX:XX:XX:XX:XX:XX";
    }

    /**
     * Parses driver hex address string (e.g. 12-char hex "7c6cf0e3d47a" or colon/hyphen delimited).
     * Validates non-null, valid hex, 6 octets, non-zero, non-broadcast (FF:FF:FF:FF:FF:FF),
     * and valid unicast (least significant bit of first octet is 0).
     */
    public static Optional<KeyboardIdentity> parseDriverAddress(String raw) {
        return parseDriverAddress(raw, null, null);
    }

    public static Optional<KeyboardIdentity> parseDriverAddress(String raw, String bleName, String reportSn) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String hexOnly;
        if (HEX_12_PATTERN.matcher(trimmed).matches()) {
            hexOnly = trimmed;
        } else if (COLON_MAC_PATTERN.matcher(trimmed).matches()) {
            hexOnly = trimmed.replace(":", "").replace("-", "");
        } else {
            return Optional.empty();
        }

        if (hexOnly.length() != 12) {
            return Optional.empty();
        }

        // Convert to uppercase
        hexOnly = hexOnly.toUpperCase(Locale.US);

        // Check for all-zeros
        if ("000000000000".equals(hexOnly)) {
            return Optional.empty();
        }

        // Check for all-ones (broadcast)
        if ("FFFFFFFFFFFF".equals(hexOnly)) {
            return Optional.empty();
        }

        // Check unicast: first byte least significant bit must be 0
        int firstByte = Integer.parseInt(hexOnly.substring(0, 2), 16);
        if ((firstByte & 0x01) != 0) {
            // Multicast address is not valid for unicast BLE device
            return Optional.empty();
        }

        // Format to XX:XX:XX:XX:XX:XX
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hexOnly, i, i + 2);
        }
        String formatted = sb.toString();

        return Optional.of(new KeyboardIdentity(trimmed, formatted, bleName, reportSn));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyboardIdentity)) return false;
        KeyboardIdentity that = (KeyboardIdentity) o;
        return formattedAddress.equalsIgnoreCase(that.formattedAddress);
    }

    @Override
    public int hashCode() {
        return formattedAddress.toUpperCase(Locale.US).hashCode();
    }

    @Override
    public String toString() {
        return "KeyboardIdentity{" +
                "formattedAddress='" + formattedAddress + '\'' +
                ", bleName='" + bleName + '\'' +
                ", reportSn='" + reportSn + '\'' +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }


    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(rawAddress);
        dest.writeString(formattedAddress);
        dest.writeString(bleName);
        dest.writeString(reportSn);
    }

    public static final Parcelable.Creator<KeyboardIdentity> CREATOR = new Parcelable.Creator<KeyboardIdentity>() {
        @Override
        public KeyboardIdentity createFromParcel(Parcel in) {
            String raw = in.readString();
            String formatted = in.readString();
            String ble = in.readString();
            String sn = in.readString();
            return new KeyboardIdentity(raw, formatted, ble, sn);
        }

        @Override
        public KeyboardIdentity[] newArray(int size) {
            return new KeyboardIdentity[size];
        }
    };
}

