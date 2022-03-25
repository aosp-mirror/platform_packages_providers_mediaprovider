/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.metrics;

import static com.android.providers.media.MediaProviderStatsLog.GENERAL_EXTERNAL_STORAGE_ACCESS_STATS;
import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA;

import android.app.StatsManager;
import android.content.Context;
import android.util.Log;
import android.util.StatsEvent;

import androidx.annotation.NonNull;

import com.android.modules.utils.BackgroundThread;
import com.android.providers.media.fuse.FuseDaemon;

import java.util.List;

/** A class to initialise and log metrics pulled by statsd. */
public class PulledMetrics {
    private static final String TAG = "PulledMetrics";

    private static final StatsPullCallbackHandler STATS_PULL_CALLBACK_HANDLER =
            new StatsPullCallbackHandler();

    private static final StorageAccessMetrics storageAccessMetrics = new StorageAccessMetrics();

    private static boolean isInitialized = false;

    public static void initialize(Context context) {
        if (isInitialized) {
            return;
        }

        final StatsManager statsManager = context.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(TAG, "Error retrieving StatsManager. Cannot initialize PulledMetrics.");
        } else {
            Log.d(TAG, "Registering callback with StatsManager");

            try {
                // use the same callback handler for registering for all the tags.
                statsManager.setPullAtomCallback(TRANSCODING_DATA, null /* metadata */,
                        BackgroundThread.getExecutor(),
                        STATS_PULL_CALLBACK_HANDLER);
                statsManager.setPullAtomCallback(
                        GENERAL_EXTERNAL_STORAGE_ACCESS_STATS,
                        /*metadata*/null,
                        BackgroundThread.getExecutor(),
                        STATS_PULL_CALLBACK_HANDLER);
                isInitialized = true;
            } catch (NullPointerException e) {
                Log.w(TAG, "Pulled metrics not supported. Could not register.", e);
            }
        }
    }

    // Storage Access Metrics log functions

    /**
     * Logs the mime type that was accessed by the given {@code uid}.
     * Does nothing if the stats puller is not initialized.
     */
    public static void logMimeTypeAccess(int uid, @NonNull String mimeType) {
        if (!isInitialized) {
            return;
        }
        BackgroundThread.getExecutor().execute(() -> {
            storageAccessMetrics.logMimeType(uid, mimeType);
        });
    }

    /**
     * Logs the storage access and attributes it to the given {@code uid}.
     *
     * <p>This is a no-op if it's called from a non-FUSE thread.
     */
    public static void logFileAccessViaFuse(int uid, @NonNull String file) {
        if (!isInitialized) {
            return;
        }
        // Log only if it's a FUSE thread
        if (!FuseDaemon.native_is_fuse_thread()) {
            return;
        }
        BackgroundThread.getExecutor().execute(() -> {
            storageAccessMetrics.logAccessViaFuse(uid, file);
        });
    }

    /**
     * Logs the storage access and attributes it to the given {@code uid}.
     *
     * <p>This is a no-op if it's called on a FUSE thread.
     */
    public static void logVolumeAccessViaMediaProvider(int uid, @NonNull String volumeName) {
        if (!isInitialized) {
            return;
        }

        // We don't log if it's a FUSE thread because logAccessViaFuse should handle that.
        if (FuseDaemon.native_is_fuse_thread()) {
            return;
        }
        BackgroundThread.getExecutor().execute(() -> {
            storageAccessMetrics.logAccessViaMediaProvider(uid, volumeName);
        });
    }

    private static class StatsPullCallbackHandler implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            // handle the tags appropriately.
            List<StatsEvent> events = pullEvents(atomTag);
            if (events == null) {
                return StatsManager.PULL_SKIP;
            }

            data.addAll(events);
            return StatsManager.PULL_SUCCESS;
        }

        private List<StatsEvent> pullEvents(int atomTag) {
            switch (atomTag) {
                case TRANSCODING_DATA:
                    return TranscodeMetrics.pullStatsEvents();
                case GENERAL_EXTERNAL_STORAGE_ACCESS_STATS:
                    return storageAccessMetrics.pullStatsEvents();
                default:
                    return null;
            }
        }
    }
}
