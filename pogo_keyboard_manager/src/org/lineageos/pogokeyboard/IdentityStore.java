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

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages atomic persistence for keyboard TargetRecord in device protected storage.
 * Reads and writes are atomic using android.util.AtomicFile.
 * Any write failure or corruption suppresses auto pairing.
 */
public class IdentityStore {

    private static final String TAG = "IdentityStore";
    private static final String RECORD_FILENAME = "pogo_keyboard_target.json";

    private final AtomicFile atomicFile;

    public IdentityStore(Context context) {
        Objects.requireNonNull(context, "context must not be null");
        Context dpsContext = context.isDeviceProtectedStorage()
                ? context
                : context.createDeviceProtectedStorageContext();
        File storageDir = dpsContext.getFilesDir();
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
        File targetFile = new File(storageDir, RECORD_FILENAME);
        this.atomicFile = new AtomicFile(targetFile);
    }

    public IdentityStore(File baseFile) {
        Objects.requireNonNull(baseFile, "baseFile must not be null");
        if (baseFile.getParentFile() != null && !baseFile.getParentFile().exists()) {
            baseFile.getParentFile().mkdirs();
        }
        this.atomicFile = new AtomicFile(baseFile);
    }

    /**
     * Atomically loads the persisted TargetRecord.
     * If the file does not exist, returns Optional.empty().
     * If the file is corrupted or cannot be read, throws an IOException.
     */
    public synchronized Optional<TargetRecord> load() throws IOException {
        if (!atomicFile.getBaseFile().exists()) {
            return Optional.empty();
        }

        try (FileInputStream in = atomicFile.openRead()) {
            byte[] bytes = in.readAllBytes();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(jsonStr);

            String rawAddress = obj.getString("rawAddress");
            String formattedAddress = obj.getString("formattedAddress");
            String bleName = obj.optString("bleName", "");
            String reportSn = obj.optString("reportSn", "");
            KeyboardIdentity identity = new KeyboardIdentity(rawAddress, formattedAddress, bleName, reportSn);

            boolean autoPairSuppressed = obj.getBoolean("autoPairSuppressed");
            String suppressionReason = obj.optString("suppressionReason", "");
            boolean lastKnownBonded = obj.getBoolean("lastKnownBonded");
            long recordVersion = obj.getLong("recordVersion");

            return Optional.of(new TargetRecord(identity, autoPairSuppressed, suppressionReason, lastKnownBonded, recordVersion));
        } catch (JSONException | RuntimeException e) {
            Log.e(TAG, "Corrupted TargetRecord file, cannot parse JSON", e);
            throw new IOException("Failed to parse persisted TargetRecord", e);
        }
    }

    /**
     * Atomically saves the TargetRecord.
     */
    public synchronized void save(TargetRecord record) throws IOException {
        Objects.requireNonNull(record, "record must not be null");
        JSONObject obj = new JSONObject();
        try {
            KeyboardIdentity id = record.getIdentity();
            obj.put("rawAddress", id.getRawAddress());
            obj.put("formattedAddress", id.getFormattedAddress());
            obj.put("bleName", id.getBleName());
            obj.put("reportSn", id.getReportSn());
            obj.put("autoPairSuppressed", record.isAutoPairSuppressed());
            obj.put("suppressionReason", record.getSuppressionReason());
            obj.put("lastKnownBonded", record.isLastKnownBonded());
            obj.put("recordVersion", record.getRecordVersion());
        } catch (JSONException e) {
            throw new IOException("Failed to serialize TargetRecord to JSON", e);
        }

        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream fos = null;
        try {
            fos = atomicFile.startWrite();
            fos.write(bytes);
            atomicFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                atomicFile.failWrite(fos);
            }
            Log.e(TAG, "Failed to write TargetRecord atomically", e);
            throw e;
        }
    }

    /**
     * Clears persisted TargetRecord.
     */
    public synchronized void clear() {
        atomicFile.delete();
    }
}
