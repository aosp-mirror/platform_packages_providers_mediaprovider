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
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_LOCAL_ONLY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_RESET_ALBUM;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_RESET_MEDIA;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_ALBUM_ID;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_AUTHORITY;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_RESET_TYPE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_INPUT_SYNC_SOURCE;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.SYNC_WORKER_TAG_IS_PERIODIC;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.markAlbumMediaSyncAsComplete;
import static com.android.providers.media.photopicker.sync.SyncTrackerRegistry.trackNewAlbumMediaSyncRequests;

import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncManager.SyncResetType;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;

/**
 * This is a {@link Worker} class responsible for handling table reset operations in the picker
 * database.
 */
public class MediaResetWorker extends Worker {

    private static final String TAG = "MediaResetWorker";
    private static final int UNDEFINED_RESET_TYPE = -1;

    @Nullable private final String mAlbumId;
    @NonNull private final Context mContext;
    @NonNull private final int mResetType;
    @NonNull private final int mSyncSource;

    @Nullable private String mAuthority;

    public MediaResetWorker(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
        super(context, workerParameters);
        mContext = context;

        mAuthority = getInputData().getString(SYNC_WORKER_INPUT_AUTHORITY);
        mAlbumId = getInputData().getString(SYNC_WORKER_INPUT_ALBUM_ID);
        mResetType = getInputData().getInt(SYNC_WORKER_INPUT_RESET_TYPE, UNDEFINED_RESET_TYPE);
        mSyncSource = getInputData().getInt(SYNC_WORKER_INPUT_SYNC_SOURCE, -1);
    }

    @Override
    public ListenableWorker.Result doWork() {
        Log.i(
                TAG,
                String.format(
                        "MediaReset has been requested. Authority: %s AlbumId: %s",
                        mAuthority, mAlbumId));

        PickerSyncController controller;
        PickerDbFacade dBFacade;
        PickerSyncLockManager pickerSyncLockManager;
        try {
            controller = PickerSyncController.getInstanceOrThrow();
            pickerSyncLockManager = controller.getPickerSyncLockManager();
            dBFacade = new PickerDbFacade(mContext, pickerSyncLockManager);
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Unable to obtain PickerSyncController", ex);
            return ListenableWorker.Result.failure();
        } catch (SQLiteException ex) {
            Log.e(TAG, "Unable to get writeable database", ex);
            return ListenableWorker.Result.failure();
        }


        try {
            if (getTags().contains(SYNC_WORKER_TAG_IS_PERIODIC)) {
                // If this worker is being run as part of periodic work, it needs to register
                // its own sync with the sync tracker.
                trackNewAlbumMediaSyncRequests(mSyncSource, getId());

                // Since this is a periodic worker, we'll use the cloud authority, if it exists.
                // Using the cloud authority will reset files for all providers. If the local
                // authority is used, it will limit the query to only files with a local_id, but
                // the cloud authority does not have such a limitation.
                // (This is not intuitive, it's just how it works.)
                mAuthority = controller.getCloudProviderWithTimeout();
                if (mAuthority == null) {
                    mAuthority = controller.getLocalProvider();
                }
                // If the authority is still null, end the operation.
                if (mAuthority == null) {
                    Log.e(TAG, "Unable to set authority for periodic worker");
                    return ListenableWorker.Result.failure();
                }
            }

            if (mSyncSource == SYNC_LOCAL_ONLY) {
                return start(dBFacade);
            } else {
                // SyncSource is either CLOUD_ONLY or LOCAL_AND_CLOUD, either way we need the
                // cloud lock.
                try (CloseableReentrantLock ignored = pickerSyncLockManager
                        .tryLock(PickerSyncLockManager.CLOUD_ALBUM_SYNC_LOCK)) {
                    return start(dBFacade);
                }
            }
        } catch (UnableToAcquireLockException e) {
            Log.e(TAG, "Could not acquire lock", e);
            return ListenableWorker.Result.failure();
        } finally {
            markAlbumMediaSyncAsComplete(mSyncSource, getId());
        }
    }

    private ListenableWorker.Result start(@NonNull PickerDbFacade dbFacade) {

        Trace.beginSection("MediaResetWorker:BeginOperation");

        int deleteCount = 0;
        try (PickerDbFacade.DbWriteOperation operation =
                beginResetOperation(dbFacade, mResetType)) {

            deleteCount = operation.execute(/* cursor= */ null);

            // Just ensure the worker hasn't been stopped before allowing the commit.
            if (isStopped()) {
                Log.i(TAG, "Worker was stopped before operation was completed");
                return ListenableWorker.Result.failure();
            }
            operation.setSuccess();

        } catch (UnsupportedOperationException | IllegalStateException ex) {
            Log.e(TAG, "Operation failed.", ex);
            return ListenableWorker.Result.failure();
        } finally {
            Trace.endSection();
        }

        Log.i(TAG, String.format("Reset operation complete. Deleted rows: %d", deleteCount));
        return ListenableWorker.Result.success();
    }

    private PickerDbFacade.DbWriteOperation beginResetOperation(
            @NonNull PickerDbFacade dbFacade, @NonNull @SyncResetType int resetType) {

        switch (resetType) {
            case SYNC_RESET_ALBUM:
                if (mAuthority == null) {
                    throw new IllegalStateException(
                            String.format(
                                    "Failed to begin SYNC_RESET_ALBUM. Unknown provider authority:"
                                            + " %s",
                                    mAuthority));
                }

                if (mSyncSource == SYNC_CLOUD_ONLY && mAlbumId == null) {
                    Log.w(
                            TAG,
                            "Sync Source is set to SYNC_CLOUD_ONLY with no albumId, but the reset"
                                    + " operation will still remove cloud+local files.");
                }
                return dbFacade.beginResetAlbumMediaOperation(mAuthority, mAlbumId);
            case SYNC_RESET_MEDIA:
            default:
                throw new UnsupportedOperationException(
                        String.format(
                                "Requested Reset operation not (yet) supported. ResetType: %d",
                                resetType));
        }
    }

    @Override
    @NonNull
    public ForegroundInfo getForegroundInfo() {
        return PickerSyncNotificationHelper.getForegroundInfo(mContext);
    }

    @Override
    public void onStopped() {
        Log.w(
                TAG,
                "Worker is stopped. Clearing all pending futures. It's possible that the worker "
                        + "still finishes running if it has started already.");
        markAlbumMediaSyncAsComplete(mSyncSource, getId());
    }
}
