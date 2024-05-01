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

import static com.android.providers.media.PickerUriResolver.getAlbumUri;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_LOCAL_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.WorkManagerInitializer.getWorkManager;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.sync.CloseableReentrantLock;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.sync.SyncCompletionWaiter;
import com.android.providers.media.photopicker.sync.SyncTrackerRegistry;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;
import com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper;
import com.android.providers.media.photopicker.v2.model.FavoritesMediaQuery;
import com.android.providers.media.photopicker.v2.model.MediaQuery;
import com.android.providers.media.photopicker.v2.model.MediaSource;
import com.android.providers.media.photopicker.v2.model.VideoMediaQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
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
     * @param appContext The application context.
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

    /**
     * Returns a cursor with the Photo Picker albums in response.
     *
     * @param appContext The application context.
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     */
    @Nullable
    static Cursor queryAlbum(@NonNull Context appContext, @NonNull Bundle queryArgs) {
        final MediaQuery query = new MediaQuery(queryArgs);
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();
        final String localAuthority = syncController.getLocalProvider();
        final boolean shouldShowLocalAlbums = query.getProviders().contains(localAuthority);
        final String cloudAuthority =
                syncController.getCloudProviderOrDefault(/* defaultValue */ null);
        final boolean shouldShowCloudAlbums = syncController.shouldQueryCloudMedia(
                query.getProviders(), cloudAuthority);
        final List<AlbumsCursorWrapper> cursors = new ArrayList<>();

        if (shouldShowLocalAlbums || shouldShowCloudAlbums) {
            cursors.add(getMergedAlbumsCursor(
                    AlbumColumns.ALBUM_ID_FAVORITES, queryArgs, database,
                    shouldShowLocalAlbums ? localAuthority : null,
                    shouldShowCloudAlbums ? cloudAuthority : null));

            cursors.add(getMergedAlbumsCursor(
                    AlbumColumns.ALBUM_ID_VIDEOS, queryArgs, database,
                    shouldShowLocalAlbums ? localAuthority : null,
                    shouldShowCloudAlbums ? cloudAuthority : null));
        }

        if (shouldShowLocalAlbums) {
            cursors.add(getLocalAlbumsCursor(appContext, query, localAuthority));
        }

        if (shouldShowCloudAlbums) {
            cursors.add(getCloudAlbumsCursor(appContext, query, localAuthority, cloudAuthority));
        }

        for (Iterator<AlbumsCursorWrapper> iterator = cursors.iterator(); iterator.hasNext(); ) {
            if (iterator.next() == null) {
                iterator.remove();
            }
        }

        if (cursors.isEmpty()) {
            Log.e(TAG, "No albums available");
            return null;
        } else {
            return new MergeCursor(cursors.toArray(new Cursor[cursors.size()]));
        }
    }

    /**
     * Return merged albums cursor for the given merged album id.
     *
     * @param albumId Merged album id.
     * @param queryArgs Query arguments bundle that will be used to filter albums.
     * @param database Instance of Picker SQLiteDatabase.
     * @param localAuthority The local authority if local albums should be returned, otherwise this
     *                       argument should be null.
     * @param cloudAuthority The cloud authority if cloud albums should be returned, otherwise this
     *                       argument should be null.
     */
    private static AlbumsCursorWrapper getMergedAlbumsCursor(
            @NonNull String albumId,
            @NonNull Bundle queryArgs,
            @NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        final MediaQuery query;
        if (albumId.equals(AlbumColumns.ALBUM_ID_VIDEOS)) {
            query = new VideoMediaQuery(queryArgs, 1);
        } else if (albumId.equals(AlbumColumns.ALBUM_ID_FAVORITES)) {
            query = new FavoritesMediaQuery(queryArgs, 1);
        } else {
            Log.e(TAG, "Cannot recognize merged album " + albumId);
            return null;
        }

        try {
            database.beginTransactionNonExclusive();
            Cursor pickerDBResponse = database.rawQuery(
                    getMediaPageQuery(
                            query,
                            database,
                            localAuthority,
                            cloudAuthority
                    ),
                    /* selectionArgs */ null
            );

            if (pickerDBResponse.moveToFirst()) {
                // Conform to the album response projection. Temporary code, this will change once
                // we start caching album metadata.
                final MatrixCursor result = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
                final String authority = pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                        PickerSQLConstants.MediaResponse.AUTHORITY.getProjectedName()));
                final String[] projectionValue = new String[]{
                        /* albumId */ albumId,
                        pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName())),
                        /* displayName */ albumId,
                        pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                                PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())),
                        /* count */ "0", // This value is not used anymore
                        authority,
                };
                result.addRow(projectionValue);
                return new AlbumsCursorWrapper(result, authority, localAuthority);
            }

            // Show merged albums even if no data is currently available in the DB when cloud media
            // feature is enabled.
            if (cloudAuthority != null) {
                // Conform to the album response projection. Temporary code, this will change once
                // we start caching album metadata.
                final MatrixCursor result = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
                final String[] projectionValue = new String[]{
                        /* albumId */ albumId,
                        /* dateTakenMillis */ Long.toString(Long.MAX_VALUE),
                        /* displayName */ albumId,
                        /* mediaId */ Integer.toString(Integer.MAX_VALUE),
                        /* count */ "0", // This value is not used anymore
                        localAuthority,
                };
                result.addRow(projectionValue);
                return new AlbumsCursorWrapper(result, localAuthority, localAuthority);
            }

            return null;
        } finally {
            database.setTransactionSuccessful();
            database.endTransaction();
        }

    }

    /**
     * Returns local albums cursor after fetching them from the local provider.
     *
     * @param appContext The application context.
     * @param query Query arguments that will be used to filter albums.
     * @param localAuthority Authority of the local media provider.
     */
    @Nullable
    private static AlbumsCursorWrapper getLocalAlbumsCursor(
            @NonNull Context appContext,
            @NonNull MediaQuery query,
            @NonNull String localAuthority) {
        return getCMPAlbumsCursor(appContext, query, localAuthority, localAuthority);
    }

    /**
     * Returns cloud albums cursor after fetching them from the local provider.
     *
     * @param appContext The application context.
     * @param query Query arguments that will be used to filter albums.
     * @param localAuthority Authority of the local media provider.
     * @param cloudAuthority Authority of the cloud media provider.
     */
    @Nullable
    private static AlbumsCursorWrapper getCloudAlbumsCursor(
            @NonNull Context appContext,
            @NonNull MediaQuery query,
            @NonNull String localAuthority,
            @NonNull String cloudAuthority) {
        return getCMPAlbumsCursor(appContext, query, localAuthority, cloudAuthority);
    }

    /**
     * Returns {@link AlbumsCursorWrapper} object that wraps the albums cursor response from the
     * CMP.
     *
     * @param appContext The application context.
     * @param query Query arguments that will be used to filter albums.
     * @param localAuthority Authority of the local media provider.
     * @param cmpAuthority Authority of the cloud media provider.
     */
    @Nullable
    private static AlbumsCursorWrapper getCMPAlbumsCursor(
            @NonNull Context appContext,
            @NonNull MediaQuery query,
            @NonNull String localAuthority,
            @NonNull String cmpAuthority) {
        final Cursor cursor = appContext.getContentResolver().query(
                getAlbumUri(cmpAuthority),
                /* projection */ null,
                query.prepareCMPQueryArgs(),
                /* cancellationSignal */ null);
        return cursor == null
                ? null
                : new AlbumsCursorWrapper(cursor, cmpAuthority, localAuthority);
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

            final String cloudAuthority =
                    syncController.getCloudProviderOrDefault(/* defaultValue */ null);
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
