package org.lineageos.pogokeyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetRecordTest {

    @Test
    public void testCreationAndGetters() {
        KeyboardIdentity id = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN1");
        TargetRecord record = new TargetRecord(id, false, "", true, 1L);

        assertEquals(id, record.getIdentity());
        assertFalse(record.isAutoPairSuppressed());
        assertEquals("", record.getSuppressionReason());
        assertTrue(record.isLastKnownBonded());
        assertEquals(1L, record.getRecordVersion());
    }

    @Test
    public void testWithSuppression() {
        KeyboardIdentity id = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN1");
        TargetRecord initial = TargetRecord.createInitial(id);

        assertFalse(initial.isAutoPairSuppressed());
        assertEquals(1L, initial.getRecordVersion());

        TargetRecord suppressed = initial.withSuppression("USER_SUPPRESSED");
        assertTrue(suppressed.isAutoPairSuppressed());
        assertEquals("USER_SUPPRESSED", suppressed.getSuppressionReason());
        assertEquals(2L, suppressed.getRecordVersion());
        assertEquals(id, suppressed.getIdentity());

        TargetRecord cleared = suppressed.withSuppressionCleared();
        assertFalse(cleared.isAutoPairSuppressed());
        assertEquals("", cleared.getSuppressionReason());
        assertEquals(3L, cleared.getRecordVersion());
    }

    @Test
    public void testWithLastKnownBonded() {
        KeyboardIdentity id = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN1");
        TargetRecord initial = TargetRecord.createInitial(id);
        assertFalse(initial.isLastKnownBonded());

        TargetRecord bonded = initial.withLastKnownBonded(true);
        assertTrue(bonded.isLastKnownBonded());
        assertEquals(2L, bonded.getRecordVersion());

        TargetRecord unbonded = bonded.withLastKnownBonded(false);
        assertFalse(unbonded.isLastKnownBonded());
        assertEquals(3L, unbonded.getRecordVersion());
    }

    @Test
    public void testImmutabilityAndEquality() {
        KeyboardIdentity id = new KeyboardIdentity("7c6cf0e3d47a", "7C:6C:F0:E3:D4:7A", "OnePlus Pad 2 Pro Keyboard", "SN1");
        TargetRecord r1 = new TargetRecord(id, false, "", false, 1L);
        TargetRecord r2 = new TargetRecord(id, false, "", false, 1L);
        TargetRecord r3 = new TargetRecord(id, true, "USER_SUPPRESSED", false, 2L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
    }
}
