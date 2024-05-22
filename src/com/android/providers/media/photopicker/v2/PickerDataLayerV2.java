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

import static java.util.Objects.requireNonNull;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
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
import com.android.providers.media.photopicker.sync.SyncCompletionWaiter;
import com.android.providers.media.photopicker.sync.SyncTrackerRegistry;
import com.android.providers.media.photopicker.v2.model.AlbumMediaQuery;
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
    public static final List<String> sMergedAlbumIds = List.of(
            AlbumColumns.ALBUM_ID_FAVORITES,
            AlbumColumns.ALBUM_ID_VIDEOS
    );

    /**
     * Returns a cursor with the Photo Picker media in response.
     *
     * @param appContext The application context.
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     */
    @NonNull
    static Cursor queryMedia(@NonNull Context appContext, @NonNull Bundle queryArgs) {
        final MediaQuery query = new MediaQuery(queryArgs);
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final String effectiveLocalAuthority =
                query.getProviders().contains(syncController.getLocalProvider())
                        ? syncController.getLocalProvider()
                        : null;
        final String cloudAuthority = syncController
                .getCloudProviderOrDefault(/* defaultValue */ null);
        final String effectiveCloudAuthority =
                syncController.shouldQueryCloudMedia(query.getProviders(), cloudAuthority)
                        ? cloudAuthority
                        : null;

        return queryMediaInternal(
                appContext,
                syncController,
                query,
                effectiveLocalAuthority,
                effectiveCloudAuthority
        );
    }

    /**
     * Returns a cursor with the Photo Picker albums in response.
     *
     * @param appContext The application context.
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     */
    @Nullable
    static Cursor queryAlbums(@NonNull Context appContext, @NonNull Bundle queryArgs) {
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
            Cursor mergeCursor = new MergeCursor(cursors.toArray(new Cursor[0]));
            Log.i(TAG, "Returning " + mergeCursor.getCount() + " albums metadata");
            return mergeCursor;
        }
    }

    /**
     * Returns a cursor with the Photo Picker album media in response.
     *
     * @param appContext The application context.
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     * @param albumId The album ID of the requested album media.
     */
    static Cursor queryAlbumMedia(
            @NonNull Context appContext,
            @NonNull Bundle queryArgs,
            @NonNull String albumId) {
        final AlbumMediaQuery query = new AlbumMediaQuery(queryArgs, albumId);
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final String effectiveLocalAuthority =
                query.getProviders().contains(syncController.getLocalProvider())
                        ? syncController.getLocalProvider()
                        : null;
        final String cloudAuthority = syncController
                .getCloudProviderOrDefault(/* defaultValue */ null);
        final String effectiveCloudAuthority =
                syncController.shouldQueryCloudMedia(query.getProviders(), cloudAuthority)
                        ? cloudAuthority
                        : null;

        if (isMergedAlbum(albumId)) {
            return queryMergedAlbumMedia(
                    albumId,
                    appContext,
                    syncController,
                    queryArgs,
                    effectiveLocalAuthority,
                    effectiveCloudAuthority
            );
        } else {
            return queryAlbumMediaInternal(
                    appContext,
                    syncController,
                    query,
                    effectiveLocalAuthority,
                    effectiveCloudAuthority
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
     *
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    private static Cursor queryMediaInternal(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            waitForOngoingSync(appContext, localAuthority, cloudAuthority);

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                            query,
                            database,
                            PickerSQLConstants.Table.MEDIA,
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
                            PickerSQLConstants.Table.MEDIA,
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
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                database.setTransactionSuccessful();

                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
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
     * @param appContext The application context.
     * @param query The AlbumMediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     */
    private static void waitForOngoingAlbumSync(
            @NonNull Context appContext,
            @NonNull AlbumMediaQuery query,
            @Nullable String localAuthority) {
        boolean isLocal = localAuthority != null
                && localAuthority.equals(query.getAlbumAuthority());
        SyncCompletionWaiter.waitForSyncWithTimeout(
                SyncTrackerRegistry.getAlbumSyncTracker(isLocal),
                /* timeoutInMillis */ 500);
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
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table.name())
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
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (query.getPageSize() == Integer.MAX_VALUE) {
            return null;
        }

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table.name())
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
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(table.name())
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
                            PickerSQLConstants.Table.MEDIA,
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

    /**
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The AlbumMediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    private static Cursor queryAlbumMediaInternal(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull AlbumMediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            waitForOngoingAlbumSync(appContext, query, localAuthority);

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                                query,
                                database,
                                PickerSQLConstants.Table.ALBUM_MEDIA,
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
                                PickerSQLConstants.Table.ALBUM_MEDIA,
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
                                PickerSQLConstants.Table.ALBUM_MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                database.setTransactionSuccessful();

                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " album media items for album "
                        + query.getAlbumId());
                return pageData;
            } finally {
                database.endTransaction();
            }


        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }

    /**
     * @param albumId The album id of the request album media.
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param queryArgs The Bundle with query args received with the request.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    private static Cursor queryMergedAlbumMedia(
            @NonNull String albumId,
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull Bundle queryArgs,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            MediaQuery query;
            switch (albumId) {
                case AlbumColumns.ALBUM_ID_FAVORITES:
                    query = new FavoritesMediaQuery(queryArgs);
                    break;
                case AlbumColumns.ALBUM_ID_VIDEOS:
                    query = new VideoMediaQuery(queryArgs);
                    break;
                default:
                    throw new IllegalArgumentException("Cannot recognize album " + albumId);
            }

            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            waitForOngoingSync(appContext, localAuthority, cloudAuthority);

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
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
                                PickerSQLConstants.Table.MEDIA,
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
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                database.setTransactionSuccessful();

                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " album media items for album "
                        + albumId);
                return pageData;
            } finally {
                database.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
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
            if (syncController.shouldQueryCloudMedia(cloudAuthority)) {
                final PackageManager packageManager = context.getPackageManager();
                final ProviderInfo providerInfo = requireNonNull(
                        packageManager.resolveContentProvider(cloudAuthority, /* flags */ 0));
                final int uid = packageManager.getPackageUid(
                        providerInfo.packageName,
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
     * @param albumId Album identifier.
     * @return True if the given album id matches the album id of any merged album.
     */
    private static boolean isMergedAlbum(@NonNull String albumId) {
        return sMergedAlbumIds.contains(albumId);
    }

    /**
     * @return a Bundle with the details of the requested cloud provider.
     */
    public static Bundle getCloudProviderDetails(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }
}
