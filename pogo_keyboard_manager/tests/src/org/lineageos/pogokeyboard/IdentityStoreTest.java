/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.pogokeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class IdentityStoreTest {

    private File tempFile;
    private IdentityStore identityStore;
    private KeyboardIdentity testIdentity;

    @Before
    public void setUp() throws Exception {
        tempFile = File.createTempFile("pogo_identity_test", ".json");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        identityStore = new IdentityStore(tempFile);
        testIdentity = KeyboardIdentity.parseDriverAddress("7c6cf0e3d47a", "OnePlus Pad Smart Keyboard", "SN12345678").get();
    }

    @After
    public void tearDown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void testLoadReturnsEmptyWhenFileDoesNotExist() throws Exception {
        Optional<TargetRecord> loaded = identityStore.load();
        assertFalse("Should be empty when file doesn't exist", loaded.isPresent());
    }

    @Test
    public void testSaveAndLoadAtomicIntegrity() throws Exception {
        TargetRecord record = new TargetRecord(testIdentity, false, "", true, 5L);
        identityStore.save(record);

        assertTrue("File must exist after save", tempFile.exists());

        Optional<TargetRecord> loadedOpt = identityStore.load();
        assertTrue("Record must be present after save", loadedOpt.isPresent());

        TargetRecord loaded = loadedOpt.get();
        assertEquals(record.getIdentity(), loaded.getIdentity());
        assertEquals(record.isAutoPairSuppressed(), loaded.isAutoPairSuppressed());
        assertEquals(record.getSuppressionReason(), loaded.getSuppressionReason());
        assertEquals(record.isLastKnownBonded(), loaded.isLastKnownBonded());
        assertEquals(record.getRecordVersion(), loaded.getRecordVersion());
    }

    @Test
    public void testClearDeletesFile() throws Exception {
        TargetRecord record = TargetRecord.createInitial(testIdentity);
        identityStore.save(record);
        assertTrue(tempFile.exists());

        identityStore.clear();
        assertFalse("File must be deleted after clear()", tempFile.exists());
        assertFalse(identityStore.load().isPresent());
    }

    @Test
    public void testLoadCorruptedFileThrowsIOException() throws Exception {
        // Write corrupted JSON content
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write("{ invalid json corrupted content ...".getBytes(StandardCharsets.UTF_8));
        }

        try {
            identityStore.load();
            fail("Should throw IOException on corrupted JSON");
        } catch (IOException e) {
            // Expected
        }
    }
}
