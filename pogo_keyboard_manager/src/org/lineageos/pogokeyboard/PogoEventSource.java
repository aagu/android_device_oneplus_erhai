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
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.util.Log;
import android.view.InputDevice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Reads driver UEvent, InputManager device status, and proc status for Pogo keyboard attachment/detachment.
 * Matches pogo_keyboard or pogopin UEvents and monitors pogo_touchpad input device lifecycle.
 * Triggers snapshot request via sys.erhai.pogo.snapshot_request property with backoff.
 */
public class PogoEventSource {

    private static final String TAG = "PogoEventSource";

    public static final String TARGET_INPUT_DEVICE_NAME = "pogo_touchpad";
    public static final String MATCH_DEVNAME = "pogo_keyboard";
    public static final String MATCH_DEVPATH = "pogopin";

    public static final String PROP_SNAPSHOT_REQUEST = "sys.erhai.pogo.snapshot_request";
    public static final String PROC_STATUS_PATH = "/proc/pogopin/kbd_keypad_status";

    public static final long[] SNAPSHOT_RETRY_BACKOFF_MS = {1000L, 2000L, 4000L};

    private final Context context;
    private final Handler handler;
    private final File procStatusFile;
    private final AtomicInteger snapshotCounter = new AtomicInteger(1);

    private Consumer<KeyboardEvent> eventSink;
    private boolean observing = false;
    private int snapshotRetryCount = 0;
    private volatile boolean closed = false;
    private boolean snapshotPending = false;
    private final Runnable snapshotRetry = this::checkSnapshotOrRetry;

