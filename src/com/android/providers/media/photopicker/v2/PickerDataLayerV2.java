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

package com.android.providers.media.photopicker.v2;

import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_LOCAL_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.WorkManagerInitializer.getWorkManager;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.sync.CloseableReentrantLock;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.sync.SyncCompletionWaiter;
import com.android.providers.media.photopicker.sync.SyncTrackerRegistry;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;
import com.android.providers.media.photopicker.v2.model.MediaQuery;
import com.android.providers.media.photopicker.v2.model.MediaSource;

import java.util.Arrays;
import java.util.List;

/**
 * This class handles Photo Picker content queries.
 */
public class PickerDataLayerV2 {
    private static final String TAG = "PickerDataLayerV2";
    private static final int CLOUD_SYNC_TIMEOUT_MILLIS = 500;
    /**
     * Returns a cursor with the Photo Picker media in response.
     *
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     */
    @NonNull
    public static Cursor queryMedia(@NonNull Context appContext, @NonNull Bundle queryArgs) {
        final MediaQuery query = new MediaQuery(queryArgs);
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();

        if (syncController.shouldQueryCloudMedia(query.getProviders())) {
            final PickerSyncLockManager syncLockManager = syncController.getPickerSyncLockManager();
            try (CloseableReentrantLock ignored =
                         syncLockManager.tryLock(PickerSyncLockManager.CLOUD_PROVIDER_LOCK)) {
                // TODO(b/329122491) wait for sync to finish.
                return queryMediaLocked(
                        appContext,
                        syncController,
                        query,
                        /* shouldQueryCloudMedia */ true
                );
            } catch (UnableToAcquireLockException e) {
                throw new RuntimeException("Could not fetch media", e);
            }
        } else {
            return queryMediaLocked(
                    appContext,
                    syncController,
                    query,
                    /* shouldQueryCloudMedia */ false
            );
        }
    }

