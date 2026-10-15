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

import android.app.Application;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;

/**
 * Application class for PogoKeyboardManager.
 * DirectBootAware, starts PogoKeyboardService only for User 0 (SYSTEM).
 */
public class PogoKeyboardApplication extends Application {

    private static final String TAG = "PogoKeyboardApp";

    @Override
    public void onCreate() {
        super.onCreate();
        int userId = getUserId();
        Log.i(TAG, "Application onCreate in user " + userId);

        if (userId == UserHandle.USER_SYSTEM) {
            Log.i(TAG, "Starting PogoKeyboardService for system user");
            Intent serviceIntent = new Intent(this, PogoKeyboardService.class);
            startService(serviceIntent);
        } else {
            Log.i(TAG, "Non-system user " + userId + ", coordinator loop not started");
        }
    }
}
