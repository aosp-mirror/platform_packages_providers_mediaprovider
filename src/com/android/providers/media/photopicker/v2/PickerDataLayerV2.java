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

import static com.android.providers.media.MediaGrants.MEDIA_GRANTS_TABLE;
import static com.android.providers.media.MediaGrants.OWNER_PACKAGE_NAME_COLUMN;
import static com.android.providers.media.MediaGrants.PACKAGE_USER_ID_COLUMN;
import static com.android.providers.media.PickerUriResolver.getAlbumUri;
import static com.android.providers.media.photopicker.PickerSyncController.getPackageNameFromUid;
import static com.android.providers.media.photopicker.PickerSyncController.uidToUserId;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_GRANTS_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.PickerSyncManager.IMMEDIATE_LOCAL_SYNC_WORK_NAME;
import static com.android.providers.media.photopicker.sync.WorkManagerInitializer.getWorkManager;
import static com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper.EMPTY_MEDIA_ID;

import static java.util.Objects.requireNonNull;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Process;
import android.provider.CloudMediaProviderContract.AlbumColumns;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.sync.SyncCompletionWaiter;
import com.android.providers.media.photopicker.sync.SyncTrackerRegistry;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;
import com.android.providers.media.photopicker.v2.model.AlbumMediaQuery;
import com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper;
import com.android.providers.media.photopicker.v2.model.MediaQuery;
import com.android.providers.media.photopicker.v2.model.MediaQueryForPreSelection;
import com.android.providers.media.photopicker.v2.model.MediaSource;
import com.android.providers.media.photopicker.v2.model.PreviewMediaQuery;
import com.android.providers.media.photopicker.v2.model.ProviderCollectionInfo;
import com.android.providers.media.photopicker.v2.sqlite.PickerMediaDatabaseUtil;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;
import com.android.providers.media.photopicker.v2.sqlite.SearchMediaQuery;
import com.android.providers.media.photopicker.v2.sqlite.SearchResultsDatabaseUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class handles Photo Picker content queries.\
 */
public class PickerDataLayerV2 {
    private static final String TAG = "PickerDataLayerV2";
    private static final int CLOUD_SYNC_TIMEOUT_MILLIS = 500;
    // Local and merged albums have a predefined order that they should be displayed in. They always
    // need to be displayed above the cloud albums too.
    public static final List<String> PINNED_ALBUMS_ORDER = List.of(
            AlbumColumns.ALBUM_ID_FAVORITES,
            AlbumColumns.ALBUM_ID_CAMERA,
            AlbumColumns.ALBUM_ID_VIDEOS,
            AlbumColumns.ALBUM_ID_SCREENSHOTS,
            AlbumColumns.ALBUM_ID_DOWNLOADS
    );
    // Set of known merged albums.
    public static final Set<String> MERGED_ALBUMS = Set.of(
            AlbumColumns.ALBUM_ID_FAVORITES,
            AlbumColumns.ALBUM_ID_VIDEOS
    );

    // Set of known local albums.
    public static final Set<String> LOCAL_ALBUMS = Set.of(
            AlbumColumns.ALBUM_ID_CAMERA,
            AlbumColumns.ALBUM_ID_SCREENSHOTS,
            AlbumColumns.ALBUM_ID_DOWNLOADS
    );

    /**
     * Table used to store the items for which the app hold read grants but have been de-selected
     * by the user in the current photo-picker session.
     */
    public static final String DE_SELECTIONS_TABLE = "de_selections";

    /**
     * Table used to store the items for which the app hold read grants but have been de-selected
     * by the user in the current photo-picker session, filtered by calling package name and userId.
     */
    public static final String CURRENT_DE_SELECTIONS_TABLE = "current_de_selections";

    private static final String IS_FIRST_PAGE = "is_first_page";
    /**
     * In SQL joins for media_grants table, it is filtered to only provide the rows corresponding to
     * the current package and userId. This is the name for the filtered table that is computed in a
     * sub-query. Any references to the columns for media_grants table should use this table name
     * instead.
     */
    public static final String CURRENT_GRANTS_TABLE = "current_media_grants";

