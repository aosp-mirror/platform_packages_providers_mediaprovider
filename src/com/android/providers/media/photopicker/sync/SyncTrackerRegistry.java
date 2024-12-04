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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_MEDIA_GRANTS;

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
    private static SyncTracker sGrantsSyncTracker = new SyncTracker();
    private static SyncTracker sLocalSearchTracker = new SyncTracker();
    private static SyncTracker sCloudSearchTracker = new SyncTracker();
    private static SyncTracker sCloudMediaSetsSyncTracker = new SyncTracker();
    private static SyncTracker sLocalMediaSetsSyncTracker = new SyncTracker();
    private static SyncTracker sCloudMediaInMediaSetTracker = new SyncTracker();
    private static SyncTracker sLocalMediaInMediaSetTracker = new SyncTracker();

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

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setGrantsSyncTracker(
            SyncTracker grantsSyncTracker) {
        sGrantsSyncTracker = grantsSyncTracker;
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

    public static SyncTracker getGrantsSyncTracker() {
        return sGrantsSyncTracker;
    }

    public static SyncTracker getLocalSearchSyncTracker() {
        return sLocalSearchTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setLocalSearchSyncTracker(
            SyncTracker localSearchSyncTracker) {
        sLocalSearchTracker = localSearchSyncTracker;
    }

    public static SyncTracker getCloudSearchSyncTracker() {
        return sCloudSearchTracker;
    }

    /**
     * This setter is required to inject mock data for tests. Do not use this anywhere else.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setCloudSearchSyncTracker(
            SyncTracker cloudSearchSyncTracker) {
        sCloudSearchTracker = cloudSearchSyncTracker;
    }


    public static SyncTracker getCloudMediaSetsSyncTracker() {
        return sCloudMediaSetsSyncTracker;
    }

    public static SyncTracker getLocalMediaSetsSyncTracker() {
        return sLocalMediaSetsSyncTracker;
    }


    /*
    Required for testing. Not to be used anywhere else
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setCloudMediaSetsSyncTracker(SyncTracker cloudMediaSetsSyncTracker) {
        sCloudMediaSetsSyncTracker = cloudMediaSetsSyncTracker;
    }

    /*
    Required for testing. Not to be used anywhere else
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setLocalMediaSetsSyncTracker(SyncTracker localMediaSetsSyncTracker) {
        sLocalMediaSetsSyncTracker = localMediaSetsSyncTracker;
    }

    public static SyncTracker getCloudMediaInMediaSetTracker() {
        return sCloudMediaInMediaSetTracker;
    }

    public static SyncTracker getLocalMediaInMediaSetTracker() {
        return sLocalMediaInMediaSetTracker;
    }

    /*
     Only to be used for tests and nowhere else
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setCloudMediaInMediaSetTracker(
            SyncTracker cloudMediaInMediaSetTracker) {
        sCloudMediaInMediaSetTracker = cloudMediaInMediaSetTracker;
    }

    /*
    Only to be used for tests and nowhere else
    */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void setLocalMediaInMediaSetTracker(
            SyncTracker localMediaInMediaSetTracker) {
        sLocalMediaInMediaSetTracker = localMediaInMediaSetTracker;
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
        if (syncSource == SYNC_MEDIA_GRANTS) {
            getGrantsSyncTracker().createSyncFuture(syncRequestId);
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
     * Create the required completable futures for new search result sync requests that need to be
     * tracked.
     */
    public static void trackNewSearchResultsSyncRequests(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        switch (syncSource) {
            case SYNC_LOCAL_ONLY:
                getLocalSearchSyncTracker().createSyncFuture(syncRequestId);
                break;
            case SYNC_CLOUD_ONLY:
                getCloudSearchSyncTracker().createSyncFuture(syncRequestId);
                break;
            default:
                getLocalSearchSyncTracker().createSyncFuture(syncRequestId);
                getCloudSearchSyncTracker().createSyncFuture(syncRequestId);
                break;
        }
    }

    /**
     * Create the required completable futures to track a new media sets sync request
     */
    public static void trackNewMediaSetsSyncRequest(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        switch (syncSource) {
            case SYNC_LOCAL_ONLY:
                getLocalMediaSetsSyncTracker().createSyncFuture(syncRequestId);
                break;
            case SYNC_CLOUD_ONLY:
                getCloudMediaSetsSyncTracker().createSyncFuture(syncRequestId);
                break;
            default:
                getLocalMediaSetsSyncTracker().createSyncFuture(syncRequestId);
                getCloudMediaSetsSyncTracker().createSyncFuture(syncRequestId);
                break;
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
        if (syncSource == SYNC_MEDIA_GRANTS) {
            getGrantsSyncTracker().markSyncCompleted(syncRequestId);
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

    /**
     * Mark the required futures as complete for existing search result sync requests.
     */
    public static void markSearchResultsSyncAsComplete(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalSearchSyncTracker().markSyncCompleted(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudSearchSyncTracker().markSyncCompleted(syncRequestId);
        }
    }

    /**
     * Mark the required futures as complete for existing media set sync requests.
     */
    public static void markMediaSetsSyncAsComplete(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalMediaSetsSyncTracker().markSyncCompleted(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudMediaSetsSyncTracker().markSyncCompleted(syncRequestId);
        }
    }

    /**
     * Mark the required futures as complete for existing media in media set sync requests.
     */
    public static void markMediaInMediaSetSyncAsComplete(
            @PickerSyncManager.SyncSource int syncSource,
            @NonNull UUID syncRequestId) {
        if (syncSource == SYNC_LOCAL_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getLocalMediaInMediaSetTracker().markSyncCompleted(syncRequestId);
        }
        if (syncSource == SYNC_CLOUD_ONLY || syncSource == SYNC_LOCAL_AND_CLOUD) {
            getCloudMediaInMediaSetTracker().markSyncCompleted(syncRequestId);
        }
    }
}
