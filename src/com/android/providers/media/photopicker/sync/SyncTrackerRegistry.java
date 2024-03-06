/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.sync;

import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_CLOUD_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_AND_CLOUD;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.UUID;

/**
 * This class stores all sync trackers.
 */
public class SyncTrackerRegistry {
    private static SyncTracker sLocalSyncTracker = new SyncTracker();
    private static SyncTracker sLocalAlbumSyncTracker = new SyncTracker();
    private static SyncTracker sCloudSyncTracker = new SyncTracker();
    private static SyncTracker sCloudAlbumSyncTracker = new SyncTracker();

    public static SyncTracker getLocalSyncTracker() {
        return sLocalSyncTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setLocalSyncTracker(SyncTracker syncTracker) {
        sLocalSyncTracker = syncTracker;
    }

    public static SyncTracker getLocalAlbumSyncTracker() {
        return sLocalAlbumSyncTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setLocalAlbumSyncTracker(
            SyncTracker localAlbumSyncTracker) {
        sLocalAlbumSyncTracker = localAlbumSyncTracker;
    }

    public static SyncTracker getCloudSyncTracker() {
        return sCloudSyncTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setCloudSyncTracker(
            SyncTracker cloudSyncTracker) {
        sCloudSyncTracker = cloudSyncTracker;
    }

    public static SyncTracker getCloudAlbumSyncTracker() {
        return sCloudAlbumSyncTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setCloudAlbumSyncTracker(
            SyncTracker cloudAlbumSyncTracker) {
        sCloudAlbumSyncTracker = cloudAlbumSyncTracker;
    }

    /**
     * Return the appropriate sync tracker.
     * @param isLocal is true when sync with local provider needs to be tracked. It is false for
     *                sync with cloud provider.
     * @return the appropriate {@link SyncTracker} object.
     */
    public static SyncTracker getSyncTracker(boolean isLocal) {
        if (isLocal) {
            return sLocalSyncTracker;
        } else {
            return sCloudSyncTracker;
        }
    }

    /**
     * Return the appropriate album sync tracker.
     * @param isLocal is true when sync with local provider needs to be tracked. It is false for
     *                sync with cloud provider.
     * @return the appropriate {@link SyncTracker} object.
     */
    public static SyncTracker getAlbumSyncTracker(boolean isLocal) {
        if (isLocal) {
            return sLocalAlbumSyncTracker;
        } else {
            return sCloudAlbumSyncTracker;
        }
    }

    /**
     * Create the required completable futures for new media sync requests that need to be tracked.
     */
    public static void trackNewSyncRequests(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalSyncTracker().createSyncFuture(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudSyncTracker().createSyncFuture(syncRequestId);
        }
    }

    /**
     * Create the required completable futures for new album media sync requests that need to be
     * tracked.
     */
    public static void trackNewAlbumMediaSyncRequests(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalAlbumSyncTracker().createSyncFuture(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudAlbumSyncTracker().createSyncFuture(syncRequestId);
        }
    }

    /**
     * Mark the required futures as complete for existing media sync requests.
     */
    public static void markSyncAsComplete(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalSyncTracker().markSyncCompleted(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudSyncTracker().markSyncCompleted(syncRequestId);
        }
    }

    /**
     * Mark the required futures as complete for existing album media sync requests.
     */
    public static void markAlbumMediaSyncAsComplete(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalAlbumSyncTracker().markSyncCompleted(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudAlbumSyncTracker().markSyncCompleted(syncRequestId);
        }
    }
}
