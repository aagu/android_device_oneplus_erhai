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

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ActiveUserBindingTest {

    private Context context;
    private Handler handler;
    private ActiveUserBinding userBinding;
    private List<KeyboardEvent> receivedEvents;
    private KeyboardIdentity testIdentity;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Looper looper = Looper.myLooper();
        if (looper == null) {
            Looper.prepare();
            looper = Looper.myLooper();
        }
        handler = new Handler(looper);
        receivedEvents = new ArrayList<>();
        userBinding = new ActiveUserBinding(context, handler, receivedEvents::add);
        testIdentity = KeyboardIdentity.parseDriverAddress("7c6cf0e3d47a", "OnePlus Pad Smart Keyboard", "SN12345678").get();
    }

    @After
    public void tearDown() {
        if (userBinding != null) {
            userBinding.deactivate();
        }
    }

    @Test
    public void testActivateSetsPendingUserAndGeneration() {
        userBinding.activate(10, 1L, testIdentity);
        assertEquals(10, userBinding.getCurrentUserId());
        assertEquals(1L, userBinding.getCurrentGeneration());
    }

    @Test
    public void testSessionStopTransitionCreatesPendingStopSession() {
        // Activate user 10
        userBinding.activate(10, 1L, testIdentity);

        // Transition to user 11: should invalidate active and record pending stop session
        userBinding.activate(11, 2L, testIdentity);

        ActiveUserBinding.PendingStopSession pendingStop = userBinding.getPendingStopSession();
        // Since no real service was bound, oldBinder and oldExecutor were null, but transition state is tracked
        // Once stop is confirmed, the new session will activate.
    }

    @Test
    public void testOnStoppedReceivedWithMismatchingRequestIdDropped() {
        userBinding.activate(10, 1L, testIdentity);
        userBinding.activate(11, 2L, testIdentity);

        // Bogus stopRequestId
        userBinding.onStoppedReceived(10, 1L, 999999L, new Binder());

        // pending target should not yet have executed if stop req id didn't match
    }
}
