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

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_DELETION_EVENT;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_IDLE_MAINTENANCE;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_EVENT;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_EVENT__RESULT__USER_DENIED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_EVENT__RESULT__USER_GRANTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__EXTERNAL_OTHER;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__UNKNOWN;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCHEMA_CHANGE;
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

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCAN_EVENT,
                translateVolumeName(volumeName), reason, itemCount, normalizedDurationMillis,
                normalizedInsertCount, normalizedUpdateCount, normalizedDeleteCount);
    }

    public static void logDeletion(@NonNull String volumeName, long timestampMillis,
            String packageName, int itemCount) {
        Logging.logPersistent(String.format(
                "Deleted %4$d items on %1$s due to %3$s",
                volumeName, timestampMillis, packageName, itemCount));

        MediaProviderStatsLog.write(MEDIA_PROVIDER_DELETION_EVENT,
                translateVolumeName(volumeName), timestampMillis, packageName, itemCount);
    }

    public static void logPermissionGranted(@NonNull String volumeName, long timestampMillis,
            String packageName, int itemCount) {
        Logging.logPersistent(String.format(
                "Granted permission to %4$d items on %1$s to %3$s",
                volumeName, timestampMillis, packageName, itemCount));

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_EVENT,
                translateVolumeName(volumeName), timestampMillis, packageName, itemCount,
                MEDIA_PROVIDER_PERMISSION_EVENT__RESULT__USER_GRANTED);
    }

    public static void logPermissionDenied(@NonNull String volumeName, long timestampMillis,
            String packageName, int itemCount) {
        Logging.logPersistent(String.format(
                "Denied permission to %4$d items on %1$s to %3$s",
                volumeName, timestampMillis, packageName, itemCount));

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_EVENT,
                translateVolumeName(volumeName), timestampMillis, packageName, itemCount,
                MEDIA_PROVIDER_PERMISSION_EVENT__RESULT__USER_DENIED);
    }

    public static void logSchemaChange(@NonNull String volumeName, int versionFrom, int versionTo,
            long itemCount, long durationMillis) {
        Logging.logPersistent(String.format(
                "Changed schema version on %s from %d to %d, %d items taking %dms",
                volumeName, versionFrom, versionTo, itemCount, durationMillis));

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCHEMA_CHANGE,
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

        MediaProviderStatsLog.write(MEDIA_PROVIDER_IDLE_MAINTENANCE,
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
                return MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__INTERNAL;
            case MediaStore.VOLUME_EXTERNAL:
                // Callers using generic "external" volume name end up applying
                // to all external volumes, so we can't tell which volumes were
                // actually changed
                return MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__UNKNOWN;
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__EXTERNAL_PRIMARY;
            default:
                return MEDIA_PROVIDER_SCAN_EVENT__VOLUME_TYPE__EXTERNAL_OTHER;
        }
    }
}