    /**
     * Query media from the database and prepare a cursor in response.
     *
     * We need to make multiple queries to prepare a response for the media query.
     * {@link android.database.sqlite.SQLiteQueryBuilder} currently does not support the creation of
     * a transaction in {@code DEFERRED} mode. This is why we'll perform the read queries in
     * {@code IMMEDIATE} mode instead.
     */
    @NonNull
    static Cursor queryMediaLocked(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaQuery query,
            boolean shouldQueryCloudMedia
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();
            final String localAuthority =
                    query.getProviders().contains(syncController.getLocalProvider())
                    ? syncController.getLocalProvider()
                    : null;
            final String cloudAuthority = (shouldQueryCloudMedia
                    && query.getProviders().contains(syncController.getCloudProvider()))
                    ? syncController.getCloudProvider()
                    : null;

            waitForOngoingSync(appContext, localAuthority, cloudAuthority);

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                            query,
                            database,
                            localAuthority,
                            cloudAuthority
                    ),
                    /* selectionArgs */ null
                );

                Bundle extraArgs = new Bundle();
                Cursor nextPageKeyCursor = database.rawQuery(
                        getMediaNextPageKeyQuery(
                            query,
                            database,
                            localAuthority,
                            cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addNextPageKey(extraArgs, nextPageKeyCursor);

                Cursor prevPageKeyCursor = database.rawQuery(
                        getMediaPreviousPageQuery(
                                query,
                                database,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                database.setTransactionSuccessful();

                pageData.setExtras(extraArgs);
                return pageData;
            } finally {
                database.endTransaction();
            }


        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }

    private static void waitForOngoingSync(
            @NonNull Context appContext,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (localAuthority != null) {
            SyncCompletionWaiter.waitForSync(
                    getWorkManager(appContext),
                    SyncTrackerRegistry.getLocalSyncTracker(),
                    IMMEDIATE_LOCAL_SYNC_WORK_NAME
            );
        }

        if (cloudAuthority != null) {
            boolean syncIsComplete = SyncCompletionWaiter.waitForSyncWithTimeout(
                    SyncTrackerRegistry.getCloudSyncTracker(),
                    CLOUD_SYNC_TIMEOUT_MILLIS);
            Log.i(TAG, "Finished waiting for cloud sync.  Is cloud sync complete: "
                    + syncIsComplete);
        }
    }

    /**
     * Adds the next page key to the cursor extras from the given cursor.
     *
     * This is not a part of the page data. Photo Picker UI uses the Paging library requires us to
     * provide the previous page key and the next page key as part of a page load response.
     * The page key in this case refers to the date taken and the picker id of the first item in
     * the page.
     */
    private static void addNextPageKey(Bundle extraArgs, Cursor nextPageKeyCursor) {
        if (nextPageKeyCursor.moveToFirst()) {
            final int pickerIdColumnIndex = nextPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
            );

            if (pickerIdColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras.NEXT_PAGE_ID.getKey(),
                        nextPageKeyCursor.getLong(pickerIdColumnIndex)
                );
            }

            final int dateTakenColumnIndex = nextPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
            );

            if (dateTakenColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras
                                .NEXT_PAGE_DATE_TAKEN.getKey(),
                        nextPageKeyCursor.getLong(dateTakenColumnIndex)
                );
            }
        }
    }

    /**
     * Adds the previous page key to the cursor extras from the given cursor.
     *
     * This is not a part of the page data. Photo Picker UI uses the Paging library requires us to
     * provide the previous page key and the next page key as part of a page load response.
     * The page key in this case refers to the date taken and the picker id of the first item in
     * the page.
     */
    private static void addPrevPageKey(Bundle extraArgs, Cursor prevPageKeyCursor) {
        if (prevPageKeyCursor.moveToLast()) {
            final int pickerIdColumnIndex = prevPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
            );

            if (pickerIdColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras.PREV_PAGE_ID.getKey(),
                        prevPageKeyCursor.getLong(pickerIdColumnIndex)
                );
            }

            final int dateTakenColumnIndex = prevPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
            );

            if (dateTakenColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras
                                .PREV_PAGE_DATE_TAKEN.getKey(),
                        prevPageKeyCursor.getLong(dateTakenColumnIndex)
                );
            }
        }
    }

    /**
     * Builds and returns the SQL query to get the page contents from the Media table in Picker DB.
     */
    private static String getMediaPageQuery(
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA.name())
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.MEDIA_ID.getProjection(),
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjection(),
                        PickerSQLConstants.MediaResponse
                                .AUTHORITY.getProjection(localAuthority, cloudAuthority),
                        PickerSQLConstants.MediaResponse.MEDIA_SOURCE.getProjection(),
                        PickerSQLConstants.MediaResponse
                                .WRAPPED_URI.getProjection(localAuthority, cloudAuthority),
                        PickerSQLConstants.MediaResponse
                                .UNWRAPPED_URI.getProjection(localAuthority, cloudAuthority),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjection(),
                        PickerSQLConstants.MediaResponse.SIZE_IN_BYTES.getProjection(),
                        PickerSQLConstants.MediaResponse.MIME_TYPE.getProjection(),
                        PickerSQLConstants.MediaResponse.STANDARD_MIME_TYPE.getProjection(),
                        PickerSQLConstants.MediaResponse.DURATION_MS.getProjection()
                ))
                .setSortOrder(
                        String.format(
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getColumnName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getColumnName()
                        )
                )
                .setLimit(query.getPageSize());

        query.addWhereClause(
                queryBuilder,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the next page key from the Media table in Picker DB.
     */
    @Nullable
    private static String getMediaNextPageKeyQuery(
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (query.getPageSize() == Integer.MAX_VALUE) {
            return null;
        }

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA.name())
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjection(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjection()
                ))
                .setSortOrder(
                        String.format(
                                "%s DESC, %s DESC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getColumnName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getColumnName()
                        )
                )
                .setLimit(1)
                .setOffset(query.getPageSize());

        query.addWhereClause(
                queryBuilder,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the previous page contents from the Media table in
     * Picker DB.
     *
     * We fetch the whole page and not just one key because it is possible that the previous page
     * is smaller than the page size. So, we get the whole page and only use the last row item to
     * get the previous page key.
     */
    private static String getMediaPreviousPageQuery(
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(PickerSQLConstants.Table.MEDIA.name())
                .setProjection(List.of(
                        PickerSQLConstants.MediaResponse.PICKER_ID.getProjection(),
                        PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjection()
                )).setSortOrder(
                        String.format(
                                "%s ASC, %s ASC",
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getColumnName(),
                                PickerSQLConstants.MediaResponse.PICKER_ID.getColumnName()
                        )
                ).setLimit(query.getPageSize());


        query.addWhereClause(
                queryBuilder,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ true
        );

        return queryBuilder.buildQuery();
    }

    static Cursor queryAlbum(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    static Cursor queryAlbumContent(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    /**
     * @return a cursor with the available providers.
     */
    @NonNull
    public static Cursor queryAvailableProviders(@NonNull Context context) {
        try {
            final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
            final String[] columnNames = Arrays
                    .stream(PickerSQLConstants.AvailableProviderResponse.values())
                    .map(PickerSQLConstants.AvailableProviderResponse::getColumnName)
                    .toArray(String[]::new);
            final MatrixCursor matrixCursor = new MatrixCursor(columnNames, /*initialCapacity */ 2);
            final String localAuthority = syncController.getLocalProvider();
            addAvailableProvidersToCursor(matrixCursor,
                    localAuthority,
                    MediaSource.LOCAL,
                    Process.myUid());

            final String cloudAuthority = syncController.getCloudProvider();
            if (cloudAuthority != null) {
                final PackageManager packageManager = context.getPackageManager();
                final int uid = packageManager.getPackageUid(
                        packageManager
                                .resolveContentProvider(cloudAuthority, /* flags */ 0)
                                .packageName,
                        /* flags */ 0
                );
                addAvailableProvidersToCursor(
                        matrixCursor,
                        cloudAuthority,
                        MediaSource.REMOTE,
                        uid);
            }

            return matrixCursor;
        } catch (IllegalStateException | NameNotFoundException e) {
            throw new RuntimeException("Unexpected internal error occurred", e);
        }
    }

    private static void addAvailableProvidersToCursor(
            @NonNull MatrixCursor cursor,
            @NonNull String authority,
            @NonNull MediaSource source,
            @UserIdInt int uid) {
        cursor.newRow()
                .add(PickerSQLConstants.AvailableProviderResponse.AUTHORITY.getColumnName(),
                        authority)
                .add(PickerSQLConstants.AvailableProviderResponse.MEDIA_SOURCE.getColumnName(),
                        source.name())
                .add(PickerSQLConstants.AvailableProviderResponse.UID.getColumnName(), uid);
    }

    /**
     * @return a Bundle with the details of the requested cloud provider.
     */
    public static Bundle getCloudProviderDetails(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }
}
