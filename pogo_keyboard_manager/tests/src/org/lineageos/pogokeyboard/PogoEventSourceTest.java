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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PogoEventSourceTest {

    private File tempProcFile;
    private Handler testHandler;
    private PogoEventSource eventSource;
    private List<KeyboardEvent> receivedEvents;

    @Before
    public void setUp() throws Exception {
        tempProcFile = File.createTempFile("pogo_proc_test", ".txt");
        if (tempProcFile.exists()) {
            tempProcFile.delete();
        }

        Looper looper = Looper.myLooper();
        if (looper == null) {
            Looper.prepare();
            looper = Looper.myLooper();
        }
        testHandler = new Handler(looper);
        receivedEvents = new ArrayList<>();
        eventSource = new PogoEventSource(testHandler, tempProcFile);
        eventSource.start(receivedEvents::add);
    }

    @After
    public void tearDown() {
        if (eventSource != null) {
            eventSource.close();
        }
        if (tempProcFile != null && tempProcFile.exists()) {
            tempProcFile.delete();
        }
    }

    @Test
    public void testProcessAttachedEvent() {
        eventSource.processEventPayload("1", "7c6cf0e3d47a", "OnePlus Pad Smart Keyboard", "SN001");
        testHandler.getLooper().getQueue(); // Process any queued messages

        assertEquals(1, receivedEvents.size());
        KeyboardEvent event = receivedEvents.get(0);
        assertEquals(KeyboardEvent.Type.UEVENT_ATTACHED, event.getType());
        assertNotNull(event.getTargetIdentity());
        assertEquals("7C:6C:F0:E3:D4:7A", event.getTargetIdentity().getFormattedAddress());
        assertEquals("OnePlus Pad Smart Keyboard", event.getTargetIdentity().getBleName());
    }

    @Test
    public void testProcessDetachedEvent() {
        eventSource.processEventPayload("0", "7c6cf0e3d47a", "OnePlus Pad Smart Keyboard", "SN001");

        assertEquals(1, receivedEvents.size());
        KeyboardEvent event = receivedEvents.get(0);
        assertEquals(KeyboardEvent.Type.UEVENT_DETACHED, event.getType());
        assertNotNull(event.getTargetIdentity());
    }

    @Test
    public void testAttachedWithoutMacIgnored() {
        eventSource.processEventPayload("1", null, null, null);
        assertTrue("Event without MAC should be dropped", receivedEvents.isEmpty());
    }

    @Test
    public void testReadDirectFromProcFile() throws Exception {
        try (FileOutputStream fos = new FileOutputStream(tempProcFile)) {
            String content = "pogopin_status=1\nmac_addr=7c6cf0e3d47a\nkeyboard_ble_name=PogoKbd\nreport_sn=SN123\n";
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        eventSource.requestSnapshot();

        assertEquals(1, receivedEvents.size());
        KeyboardEvent event = receivedEvents.get(0);
        assertEquals(KeyboardEvent.Type.UEVENT_ATTACHED, event.getType());
        assertEquals("7C:6C:F0:E3:D4:7A", event.getTargetIdentity().getFormattedAddress());
    }
}