    public static final String COLUMN_GRANTS_COUNT = "grants_count";

    private static final String PROJECTION_GRANTS_COUNT = String.format("COUNT(*) AS %s",
            COLUMN_GRANTS_COUNT);

    /**
     * Refresh the cloud provider in-memory cache in PickerSyncController.
     */
    public static void ensureProviders() {
        try {
            final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
            syncController.maybeEnableCloudMediaQueries();
        } catch (UnableToAcquireLockException | RequestObsoleteException exception) {
            Log.e(TAG, "Could not ensure that the providers are set.");
        }
    }

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

        waitForOngoingSync(appContext, effectiveLocalAuthority, effectiveCloudAuthority,
                query.getIntentAction());

        return PickerMediaDatabaseUtil.queryMedia(
                appContext,
                syncController,
                query,
                effectiveLocalAuthority,
                effectiveCloudAuthority
        );
    }

    /**
     * Returns a cursor with the Photo Picker media in response.
     *
     * @param appContext The application context.
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     */
    @NonNull
    static Cursor queryPreviewMedia(@NonNull Context appContext, @NonNull Bundle queryArgs) {
        final PreviewMediaQuery query = new PreviewMediaQuery(queryArgs);
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

        if (queryArgs.getBoolean(IS_FIRST_PAGE)) {
            PreviewMediaQuery.insertDeSelections(appContext, syncController,
                    query.getCallingPackageUid(), query.getCurrentDeSelection());
        }

        waitForOngoingSync(appContext, effectiveLocalAuthority, effectiveCloudAuthority,
                query.getIntentAction());

        return PickerMediaDatabaseUtil.queryMedia(
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
        final List<AlbumsCursorWrapper> allAlbumCursors = new ArrayList<>();

        final String effectiveLocalAuthority = shouldShowLocalAlbums ? localAuthority : null;
        final String effectiveCloudAuthority = shouldShowCloudAlbums ? cloudAuthority : null;

        // Get all local albums from the local provider in separate cursors to facilitate zipping
        // them with merged albums.
        final Map<String, AlbumsCursorWrapper> localAlbums = getLocalAlbumCursors(
                appContext, query, effectiveLocalAuthority);

        // Add Pinned album cursors to the list of all album cursors in the order in which they
        // should be displayed. Note that pinned albums can only be local and merged albums.
        for (String albumId: PINNED_ALBUMS_ORDER) {
            final AlbumsCursorWrapper albumCursor;
            if (MERGED_ALBUMS.contains(albumId)) {
                albumCursor = PickerMediaDatabaseUtil.getMergedAlbumsCursor(
                        albumId, appContext, queryArgs, database,
                        effectiveLocalAuthority, effectiveCloudAuthority);
            } else if (LOCAL_ALBUMS.contains(albumId)) {
                albumCursor = localAlbums.getOrDefault(albumId, null);
            } else {
                Log.e(TAG, "Could not recognize pinned album id, skipping it : " + albumId);
                albumCursor = null;
            }
            allAlbumCursors.add(albumCursor);
        }

        // Add cloud albums at the end.
        // This is an external query into the CMP, so catch any exceptions that might get thrown
        // so that at a minimum, the local results are sent back to the UI.
        try {
            allAlbumCursors.add(getCloudAlbumsCursor(appContext, query, effectiveLocalAuthority,
                    effectiveCloudAuthority));
        } catch (RuntimeException ex) {
            Log.w(TAG, "Cloud provider exception while fetching cloud albums cursor", ex);
        }

        // Remove empty cursors.
        allAlbumCursors.removeIf(it -> it == null || !it.moveToFirst());

        if (allAlbumCursors.isEmpty()) {
            Log.e(TAG, "No albums available");
            return null;
        } else {
            Cursor mergeCursor = new MergeCursor(allAlbumCursors.toArray(new Cursor[0]));
            Log.i(TAG, "Returning " + mergeCursor.getCount() + " albums' metadata");
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

        if (MERGED_ALBUMS.contains(albumId)) {
            waitForOngoingSync(appContext, effectiveLocalAuthority, effectiveCloudAuthority,
                    query.getIntentAction());

            return PickerMediaDatabaseUtil.queryMergedAlbumMedia(
                    albumId,
                    appContext,
                    syncController,
                    queryArgs,
                    effectiveLocalAuthority,
                    effectiveCloudAuthority
            );
        } else {
            waitForOngoingAlbumSync(appContext, query, effectiveLocalAuthority);

            return PickerMediaDatabaseUtil.queryAlbumMedia(
                    appContext,
                    syncController,
                    query,
                    effectiveLocalAuthority,
                    effectiveCloudAuthority
            );
        }
    }

    /**
     * Returns a cursor with the Photo Picker search results media in response.
     *
     * @param queryArgs The arguments help us filter on the media query to yield the desired
     *                  results.
     * @param searchRequestID Identifier of the search request.
     */
    public static Cursor querySearchMedia(
            @NonNull Bundle queryArgs,
            @NonNull int searchRequestID) {
        final SearchMediaQuery query = new SearchMediaQuery(queryArgs, searchRequestID);

        // Validate query input
        if (MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(query.getIntentAction())) {
            throw new RuntimeException("Search feature cannot be enabled with Picker Choice");
        }

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

        // TODO(b/361041500) wait for sync before querying the database
        // TODO(b/361042632) resume sync if required

        return SearchResultsDatabaseUtil.querySearchMedia(
                syncController,
                query,
                effectiveLocalAuthority,
                effectiveCloudAuthority
        );
    }

    /**
     * Queries the picker database and fetches the count of pre-granted media for the current
     * package and userId.
     *
     * @return a [Cursor] containing only one column [COLUMN_GRANTS_COUNT] which have a single
     * row representing the count.
     */
    static Cursor fetchMediaGrantsCount(
            @NonNull Context appContext,
            @NonNull Bundle queryArgs) {
        String[] projectionIn = new String[]{PROJECTION_GRANTS_COUNT};
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

        waitForOngoingGrantsSync(appContext);

        int packageUid = queryArgs.getInt(Intent.EXTRA_UID);
        int userId = uidToUserId(packageUid);
        String[] packageNames = getPackageNameFromUid(appContext,
                packageUid);

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MEDIA_GRANTS_TABLE);
        addWhereClausesForPackageAndUserIdSelection(userId, packageNames, MEDIA_GRANTS_TABLE, qb);

        Cursor result = qb.query(database, projectionIn, null,
                null, null, null, null);
        return result;
    }

    /**
     * Adds the clause to select rows based on calling packageName and userId.
     */
    public static void addWhereClausesForPackageAndUserIdSelection(int userId,
            @NonNull String[] packageNames, String table, SQLiteQueryBuilder qb) {
        // Add where clause for userId selection.
        qb.appendWhereStandalone(
                String.format(Locale.ROOT,
                        "%s.%s = %d", table, PACKAGE_USER_ID_COLUMN, userId));

        // Add where clause for package name selection.
        Objects.requireNonNull(packageNames);
        qb.appendWhereStandalone(getPackageSelectionWhereClause(packageNames,
                table).toString());
    }

    private static void waitForOngoingSync(
            @NonNull Context appContext,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority, String intentAction) {
        // when the intent action is ACTION_USER_SELECT_IMAGES_FOR_APP, the flow should wait for
        // the sync of grants and since this is a localOnly session. It should not wait or check
        // cloud media.
        boolean isUserSelectAction = MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(
                intentAction);
        if (localAuthority != null) {
            SyncCompletionWaiter.waitForSync(
                    getWorkManager(appContext),
                    SyncTrackerRegistry.getGrantsSyncTracker(),
                    IMMEDIATE_GRANTS_SYNC_WORK_NAME
            );
            if (isUserSelectAction) {
                SyncCompletionWaiter.waitForSync(
                        getWorkManager(appContext),
                        SyncTrackerRegistry.getLocalSyncTracker(),
                        IMMEDIATE_LOCAL_SYNC_WORK_NAME
                );
            }
        }

        if (cloudAuthority != null && !isUserSelectAction) {
            boolean syncIsComplete = SyncCompletionWaiter.waitForSyncWithTimeout(
                    SyncTrackerRegistry.getCloudSyncTracker(),
                    CLOUD_SYNC_TIMEOUT_MILLIS);
            Log.i(TAG, "Finished waiting for cloud sync.  Is cloud sync complete: "
                    + syncIsComplete);
        }
    }

    private static void waitForOngoingGrantsSync(
            @NonNull Context appContext) {
        SyncCompletionWaiter.waitForSync(
                getWorkManager(appContext),
                SyncTrackerRegistry.getGrantsSyncTracker(),
                IMMEDIATE_GRANTS_SYNC_WORK_NAME
        );
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
     * Returns a clause that can be used to filter OWNER_PACKAGE_NAME_COLUMN using the input
     * packageNames in a query.
     */
    public static @NonNull StringBuilder getPackageSelectionWhereClause(String[] packageNames,
            String table) {
        StringBuilder packageSelection = new StringBuilder();
        String packageColumn = String.format("%s.%s", table, OWNER_PACKAGE_NAME_COLUMN);
        packageSelection.append(packageColumn).append(" IN (\'");

        String joinedPackageNames = String.join("\',\'", packageNames);
        packageSelection.append(joinedPackageNames);

        packageSelection.append("\')");
        return packageSelection;
    }

    public static Cursor getDefaultEmptyAlbum(@NonNull String albumId) {
        // Conform to the album response projection. Temporary code, this will change once we start
        // caching album metadata.
        final MatrixCursor result = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
        final String[] projectionValue = new String[]{
                /* albumId */ albumId,
                /* dateTakenMillis */ Long.toString(Long.MAX_VALUE),
                /* displayName */ albumId,
                /* mediaId */ EMPTY_MEDIA_ID,
                /* count */ "0", // This value is not used anymore
                /* authority */ null, // Authority is populated in AlbumsCursorWrapper
        };
        result.addRow(projectionValue);
        return result;
    }

    /**
     * Returns local albums in individial cursors mapped against their album id after fetching them
     * from the local provider.
     *
     * @param appContext The application context.
     * @param query Query arguments that will be used to filter albums.
     * @param localAuthority Authority of the local media provider.
     */
    @Nullable
    private static Map<String, AlbumsCursorWrapper> getLocalAlbumCursors(
            @NonNull Context appContext,
            @NonNull MediaQuery query,
            @Nullable String localAuthority) {
        if (localAuthority == null) {
            Log.d(TAG, "Cannot fetch local albums when local authority is null.");
            return null;
        }

        final Cursor localAlbumsCursor =
                getAlbumsCursorFromProvider(appContext, query, localAuthority);

        final Map<String, AlbumsCursorWrapper> localAlbumsMap = new HashMap<>();
        if (localAlbumsCursor != null && localAlbumsCursor.moveToFirst()) {
            do {
                try {
                    final String albumId =
                            localAlbumsCursor.getString(
                                    localAlbumsCursor.getColumnIndex(AlbumColumns.ID));
                    final MatrixCursor albumCursor =
                            new MatrixCursor(localAlbumsCursor.getColumnNames());
                    MatrixCursor.RowBuilder builder = albumCursor.newRow();
                    for (String columnName : localAlbumsCursor.getColumnNames()) {
                        final int columnIndex = localAlbumsCursor.getColumnIndex(columnName);
                        switch (localAlbumsCursor.getType(columnIndex)) {
                            case Cursor.FIELD_TYPE_INTEGER:
                                builder.add(columnName, localAlbumsCursor.getInt(columnIndex));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                builder.add(columnName, localAlbumsCursor.getFloat(columnIndex));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                builder.add(columnName, localAlbumsCursor.getBlob(columnIndex));
                                break;
                            case Cursor.FIELD_TYPE_NULL:
                                builder.add(columnName, null);
                                break;
                            case Cursor.FIELD_TYPE_STRING:
                                builder.add(columnName, localAlbumsCursor.getString(columnIndex));
                                break;
                            default:
                                throw new IllegalArgumentException(
                                        "Could not recognize column type "
                                                + localAlbumsCursor.getType(columnIndex));
                        }
                    }
                    localAlbumsMap.put(
                            albumId,
                            new AlbumsCursorWrapper(albumCursor,
                                    /* coverAuthority */ localAuthority,
                                    /* localAuthority */ localAuthority)
                    );
                } catch (RuntimeException e) {
                    Log.e(TAG,
                            "Could not read album cursor values received from local provider", e);
                }
            } while(localAlbumsCursor.moveToNext());
        }

        // Close localAlbumsCursor because it's data was copied into new Cursor(s) and it won't
        // be used again.
        if (localAlbumsCursor != null) localAlbumsCursor.close();

        // Always show Camera album.
        if (!localAlbumsMap.containsKey(AlbumColumns.ALBUM_ID_CAMERA)) {
            localAlbumsMap.put(
                    AlbumColumns.ALBUM_ID_CAMERA,
                    new AlbumsCursorWrapper(
                            getDefaultEmptyAlbum(AlbumColumns.ALBUM_ID_CAMERA),
                            /* albumAuthority */ localAuthority,
                            /* localAuthority */ localAuthority)
            );
        }

        return localAlbumsMap;
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
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (cloudAuthority == null) {
            Log.d(TAG, "Cannot fetch cloud albums when cloud authority is null.");
            return null;
        }

        final Cursor cursor = getAlbumsCursorFromProvider(appContext, query, cloudAuthority);
        return cursor == null
                ? null
                : new AlbumsCursorWrapper(cursor, cloudAuthority, localAuthority);
    }

    /**
     * Returns {@link AlbumsCursorWrapper} object that wraps the albums cursor response from the
     * provider.
     *
     * @param appContext The application context.
     * @param query Query arguments that will be used to filter albums.
     * @param providerAuthority Authority of the cloud media provider.
     */
    @Nullable
    private static Cursor getAlbumsCursorFromProvider(
            @NonNull Context appContext,
            @NonNull MediaQuery query,
            @NonNull String providerAuthority) {
        return appContext.getContentResolver().query(
                getAlbumUri(providerAuthority),
                /* projection */ null,
                query.prepareCMPQueryArgs(),
                /* cancellationSignal */ null);
    }

    /**
     * @return a cursor with the available providers.
     */
    @NonNull
    public static Cursor queryAvailableProviders(@NonNull Context context) {
        try {
            final PackageManager packageManager = context.getPackageManager();
            final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
            final String[] columnNames = Arrays
                    .stream(PickerSQLConstants.AvailableProviderResponse.values())
                    .map(PickerSQLConstants.AvailableProviderResponse::getColumnName)
                    .toArray(String[]::new);
            final MatrixCursor matrixCursor = new MatrixCursor(columnNames, /*initialCapacity */ 2);
            final String localAuthority = syncController.getLocalProvider();
            final ProviderInfo localProviderInfo = packageManager.resolveContentProvider(
                    localAuthority, /* flags */ 0);
            final String localProviderLabel =
                    String.valueOf(localProviderInfo.loadLabel(packageManager));
            addAvailableProvidersToCursor(
                    matrixCursor,
                    localAuthority,
                    MediaSource.LOCAL,
                    Process.myUid(),
                    localProviderLabel
            );

            final String cloudAuthority =
                    syncController.getCloudProviderOrDefault(/* defaultValue */ null);
            if (syncController.shouldQueryCloudMedia(cloudAuthority)) {
                final ProviderInfo cloudProviderInfo = requireNonNull(
                        packageManager.resolveContentProvider(cloudAuthority, /* flags */ 0));
                final int uid = packageManager.getPackageUid(
                        cloudProviderInfo.packageName,
                        /* flags */ 0
                );
                final String cloudProviderLabel =
                        String.valueOf(cloudProviderInfo.loadLabel(packageManager));
                addAvailableProvidersToCursor(
                        matrixCursor,
                        cloudAuthority,
                        MediaSource.REMOTE,
                        uid,
                        cloudProviderLabel
                );
            }

            return matrixCursor;
        } catch (IllegalStateException | NameNotFoundException e) {
            throw new RuntimeException("Unexpected internal error occurred", e);
        }
    }

    /**
     * @return a cursor with the Collection Info for all the available providers.
     */
    public static Cursor queryCollectionInfo() {
        try {
            final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
            final String[] columnNames = Arrays
                    .stream(PickerSQLConstants.CollectionInfoResponse.values())
                    .map(PickerSQLConstants.CollectionInfoResponse::getColumnName)
                    .toArray(String[]::new);
            final MatrixCursor matrixCursor = new MatrixCursor(columnNames, /*initialCapacity */ 2);
            Bundle extras = new Bundle();
            matrixCursor.setExtras(extras);
            final ProviderCollectionInfo localCollectionInfo =
                    syncController.getLocalProviderLatestCollectionInfo();
            addCollectionInfoToCursor(
                    matrixCursor,
                    localCollectionInfo
            );

            final ProviderCollectionInfo cloudCollectionInfo =
                    syncController.getCloudProviderLatestCollectionInfo();
            if (cloudCollectionInfo != null
                    && syncController.shouldQueryCloudMedia(cloudCollectionInfo.getAuthority())) {
                addCollectionInfoToCursor(
                        matrixCursor,
                        cloudCollectionInfo
                );
            }

            return matrixCursor;
        } catch (IllegalStateException e) {
            throw new RuntimeException("Unexpected internal error occurred", e);
        }
    }

    private static void addAvailableProvidersToCursor(
            @NonNull MatrixCursor cursor,
            @NonNull String authority,
            @NonNull MediaSource source,
            @UserIdInt int uid,
            @Nullable String displayName) {
        cursor.newRow()
                .add(PickerSQLConstants.AvailableProviderResponse.AUTHORITY.getColumnName(),
                        authority)
                .add(PickerSQLConstants.AvailableProviderResponse.MEDIA_SOURCE.getColumnName(),
                        source.name())
                .add(PickerSQLConstants.AvailableProviderResponse.UID.getColumnName(), uid)
                .add(PickerSQLConstants.AvailableProviderResponse.DISPLAY_NAME.getColumnName(),
                        displayName);
    }

    private static void addCollectionInfoToCursor(
            @NonNull MatrixCursor cursor,
            @NonNull ProviderCollectionInfo providerCollectionInfo) {
        if (providerCollectionInfo != null) {
            cursor.newRow()
                    .add(PickerSQLConstants.CollectionInfoResponse.AUTHORITY.getColumnName(),
                            providerCollectionInfo.getAuthority())
                    .add(PickerSQLConstants.CollectionInfoResponse.COLLECTION_ID.getColumnName(),
                            providerCollectionInfo.getCollectionId())
                    .add(PickerSQLConstants.CollectionInfoResponse.ACCOUNT_NAME.getColumnName(),
                            providerCollectionInfo.getAccountName());

            Bundle extras = cursor.getExtras();
            extras.putParcelable(providerCollectionInfo.getAuthority(),
                    providerCollectionInfo.getAccountConfigurationIntent());
        }
    }

    /**
     * @return a Bundle with the details of the requested cloud provider.
     */
    public static Bundle getCloudProviderDetails(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    /**
     * Returns a cursor for media filtered by ids based on input URIs.
     */
    public static Cursor queryMediaForPreSelection(@NonNull Context appContext, Bundle queryArgs) {
        final MediaQueryForPreSelection query = new MediaQueryForPreSelection(queryArgs);
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
        waitForOngoingSync(appContext, effectiveLocalAuthority, effectiveCloudAuthority,
                query.getIntentAction());

        query.processUrisForSelection(query.getPreSelectionUris(), effectiveLocalAuthority,
                effectiveCloudAuthority, effectiveCloudAuthority == null, appContext,
                query.getCallingPackageUid());

        return PickerMediaDatabaseUtil.queryPreSelectedMedia(
                appContext,
                syncController,
                query,
                effectiveLocalAuthority,
                effectiveCloudAuthority
        );
    }
}
