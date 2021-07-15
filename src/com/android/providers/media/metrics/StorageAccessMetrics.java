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

import static java.util.stream.Collectors.toList;

import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaProviderStatsLog;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.MimeUtils;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Metrics for {@link MediaProviderStatsLog#GENERAL_EXTERNAL_STORAGE_ACCESS_STATS}. This class
 * gathers stats separately for each UID that accesses external storage.
 */
class StorageAccessMetrics {

    private static final String TAG = "StorageAccessMetrics";

    @VisibleForTesting
    static final int UID_SAMPLES_COUNT_LIMIT = 50;

    private final int mMyUid = Process.myUid();

    @GuardedBy("mLock")
    private final SparseArray<PackageStorageAccessStats> mAccessStatsPerPackage =
            new SparseArray<>();
    @GuardedBy("mLock")
    private long mStartTimeMillis = SystemClock.uptimeMillis();

    private final Object mLock = new Object();


    /**
     * Logs the mime type that was accessed by the given {@code uid}.
     */
    void logMimeType(int uid, @NonNull String mimeType) {
        if (mimeType == null) {
            Log.w(TAG, "Attempted to log null mime type access");
            return;
        }

        synchronized (mLock) {
            getOrGeneratePackageStatsObjectLocked(uid).mMimeTypes.add(mimeType);
        }
    }

    /**
     * Logs the storage access and attributes it to the given {@code uid}.
     *
     * <p>Should only be called from a FUSE thread.
     */
    void logAccessViaFuse(int uid, @NonNull String file) {
        // We don't log the access if it's MediaProvider accessing.
        if (mMyUid == uid) {
            return;
        }

        incrementFilePathAccesses(uid);
        final String volumeName = MediaStore.getVolumeName(
                FileUtils.getContentUriForPath(file));
        logGeneralExternalStorageAccess(uid, volumeName);
        logMimeTypeFromFile(uid, file);
    }


    /**
     * Logs the storage access and attributes it to the given {@code uid}.
     */
    void logAccessViaMediaProvider(int uid, @NonNull String volumeName) {
        // We also don't log the access if it's MediaProvider accessing.
        if (mMyUid == uid) {
            return;
        }

        logGeneralExternalStorageAccess(uid, volumeName);
    }

    /**
     * Use this to log whenever a package accesses external storage via ContentResolver or FUSE.
     * The given volume name helps us determine whether this was an access on primary or secondary
     * storage.
     */
    private void logGeneralExternalStorageAccess(int uid, @NonNull String volumeName) {
        switch (volumeName) {
            case MediaStore.VOLUME_EXTERNAL:
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                incrementTotalAccesses(uid);
                break;
            case MediaStore.VOLUME_INTERNAL:
            case MediaStore.VOLUME_DEMO:
            case MediaStore.MEDIA_SCANNER_VOLUME:
                break;
            default:
                // Secondary external storage
                incrementTotalAccesses(uid);
                incrementSecondaryStorageAccesses(uid);
        }
    }

    /**
     * Logs that the mime type of the given {@param file} was accessed by the given {@param uid}.
     */
    private void logMimeTypeFromFile(int uid, @Nullable String file) {
        logMimeType(uid, MimeUtils.resolveMimeType(new File(file)));
    }

    private void incrementTotalAccesses(int uid) {
        synchronized (mLock) {
            getOrGeneratePackageStatsObjectLocked(uid).mTotalAccesses += 1;
        }
    }

    private void incrementFilePathAccesses(int uid) {
        synchronized (mLock) {
            getOrGeneratePackageStatsObjectLocked(uid).mFilePathAccesses += 1;
        }
    }

    private void incrementSecondaryStorageAccesses(int uid) {
        synchronized (mLock) {
            getOrGeneratePackageStatsObjectLocked(uid).mSecondaryStorageAccesses += 1;
        }
    }

    @GuardedBy("mLock")
    private PackageStorageAccessStats getOrGeneratePackageStatsObjectLocked(int uid) {
        PackageStorageAccessStats stats = mAccessStatsPerPackage.get(uid);
        if (stats == null) {
            stats = new PackageStorageAccessStats(uid);
            mAccessStatsPerPackage.put(uid, stats);
        }
        return stats;
    }

    /**
     * Returns the list of {@link StatsEvent} since latest reset, for a random subset of tracked
     * uids if there are more than {@link #UID_SAMPLES_COUNT_LIMIT} in total. Returns {@code null}
     * when the time since reset is non-positive.
     */
    @Nullable
    List<StatsEvent> pullStatsEvents() {
        synchronized (mLock) {
            final long timeInterval = SystemClock.uptimeMillis() - mStartTimeMillis;
            List<PackageStorageAccessStats> stats = getSampleStats();
            resetStats();
            return stats
                    .stream()
                    .map(s -> s.toNormalizedStats(timeInterval).toStatsEvent())
                    .collect(toList());
        }
    }

    @VisibleForTesting
    List<PackageStorageAccessStats> getSampleStats() {
        synchronized (mLock) {
            List<PackageStorageAccessStats> result = new ArrayList<>();

            List<Integer> sampledUids = new ArrayList<>();
            for (int i = 0; i < mAccessStatsPerPackage.size(); i++) {
                sampledUids.add(mAccessStatsPerPackage.keyAt(i));
            }

            if (sampledUids.size() > UID_SAMPLES_COUNT_LIMIT) {
                Collections.shuffle(sampledUids);
                sampledUids = sampledUids.subList(0, UID_SAMPLES_COUNT_LIMIT);
            }
            for (Integer uid : sampledUids) {
                PackageStorageAccessStats stats = mAccessStatsPerPackage.get(uid);
                result.add(stats);
            }

            return result;
        }
    }

    private void resetStats() {
        synchronized (mLock) {
            mAccessStatsPerPackage.clear();
            mStartTimeMillis = SystemClock.uptimeMillis();
        }
    }

    @VisibleForTesting
    static class PackageStorageAccessStats {
        private final int mUid;
        int mTotalAccesses = 0;
        int mFilePathAccesses = 0;
        int mSecondaryStorageAccesses = 0;

        final ArraySet<String> mMimeTypes = new ArraySet<>();

        PackageStorageAccessStats(int uid) {
            this.mUid = uid;
        }

        PackageStorageAccessStats toNormalizedStats(long timeInterval) {
            this.mTotalAccesses = normalizeAccessesPerDay(mTotalAccesses, timeInterval);
            this.mFilePathAccesses = normalizeAccessesPerDay(mFilePathAccesses, timeInterval);
            this.mSecondaryStorageAccesses =
                    normalizeAccessesPerDay(mSecondaryStorageAccesses, timeInterval);
            return this;
        }

        StatsEvent toStatsEvent() {
            return StatsEvent.newBuilder()
                    .setAtomId(GENERAL_EXTERNAL_STORAGE_ACCESS_STATS)
                    .writeInt(mUid)
                    .writeInt(mTotalAccesses)
                    .writeInt(mFilePathAccesses)
                    .writeInt(mSecondaryStorageAccesses)
                    .writeByteArray(getMimeTypesAsProto().getBytes())
                    .build();
        }

        private ProtoOutputStream getMimeTypesAsProto() {
            ProtoOutputStream proto = new ProtoOutputStream();
            for (int i = 0; i < mMimeTypes.size(); i++) {
                String mime = mMimeTypes.valueAt(i);
                proto.write(/*fieldId*/ProtoOutputStream.FIELD_TYPE_STRING
                                | ProtoOutputStream.FIELD_COUNT_REPEATED
                                | 1,
                        mime);
            }
            return proto;
        }

        private static int normalizeAccessesPerDay(int value, long interval) {
            if (interval <= 0) {
                return -1;
            }

            double multiplier = Double.valueOf(TimeUnit.DAYS.toMillis(1)) / interval;
            double normalizedValue = value * multiplier;
            return Double.valueOf(normalizedValue).intValue();
        }

        @VisibleForTesting
        int getUid() {
            return mUid;
        }
    }
}