    private InputManager inputManager;
    private int pogoTouchpadDeviceId = -1;
    private volatile KeyboardIdentity lastKnownIdentity = null;

    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            if (closed || inputManager == null) return;
            InputDevice dev = inputManager.getInputDevice(deviceId);
            if (dev != null && TARGET_INPUT_DEVICE_NAME.equals(dev.getName())) {
                Log.i(TAG, "Input device added: " + TARGET_INPUT_DEVICE_NAME + " (id=" + deviceId + ")");
                pogoTouchpadDeviceId = deviceId;
                requestSnapshot();
            }
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            if (closed) return;
            if (deviceId == pogoTouchpadDeviceId) {
                Log.i(TAG, "Input device removed: " + TARGET_INPUT_DEVICE_NAME + " (id=" + deviceId + ")");
                pogoTouchpadDeviceId = -1;
                KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                        .targetIdentity(lastKnownIdentity)
                        .timestampMs(System.currentTimeMillis())
                        .build();
                dispatchSink(event);
            }
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {}
    };

    private final UEventObserver uEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            if (closed) return;
            handler.post(() -> {
                if (!closed) handleRawUEvent(event);
            });
        }
    };

    public PogoEventSource(Handler handler) {
        this(null, handler, new File(PROC_STATUS_PATH));
    }

    public PogoEventSource(Context context, Handler handler) {
        this(context, handler, new File(PROC_STATUS_PATH));
    }

    public PogoEventSource(Handler handler, File procStatusFile) {
        this(null, handler, procStatusFile);
    }

    public PogoEventSource(Context context, Handler handler, File procStatusFile) {
        this.context = context;
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.procStatusFile = Objects.requireNonNull(procStatusFile, "procStatusFile must not be null");
    }

    /**
     * Starts listening for UEvents, registers InputDeviceListener, and registers sink.
     */
    public synchronized void start(Consumer<KeyboardEvent> sink) {
        if (closed) return;
        this.eventSink = Objects.requireNonNull(sink, "sink must not be null");

        if (context != null) {
            inputManager = context.getSystemService(InputManager.class);
            if (inputManager != null) {
                inputManager.registerInputDeviceListener(inputDeviceListener, handler);
                // Check if pogo_touchpad is already present on start
                int[] ids = inputManager.getInputDeviceIds();
                if (ids != null) {
                    for (int id : ids) {
                        InputDevice dev = inputManager.getInputDevice(id);
                        if (dev != null && TARGET_INPUT_DEVICE_NAME.equals(dev.getName())) {
                            pogoTouchpadDeviceId = id;
                            Log.i(TAG, "Initial physical pogo device present: " + TARGET_INPUT_DEVICE_NAME + " (id=" + id + ")");
                            break;
                        }
                    }
                }
            }
        }

        if (!observing) {
            uEventObserver.startObserving(MATCH_DEVNAME);
            uEventObserver.startObserving(MATCH_DEVPATH);
            observing = true;
            Log.i(TAG, "Started observing UEvents for pogo keyboard");
        }
    }

    /**
     * Triggers snapshot request via system property sys.erhai.pogo.snapshot_request.
     * Retries according to backoff budget (1s, 2s, 4s).
     */
    public synchronized void requestSnapshot() {
        if (closed || snapshotPending) return;
        snapshotPending = true;
        snapshotRetryCount = 0;

        // Try reading proc status directly first if available
        Optional<KeyboardEvent> eventOpt = tryReadProcStatus();
        if (eventOpt.isPresent()) {
            Log.i(TAG, "Read initial snapshot directly from proc status");
            dispatchSink(eventOpt.get());
            return;
        }

        // Property-based snapshot request to init
        triggerPropertySnapshot();
    }

    private Optional<KeyboardEvent> tryReadProcStatus() {
        if (procStatusFile == null || PROC_STATUS_PATH.equals(procStatusFile.getPath())) {
            return Optional.empty();
        }
        try {
            if (procStatusFile.canRead()) {
                return readProcStatus();
            }
        } catch (SecurityException ignored) {
            // DAC/SELinux restricted, proceed via init snapshot property
        }
        return Optional.empty();
    }

    private void triggerPropertySnapshot() {
        int nextSeq = snapshotCounter.getAndIncrement();
        Log.i(TAG, "Setting snapshot request property: " + PROP_SNAPSHOT_REQUEST + "=" + nextSeq);
        try {
            SystemProperties.set(PROP_SNAPSHOT_REQUEST, String.valueOf(nextSeq));
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to set snapshot request property", e);
        }

        // Schedule check / retry if proc node doesn't yield an event
        if (snapshotRetryCount < SNAPSHOT_RETRY_BACKOFF_MS.length) {
            long delay = SNAPSHOT_RETRY_BACKOFF_MS[snapshotRetryCount];
            snapshotRetryCount++;
            handler.postDelayed(snapshotRetry, delay);
        } else {
            snapshotPending = false;
            Log.w(TAG, "Snapshot request retries exhausted");
        }
    }

    private synchronized void checkSnapshotOrRetry() {
        if (closed || !snapshotPending) return;
        Optional<KeyboardEvent> eventOpt = tryReadProcStatus();
        if (eventOpt.isPresent()) {
            Log.i(TAG, "Snapshot obtained on retry " + snapshotRetryCount);
            dispatchSink(eventOpt.get());
            return;
        }

        // If still not available and within budget, retry
        triggerPropertySnapshot();
    }

    /**
     * Parses raw UEvent and dispatches KeyboardEvent to sink if valid.
     */
    public void handleRawUEvent(UEventObserver.UEvent event) {
        if (event == null) return;
        String status = event.get("pogopin_status");
        if (status == null) {
            status = event.get("STATUS");
        }
        String mac = event.get("mac_addr");
        if (mac == null) {
            mac = event.get("MAC_ADDR");
        }
        String bleName = event.get("keyboard_ble_name");
        String reportSn = event.get("report_sn");

        processEventPayload(status, mac, bleName, reportSn);
    }

    public synchronized void processEventPayload(String status, String mac, String bleName, String reportSn) {
        if (status == null) {
            return;
        }

        boolean attached = "1".equals(status.trim()) || "attached".equalsIgnoreCase(status.trim()) || "ATTACHED".equalsIgnoreCase(status.trim());
        boolean detached = "0".equals(status.trim()) || "detached".equalsIgnoreCase(status.trim()) || "DETACHED".equalsIgnoreCase(status.trim());

        if (attached) {
            if (mac == null || mac.trim().isEmpty()) {
                Log.w(TAG, "ATTACHED event received without mac_addr, ignoring");
                return;
            }
            Optional<KeyboardIdentity> identityOpt = KeyboardIdentity.parseDriverAddress(mac, bleName, reportSn);
            if (!identityOpt.isPresent()) {
                Log.w(TAG, "Failed to parse mac_addr in ATTACHED event: " + mac);
                return;
            }

            KeyboardIdentity identity = identityOpt.get();
            this.lastKnownIdentity = identity;
            KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                    .targetIdentity(identity)
                    .timestampMs(System.currentTimeMillis())
                    .build();
            dispatchSink(event);
        } else if (detached) {
            KeyboardIdentity identity = null;
            if (mac != null && !mac.trim().isEmpty()) {
                identity = KeyboardIdentity.parseDriverAddress(mac, bleName, reportSn).orElse(null);
            }
            if (identity != null) {
                this.lastKnownIdentity = identity;
            } else if (lastKnownIdentity != null) {
                identity = lastKnownIdentity;
            }
            KeyboardEvent event = KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                    .targetIdentity(identity)
                    .timestampMs(System.currentTimeMillis())
                    .build();
            dispatchSink(event);
        }
    }

    private Optional<KeyboardEvent> readProcStatus() {
        try (FileInputStream in = new FileInputStream(procStatusFile)) {
            byte[] buf = in.readAllBytes();
            if (buf == null || buf.length == 0) {
                return Optional.empty();
            }
            String content = new String(buf, StandardCharsets.UTF_8).trim();
            // Expected content format: e.g. "pogopin_status=1\nmac_addr=... " or simple "1" / "0"
            String status = null;
            String mac = null;
            String bleName = null;
            String reportSn = null;

            String[] lines = content.split("\n");
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ("pogopin_status".equalsIgnoreCase(k)) status = v;
                    else if ("mac_addr".equalsIgnoreCase(k)) mac = v;
                    else if ("keyboard_ble_name".equalsIgnoreCase(k)) bleName = v;
                    else if ("report_sn".equalsIgnoreCase(k)) reportSn = v;
                }
            }
            if (status == null && (content.equals("1") || content.equals("0"))) {
                status = content;
            }

            if (status != null) {
                boolean attached = "1".equals(status);
                if (attached && mac != null) {
                    Optional<KeyboardIdentity> identityOpt = KeyboardIdentity.parseDriverAddress(mac, bleName, reportSn);
                    if (identityOpt.isPresent()) {
                        KeyboardIdentity id = identityOpt.get();
                        this.lastKnownIdentity = id;
                        return Optional.of(KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_ATTACHED)
                                .targetIdentity(id)
                                .timestampMs(System.currentTimeMillis())
                                .build());
                    }
                } else if (!attached) {
                    return Optional.of(KeyboardEvent.builder(KeyboardEvent.Type.UEVENT_DETACHED)
                            .targetIdentity(lastKnownIdentity)
                            .timestampMs(System.currentTimeMillis())
                            .build());
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Cannot read proc status file: " + e.getMessage());
        }
        return Optional.empty();
    }

    private synchronized void dispatchSink(KeyboardEvent event) {
        snapshotPending = false;
        handler.removeCallbacks(snapshotRetry);
        handler.post(() -> {
            if (closed || eventSink == null) return;
            eventSink.accept(event);
        });
    }

    public synchronized void close() {
        closed = true;
        snapshotPending = false;
        handler.removeCallbacks(snapshotRetry);
        if (observing) {
            uEventObserver.stopObserving();
            observing = false;
        }
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
            inputManager = null;
        }
        eventSink = null;
    }
}
