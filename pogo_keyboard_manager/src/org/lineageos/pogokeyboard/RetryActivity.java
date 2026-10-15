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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import org.lineageos.pogokeyboard.aidl.IKeyboardCoordinator;

/**
 * User-facing explicit retry activity.
 * Initiated only by direct user interaction to clear auto-pairing suppression.
 */
public class RetryActivity extends Activity {

    private static final String TAG = "RetryActivity";

    public static final String ACTION_RETRY = "org.lineageos.pogokeyboard.action.RETRY";
    public static final String EXTRA_TARGET_IDENTITY = "extra_target_identity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "RetryActivity launched in user " + getUserId());

        KeyboardIdentity targetIdentity = getIntent().getParcelableExtra(EXTRA_TARGET_IDENTITY, KeyboardIdentity.class);
        if (targetIdentity == null) {
            Log.w(TAG, "No target identity provided for retry");
            finish();
            return;
        }

        // Connect to system user coordinator via explicit signature-protected binding
        Intent serviceIntent = new Intent(this, PogoKeyboardService.class);
        bindServiceAsUser(serviceIntent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IKeyboardCoordinator coordinator = IKeyboardCoordinator.Stub.asInterface(service);
                    Log.i(TAG, "Requesting retry for target: " + targetIdentity.getAnonymizedAddress());
                    coordinator.requestRetry(targetIdentity);
                    Toast.makeText(RetryActivity.this, "Retrying keyboard connection...", Toast.LENGTH_SHORT).show();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call requestRetry on coordinator", e);
                } finally {
                    unbindService(this);
                    finish();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                finish();
            }
        }, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }
}
