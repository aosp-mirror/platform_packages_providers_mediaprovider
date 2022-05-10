/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_CONTENT_DELETED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_IDLE_MAINTENANCE_FINISHED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_DENIED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_GRANTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_OTHER;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__UNKNOWN;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCHEMA_CHANGED;
import static com.android.providers.media.scan.MediaScanner.REASON_DEMAND;
import static com.android.providers.media.scan.MediaScanner.REASON_IDLE;
import static com.android.providers.media.scan.MediaScanner.REASON_MOUNTED;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;

import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.android.providers.media.MediaProviderStatsLog;

/**
 * Class that emits common metrics to both remote and local endpoints to aid in
 * regression investigations and bug triage.
 */
public class Metrics {
    public static void logScan(@NonNull String volumeName, int reason, long itemCount,
            long durationMillis, int insertCount, int updateCount, int deleteCount) {
        Logging.logPersistent(String.format(
                "Scanned %s due to %s, found %d items in %dms, %d inserts %d updates %d deletes",
                volumeName, translateReason(reason), itemCount, durationMillis, insertCount,
                updateCount, deleteCount));

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;
        final float normalizedInsertCount = ((float) insertCount) / itemCount;
        final float normalizedUpdateCount = ((float) updateCount) / itemCount;
        final float normalizedDeleteCount = ((float) deleteCount) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCAN_OCCURRED,
                translateVolumeName(volumeName), reason, itemCount, normalizedDurationMillis,
                normalizedInsertCount, normalizedUpdateCount, normalizedDeleteCount);
    }

    /**
     * Logs persistent deletion logs on-device.
     */
    public static void logDeletionPersistent(@NonNull String volumeName, String reason,
            int[] countPerMediaType) {
        final StringBuilder builder = new StringBuilder("Deleted ");
        for (int count: countPerMediaType) {
            builder.append(count).append(' ');
        }
        builder.append("items on ")
                .append(volumeName)
                .append(" due to ")
                .append(reason);

        Logging.logPersistent(builder.toString());
    }

    /**
     * Logs persistent deletion logs on-device and stats metrics. Count of items per-media-type
     * are not uploaded to MediaProviderStats logs.
     */
    public static void logDeletion(@NonNull String volumeName, int uid, String packageName,
            int itemCount, int[] countPerMediaType) {
        logDeletionPersistent(volumeName, packageName, countPerMediaType);
        MediaProviderStatsLog.write(MEDIA_CONTENT_DELETED,
                translateVolumeName(volumeName), uid, itemCount);
    }

    public static void logPermissionGranted(@NonNull String volumeName, int uid, String packageName,
            int itemCount) {
        Logging.logPersistent(String.format(
                "Granted permission to %3$d items on %1$s to %2$s",
                volumeName, packageName, itemCount));

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_REQUESTED,
                translateVolumeName(volumeName), uid, itemCount,
                MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_GRANTED);
    }

    public static void logPermissionDenied(@NonNull String volumeName, int uid, String packageName,
            int itemCount) {
        Logging.logPersistent(String.format(
                "Denied permission to %3$d items on %1$s to %2$s",
                volumeName, packageName, itemCount));

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_REQUESTED,
                translateVolumeName(volumeName), uid, itemCount,
                MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_DENIED);
    }

    public static void logSchemaChange(@NonNull String volumeName, int versionFrom, int versionTo,
            long itemCount, long durationMillis, @NonNull String databaseUuid) {
        Logging.logPersistent(String.format(
                "Changed schema version on %s from %d to %d, %d items taking %dms UUID %s",
                volumeName, versionFrom, versionTo, itemCount, durationMillis, databaseUuid));

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCHEMA_CHANGED,
                translateVolumeName(volumeName), versionFrom, versionTo, itemCount,
                normalizedDurationMillis);
    }

    public static void logIdleMaintenance(@NonNull String volumeName, long itemCount,
            long durationMillis, int staleThumbnails, int expiredMedia) {
        Logging.logPersistent(String.format(
                "Idle maintenance on %s, %d items taking %dms, %d stale, %d expired",
                volumeName, itemCount, durationMillis, staleThumbnails, expiredMedia));

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;
        final float normalizedStaleThumbnails = ((float) staleThumbnails) / itemCount;
        final float normalizedExpiredMedia = ((float) expiredMedia) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_IDLE_MAINTENANCE_FINISHED,
                translateVolumeName(volumeName), itemCount, normalizedDurationMillis,
                normalizedStaleThumbnails, normalizedExpiredMedia);
    }

    public static String translateReason(int reason) {
        switch (reason) {
            case REASON_UNKNOWN: return "REASON_UNKNOWN";
            case REASON_MOUNTED: return "REASON_MOUNTED";
            case REASON_DEMAND: return "REASON_DEMAND";
            case REASON_IDLE: return "REASON_IDLE";
            default: return String.valueOf(reason);
        }
    }

    private static int translateVolumeName(@NonNull String volumeName) {
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__INTERNAL;
            case MediaStore.VOLUME_EXTERNAL:
                // Callers using generic "external" volume name end up applying
                // to all external volumes, so we can't tell which volumes were
                // actually changed
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__UNKNOWN;
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_PRIMARY;
            default:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_OTHER;
        }
    }
}
