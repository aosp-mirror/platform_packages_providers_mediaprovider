/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markMediaInMediaSetSyncAsComplete;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.sqlite.MediaInMediaSetsDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.MediaSetsDatabaseUtil;

import java.util.List;
import java.util.Objects;

/**
 * This is a {@link Worker} class responsible for syncing the media items of a media set with the
 * correct sync source.
 */
public class MediaInMediaSetsSyncWorker extends Worker {

    private static final String TAG = "SearchSyncWorker";
    private static final int SYNC_PAGE_COUNT = Integer.MAX_VALUE;
    private static final int PAGE_SIZE = 500;
    private static final int INVALID_SYNC_SOURCE = -1;
    @VisibleForTesting
    public static final String SYNC_COMPLETE_RESUME_KEY = "SYNCED";
    private final Context mContext;
    private final CancellationSignal mCancellationSignal;
    private final SQLiteDatabase mDatabase;

    public MediaInMediaSetsSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        mContext = context;
        mCancellationSignal = new CancellationSignal();
        mDatabase = getDatabase();
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        final int syncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE,
                /* defaultValue */ INVALID_SYNC_SOURCE);
        String mediaSetAuthority = getInputData().getString(SYNC_WORKER_INPUT_AUTHORITY);
        String mediaSetPickerId = getInputData().getString(SYNC_WORKER_INPUT_MEDIA_SET_PICKER_ID);
        String mediaSetId = "";

        try {

            Pair<String, String[]> pair = MediaSetsDatabaseUtil
                    .getMediaSetIdAndMimeType(mDatabase, mediaSetPickerId);
            mediaSetId = pair.first;
            String[] mimeTypes = pair.second;

            if (getRunAttemptCount() > 0) {
                Log.w(TAG, "MediaInMediaSets worker retry was detected, "
                        + "ending this run in failure.");
                return ListenableWorker.Result.failure();
            }

            Log.i(TAG, "Starting media in media sets sync from sync source " + syncSource
                    + " with mediaSetId " + mediaSetId);

            checkIfWorkerHasStopped();

            checkValidityOfWorkerInputParams(
                    mediaSetId, syncSource, mediaSetPickerId, mediaSetAuthority);

            syncMediaInMediaSet(
                    syncSource, mediaSetId, mediaSetPickerId, mediaSetAuthority, mimeTypes);

            Log.i(TAG, "Completed media in media set sync for mediaSetId " + mediaSetId);

            return ListenableWorker.Result.success();

        } catch (RuntimeException | RequestObsoleteException e) {
            Log.e(TAG, "Could not complete media in media set sync from sync source "
                    + " for mediaSetId " + mediaSetId);
            return ListenableWorker.Result.failure();
        } finally {
            // mark sync as complete
            markMediaInMediaSetSyncAsComplete(syncSource, getId());
        }
    }

    /**
     * Fetches the media of a media set from the provider
     * @param syncSource Indicates whether we need to sync with the local provider or cloud provider
     * @param mediaSetId The identifying id of the media set
     * @param mediaSetPickerId The pickerId of the media set
     */
    private void syncMediaInMediaSet(
            int syncSource, @NonNull String mediaSetId,
            @NonNull String mediaSetPickerId, @NonNull String mediaSetAuthority,
            @Nullable String[] mimeTypes)
            throws RequestObsoleteException, IllegalArgumentException {
        final PickerSearchProviderClient searchClient =
                PickerSearchProviderClient.create(mContext, mediaSetAuthority);

        String resumePageToken = MediaSetsDatabaseUtil.getMediaResumeKey(
                mDatabase, mediaSetPickerId
        );

        if (SYNC_COMPLETE_RESUME_KEY.equals(resumePageToken)) {
            Log.i(TAG, "Sync has already been completed.");
            return;
        }

        try {
            for (int currentIteration = 0; currentIteration < SYNC_PAGE_COUNT; currentIteration++) {
                checkIfWorkerHasStopped();
                checkIfCurrentCloudProviderAuthorityHasChanged(mediaSetAuthority);

                try (Cursor mediaInMediaSetsCursor = fetchMediaInMediaSetFromCmp(
                        searchClient, mediaSetId, resumePageToken, mimeTypes)) {
                    // Cache the media items in this media set
                    List<ContentValues> mediaItemsToInsert =
                            MediaInMediaSetsDatabaseUtil.getMediaContentValuesFromCursor(
                                    mediaInMediaSetsCursor,
                                    mediaSetPickerId,
                                    isAuthorityLocal(mediaSetAuthority)
                            );
                    MediaInMediaSetsDatabaseUtil.cacheMediaOfMediaSet(
                            mDatabase, mediaItemsToInsert, mediaSetAuthority
                    );
                    resumePageToken = getResumePageToken(mediaInMediaSetsCursor.getExtras());

                    if (resumePageToken.equals(SYNC_COMPLETE_RESUME_KEY)) {
                        break;
                    }
                    // mark sync as complete
                    markMediaInMediaSetSyncAsComplete(syncSource, getId());
                }
            }
        } finally {
            MediaSetsDatabaseUtil.updateMediaInMediaSetSyncResumeKey(
                    mDatabase, mediaSetPickerId, resumePageToken
            );
        }
    }

    /**
     * Makes a call to CMP to fetch the media of a media set
     * @param pickerSearchProviderClient Helper client to fetch media
     * @param mediaSetId Identifying id of the media set
     * @param resumePageToken The page token to fetch media
     * @return Cursor with the media of the corresponding mediaSetId
     */
    private Cursor fetchMediaInMediaSetFromCmp(
            @NonNull PickerSearchProviderClient pickerSearchProviderClient,
            @NonNull String mediaSetId, @Nullable String resumePageToken,
            @Nullable String[] mimeTypes) {
        final Cursor cursor = pickerSearchProviderClient.fetchMediasInMediaSetFromCmp(
                mediaSetId,
                resumePageToken,
                PAGE_SIZE,
                CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN,
                mimeTypes,
                mCancellationSignal
        );

        if (cursor == null) {
            throw new IllegalStateException("Cursor returned from provider is null.");
        }

        return cursor;
    }

    @NonNull
    private String getResumePageToken(@Nullable Bundle extras) {
        if (extras == null
                || extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN) == null) {
            return SYNC_COMPLETE_RESUME_KEY;
        }

        return extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN);
    }

    private void checkIfWorkerHasStopped() throws RequestObsoleteException {
        if (isStopped()) {
            throw new RequestObsoleteException("MediaInMediaSets worker has stopped" + getId());
        }
    }

    private void checkIfCurrentCloudProviderAuthorityHasChanged(@NonNull String authority)
            throws RequestObsoleteException {
        if (isAuthorityLocal(authority)) {
            return;
        }
        final String currentCloudAuthority = getCurrentCloudProviderAuthority();
        if (!authority.equals(currentCloudAuthority)) {
            throw new RequestObsoleteException("Cloud provider authority has changed."
                    + " Sync will not be continued."
                    + " Current cloud provider authority: " + currentCloudAuthority
                    + " Cloud provider authority to sync with: " + authority);
        }
    }

    private void checkValidityOfWorkerInputParams(
            @NonNull String mediaSetId, int syncSource,
            @NonNull String mediaSetPickerId, @NonNull String mediaSetAuthority) {
        Objects.requireNonNull(mediaSetId);
        if (mediaSetId.isEmpty()) {
            Log.e(TAG, "Received empty mediaSetId id to fetch media set items");
            throw new IllegalArgumentException("mediaSetId was an empty string");
        }

        Objects.requireNonNull(mediaSetPickerId);
        if (mediaSetPickerId.isEmpty()) {
            Log.e(TAG, "Received empty mediaSetPickerId id to fetch media set items");
            throw new IllegalArgumentException("mediaSetPickerId was an empty string");
        }

        // SyncSource should either be cloud or local in order to fetch media set items
        if (syncSource != SYNC_LOCAL_ONLY && syncSource != SYNC_CLOUD_ONLY) {
            throw new IllegalArgumentException("Invalid media sets sync source " + syncSource);
        }

        Objects.requireNonNull(mediaSetAuthority);
        if (mediaSetAuthority.isEmpty()) {
            Log.e(TAG, "Received empty mediaSetAuthority id to fetch media set items");
            throw new IllegalArgumentException("mediaSetPickerId was an empty string");
        }
    }

    private boolean isAuthorityLocal(@NonNull String authority) {
        return getLocalProviderAuthority().equals(authority);
    }

    @Nullable
    private String getLocalProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow().getLocalProvider();
    }

    @Nullable
    private String getCurrentCloudProviderAuthority() {
        return PickerSyncController.getInstanceOrThrow().getCloudProvider();
    }

    private SQLiteDatabase getDatabase() {
        return PickerSyncController.getInstanceOrThrow().getDbFacade().getDatabase();
    }
}
