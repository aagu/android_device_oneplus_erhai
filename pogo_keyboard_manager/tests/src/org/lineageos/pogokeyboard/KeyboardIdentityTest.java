package org.lineageos.pogokeyboard;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Optional;

public class KeyboardIdentityTest {

    @Test
    public void testParseValidRaw12HexLower() {
        Optional<KeyboardIdentity> id = KeyboardIdentity.parseDriverAddress("7c6cf0e3d47a");
        assertTrue(id.isPresent());
        assertEquals("7C:6C:F0:E3:D4:7A", id.get().getFormattedAddress());
        assertEquals("7c6cf0e3d47a", id.get().getRawAddress().toLowerCase());
    }

    @Test
    public void testParseValidRaw12HexUpper() {
        Optional<KeyboardIdentity> id = KeyboardIdentity.parseDriverAddress("7C6CF0E3D47A");
        assertTrue(id.isPresent());
        assertEquals("7C:6C:F0:E3:D4:7A", id.get().getFormattedAddress());
    }

    @Test
    public void testParseValidColonDelimited() {
        Optional<KeyboardIdentity> id = KeyboardIdentity.parseDriverAddress("7C:6C:F0:E3:D4:7A");
        assertTrue(id.isPresent());
        assertEquals("7C:6C:F0:E3:D4:7A", id.get().getFormattedAddress());
        assertEquals("7C:6C:F0:E3:D4:7A", id.get().getRawAddress());
    }

    @Test
    public void testParseValidHyphenDelimited() {
        Optional<KeyboardIdentity> id = KeyboardIdentity.parseDriverAddress("7C-6C-F0-E3-D4-7A");
        assertTrue(id.isPresent());
        assertEquals("7C:6C:F0:E3:D4:7A", id.get().getFormattedAddress());
    }

    @Test
    public void testDifferentOuiAndSyntheticAddresses() {
        // Different valid unicast addresses with different OUIs
        Optional<KeyboardIdentity> id1 = KeyboardIdentity.parseDriverAddress("00:11:22:33:44:55");
        assertTrue(id1.isPresent());
        assertEquals("00:11:22:33:44:55", id1.get().getFormattedAddress());

        Optional<KeyboardIdentity> id2 = KeyboardIdentity.parseDriverAddress("aabbccddeeff");
        assertTrue(id2.isPresent());
        assertEquals("AA:BB:CC:DD:EE:FF", id2.get().getFormattedAddress());
    }

    @Test
    public void testRejectsNullAndEmpty() {
        assertFalse(KeyboardIdentity.parseDriverAddress(null).isPresent());
        assertFalse(KeyboardIdentity.parseDriverAddress("").isPresent());
        assertFalse(KeyboardIdentity.parseDriverAddress("   ").isPresent());
    }

    @Test
    public void testRejectsAllZeros() {
        assertFalse(KeyboardIdentity.parseDriverAddress("000000000000").isPresent());
        assertFalse(KeyboardIdentity.parseDriverAddress("00:00:00:00:00:00").isPresent());
    }

    @Test
    public void testRejectsAllOnesBroadcast() {
        assertFalse(KeyboardIdentity.parseDriverAddress("ffffffffffff").isPresent());
        assertFalse(KeyboardIdentity.parseDriverAddress("FF:FF:FF:FF:FF:FF").isPresent());
    }

    @Test
    public void testRejectsMulticast() {
        // Bit 0 of first octet is 1 -> multicast, invalid for BLE device address
        assertFalse(KeyboardIdentity.parseDriverAddress("01:00:00:00:00:01").isPresent());
        assertFalse(KeyboardIdentity.parseDriverAddress("010203040506").isPresent());
    }

    @Test
    public void testRejectsInvalidHexOrLength() {
        assertFalse(KeyboardIdentity.parseDriverAddress("7c6cf0e3d47g").isPresent()); // non-hex 'g'
        assertFalse(KeyboardIdentity.parseDriverAddress("7c6cf0e3d47").isPresent());  // 11 chars
        assertFalse(KeyboardIdentity.parseDriverAddress("7c6cf0e3d47aa").isPresent()); // 13 chars
        assertFalse(KeyboardIdentity.parseDriverAddress("7C:6C:F0:E3:D4").isPresent()); // too short
        assertFalse(KeyboardIdentity.parseDriverAddress("7C:6C:F0:E3:D4:7A:00").isPresent()); // too long
    }

    @Test
    public void testIdentityWithBleNameAndReportSn() {
        KeyboardIdentity id = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN123456");
        assertEquals("7c6cf0e3d47a", id.getRawAddress());
        assertEquals("7C:6C:F0:E3:D4:7A", id.getFormattedAddress());
        assertEquals("OnePlus Pad 2 Pro Keyboard", id.getBleName());
        assertEquals("SN123456", id.getReportSn());
    }

    @Test
    public void testEqualityAndHashCode() {
        KeyboardIdentity id1 = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN1");
        KeyboardIdentity id2 = new KeyboardIdentity("7C6CF0E3D47A", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN2");
        KeyboardIdentity id3 = new KeyboardIdentity("001122334455", "00:11:22:33:44:55", "OnePlus Pad 2 Pro Keyboard", "SN1");

        // Identity equivalence is based on formatted MAC address
        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
        assertNotEquals(id1, id3);
    }
}
