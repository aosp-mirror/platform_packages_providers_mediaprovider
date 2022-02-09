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

package com.android.providers.media.photopicker.data;

import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.MediaStore.PickerMediaColumns;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.DatabaseUtils.replaceMatchAnyChar;
import static com.android.providers.media.util.FileUtils.buildPrimaryVolumeFile;
import static com.android.providers.media.util.SyntheticPathUtils.getPickerRelativePath;

import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.model.Category;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the picker db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class PickerDbFacade {
    private final Object mLock = new Object();
    private final Context mContext;
    private final SQLiteDatabase mDatabase;
    private final String mLocalProvider;
    private String mCloudProvider;

    public PickerDbFacade(Context context) {
        this(context, PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
    }

    @VisibleForTesting
    public PickerDbFacade(Context context, String localProvider) {
        this(context, localProvider, new PickerDatabaseHelper(context));
    }

    @VisibleForTesting
    public PickerDbFacade(Context context, String localProvider, PickerDatabaseHelper dbHelper) {
        mContext = context;
        mLocalProvider = localProvider;
        mDatabase = dbHelper.getWritableDatabase();
    }

    private static final String TAG = "PickerDbFacade";

    private static final int RETRY = 0;
    private static final int SUCCESS = 1;
    private static final int FAIL = -1;

    private static final String TABLE_MEDIA = "media";
    private static final String PICKER_PATH = buildPrimaryVolumeFile(MediaStore.MY_USER_ID,
            getPickerRelativePath()).getAbsolutePath();

    @VisibleForTesting
    public static final String KEY_ID = "_id";
    @VisibleForTesting
    public static final String KEY_LOCAL_ID = "local_id";
    @VisibleForTesting
    public static final String KEY_CLOUD_ID = "cloud_id";
    @VisibleForTesting
    public static final String KEY_IS_VISIBLE = "is_visible";
    @VisibleForTesting
    public static final String KEY_DATE_TAKEN_MS = "date_taken_ms";
    @VisibleForTesting
    public static final String KEY_GENERATION_MODIFIED = "generation_modified";
    @VisibleForTesting
    public static final String KEY_SIZE_BYTES = "size_bytes";
    @VisibleForTesting
    public static final String KEY_DURATION_MS = "duration_ms";
    @VisibleForTesting
    public static final String KEY_MIME_TYPE = "mime_type";
    @VisibleForTesting
    public static final String KEY_STANDARD_MIME_TYPE_EXTENSION = "standard_mime_type_extension";
    @VisibleForTesting
    public static final String KEY_IS_FAVORITE = "is_favorite";

    @VisibleForTesting
    public static final String IMAGE_FILE_EXTENSION = ".jpg";
    @VisibleForTesting
    public static final String VIDEO_FILE_EXTENSION = ".mp4";

    private static final String WHERE_ID = KEY_ID + " = ?";
    private static final String WHERE_LOCAL_ID = KEY_LOCAL_ID + " = ?";
    private static final String WHERE_CLOUD_ID = KEY_CLOUD_ID + " = ?";
    private static final String WHERE_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NULL";
    private static final String WHERE_NOT_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NOT NULL";
    private static final String WHERE_IS_VISIBLE = KEY_IS_VISIBLE + " = 1";
    private static final String WHERE_MIME_TYPE = KEY_MIME_TYPE + " LIKE ? ";
    private static final String WHERE_IS_FAVORITE = KEY_IS_FAVORITE + " = 1";
    private static final String WHERE_SIZE_BYTES = KEY_SIZE_BYTES + " <= ?";
    private static final String WHERE_DATE_TAKEN_MS_AFTER =
            String.format("%s > ? OR (%s = ? AND %s > ?)",
                    KEY_DATE_TAKEN_MS, KEY_DATE_TAKEN_MS, KEY_ID);
    private static final String WHERE_DATE_TAKEN_MS_BEFORE =
            String.format("%s < ? OR (%s = ? AND %s < ?)",
                    KEY_DATE_TAKEN_MS, KEY_DATE_TAKEN_MS, KEY_ID);

    private static final String[] PROJECTION_ALBUM_CURSOR = new String[] {
        CloudMediaProviderContract.AlbumColumns.ID,
        CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
        CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
        CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
        CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
        CloudMediaProviderContract.AlbumColumns.TYPE
    };

    private static final String[] PROJECTION_ALBUM_DB = new String[] {
        "COUNT(" + KEY_ID + ") AS " + CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
        "MAX(" + KEY_DATE_TAKEN_MS + ") AS "
        + CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
        String.format("IFNULL(%s, %s) AS %s", KEY_CLOUD_ID,
                KEY_LOCAL_ID, CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID)
    };

    // Matches all media including cloud+local, cloud-only and local-only
    private static final SQLiteQueryBuilder QB_MATCH_ALL = createMediaQueryBuilder();
    // Matches media with id
    private static final SQLiteQueryBuilder QB_MATCH_ID = createIdMediaQueryBuilder();
    // Matches media with local_id including cloud+local and local-only
    private static final SQLiteQueryBuilder QB_MATCH_LOCAL = createLocalMediaQueryBuilder();
    // Matches cloud media including cloud+local and cloud-only
    private static final SQLiteQueryBuilder QB_MATCH_CLOUD = createCloudMediaQueryBuilder();
    // Matches all visible media including cloud+local, cloud-only and local-only
    private static final SQLiteQueryBuilder QB_MATCH_VISIBLE = createVisibleMediaQueryBuilder();
    // Matches visible media with local_id including cloud+local and local-only
    private static final SQLiteQueryBuilder QB_MATCH_VISIBLE_LOCAL =
            createVisibleLocalMediaQueryBuilder();
    // Matches stricly local-only media
    private static final SQLiteQueryBuilder QB_MATCH_LOCAL_ONLY =
            createLocalOnlyMediaQueryBuilder();

    private static final ContentValues CONTENT_VALUE_VISIBLE = new ContentValues();
    private static final ContentValues CONTENT_VALUE_HIDDEN = new ContentValues();

    static {
        CONTENT_VALUE_VISIBLE.put(KEY_IS_VISIBLE, 1);
        CONTENT_VALUE_HIDDEN.putNull(KEY_IS_VISIBLE);
    }

    /**
     * Sets the cloud provider to be returned after querying the picker db
     * If null, cloud media will be excluded from all queries.
     */
    public void setCloudProvider(String authority) {
        synchronized (mLock) {
            mCloudProvider = authority;
        }
    }

    /**
     * Returns the cloud provider that will be returned after querying the picker db
     */
    @VisibleForTesting
    public String getCloudProvider() {
        synchronized (mLock) {
            return mCloudProvider;
        }
    }

    public String getLocalProvider() {
        return mLocalProvider;
    }

    /**
     * Returns {@link DbWriteOperation} to add media belonging to {@code authority} into the picker
     * db.
     */
    public DbWriteOperation beginAddMediaOperation(String authority) {
        return new AddMediaOperation(mDatabase, isLocal(authority));
    }

    /**
     * Returns {@link DbWriteOperation} to remove media belonging to {@code authority} from the
     * picker db.
     */
    public DbWriteOperation beginRemoveMediaOperation(String authority) {
        return new RemoveMediaOperation(mDatabase, isLocal(authority));
    }

    /**
     * Returns {@link DbWriteOperation} to clear local media or all cloud media from the picker
     * db.
     *
     * @param authority to determine whether local or cloud media should be cleared
     */
    public DbWriteOperation beginResetMediaOperation(String authority) {
        return new ResetMediaOperation(mDatabase, isLocal(authority));
    }

    /**
     * Represents an atomic write operation to the picker database.
     *
     * <p>This class is not thread-safe and is meant to be used within a single thread only.
     */
    public static abstract class DbWriteOperation implements AutoCloseable {

        private final SQLiteDatabase mDatabase;
        private final boolean mIsLocal;

        private boolean mIsSuccess = false;

        private DbWriteOperation(SQLiteDatabase database, boolean isLocal) {
            mDatabase = database;
            mIsLocal = isLocal;
            mDatabase.beginTransaction();
        }

        /*
         * Execute the write operation.
         *
         * @param cursor containing items to add/remove
         * @return {@link WriteResult} indicating success/failure and the number of {@code cursor}
         *          items that were inserted/updated/deleted in the picker db
         * @throws {@link IllegalStateException} if no DB transaction is active
         */
        public int execute(@Nullable Cursor cursor) {
            if (!mDatabase.inTransaction()) {
                throw new IllegalStateException("No ongoing DB transaction.");
            }
            return executeInternal(cursor);
        }

        public void setSuccess() {
            mIsSuccess = true;
        }

        @Override
        public void close() {
            if (mDatabase.inTransaction()) {
                if (mIsSuccess) {
                    mDatabase.setTransactionSuccessful();
                } else {
                    Log.w(TAG, "DB write transaction failed.");
                }
                mDatabase.endTransaction();
            } else {
                throw new IllegalStateException("close() has already been called previously.");
            }
        }

        abstract int executeInternal(@Nullable Cursor cursor);

        SQLiteDatabase getDatabase() {
            return mDatabase;
        }

        boolean isLocal() {
            return mIsLocal;
        }

        int updateMedia(SQLiteQueryBuilder qb, ContentValues values,
                String[] selectionArgs) {
            try {
                if (qb.update(mDatabase, values, /* selection */ null, selectionArgs) > 0) {
                    return SUCCESS;
                } else {
                    Log.d(TAG, "Failed to update picker db media. ContentValues: " + values);
                    return FAIL;
                }
            } catch (SQLiteConstraintException e) {
                Log.d(TAG, "Failed to update picker db media. ContentValues: " + values, e);
                return RETRY;
            }
        }

        String querySingleMedia(SQLiteQueryBuilder qb, String[] projection,
                String[] selectionArgs, int columnIndex) {
            try (Cursor cursor = qb.query(mDatabase, projection, /* selection */ null,
                    selectionArgs, /* groupBy */ null, /* having */ null,
                    /* orderBy */ null)) {
                if (cursor.moveToFirst()) {
                    return cursor.getString(columnIndex);
                }
            }

            return null;
        }
    }

    private static final class AddMediaOperation extends DbWriteOperation {

        private AddMediaOperation(SQLiteDatabase database, boolean isLocal) {
            super(database, isLocal);
        }

        @Override
        int executeInternal(@Nullable Cursor cursor) {
            final boolean isLocal = isLocal();
            final SQLiteQueryBuilder qb = isLocal ? QB_MATCH_LOCAL_ONLY : QB_MATCH_CLOUD;
            int counter = 0;

            while (cursor.moveToNext()) {
                ContentValues values = cursorToContentValue(cursor, isLocal);

                String[] upsertArgs = {values.getAsString(isLocal ?
                        KEY_LOCAL_ID : KEY_CLOUD_ID)};
                if (upsertMedia(qb, values, upsertArgs) == SUCCESS) {
                    counter++;
                    continue;
                }

                // Because we want to prioritize visible local media over visible cloud media,
                // we do the following if the upsert above failed
                if (isLocal) {
                    // For local syncs, we attempt hiding the visible cloud media
                    String cloudId = getVisibleCloudIdFromDb(values.getAsString(KEY_LOCAL_ID));
                    demoteCloudMediaToHidden(cloudId);
                } else {
                    // For cloud syncs, we prepare an upsert as hidden cloud media
                    values.putNull(KEY_IS_VISIBLE);
                }

                // Now attempt upsert again, this should succeed
                if (upsertMedia(qb, values, upsertArgs) == SUCCESS) {
                    counter++;
                }
            }
            return counter;
        }

        private int insertMedia(ContentValues values) {
            try {
                if (QB_MATCH_ALL.insert(getDatabase(), values) > 0) {
                    return SUCCESS;
                } else {
                    Log.d(TAG, "Failed to insert picker db media. ContentValues: " + values);
                    return FAIL;
                }
            } catch (SQLiteConstraintException e) {
                Log.d(TAG, "Failed to insert picker db media. ContentValues: " + values, e);
                return RETRY;
            }
        }

        private int upsertMedia(SQLiteQueryBuilder qb,
                ContentValues values, String[] selectionArgs) {
            int res = insertMedia(values);
            if (res == RETRY) {
                // Attempt equivalent of CONFLICT_REPLACE resolution
                Log.d(TAG, "Retrying failed insert as update. ContentValues: " + values);
                res = updateMedia(qb, values, selectionArgs);
            }

            return res;
        }

        private void demoteCloudMediaToHidden(@Nullable String cloudId) {
            if (cloudId == null) {
                return;
            }

            final String[] updateArgs = new String[] {cloudId};
            if (updateMedia(QB_MATCH_CLOUD, CONTENT_VALUE_HIDDEN, updateArgs) == SUCCESS) {
                Log.d(TAG, "Demoted picker db media item to hidden. CloudId: " + cloudId);
            }
        }

        private String getVisibleCloudIdFromDb(String localId) {
            final String[] cloudIdProjection = new String[] {KEY_CLOUD_ID};
            final String[] queryArgs = new String[] {localId};
            return querySingleMedia(QB_MATCH_VISIBLE_LOCAL, cloudIdProjection, queryArgs,
                    /* columnIndex */ 0);
        }
    }

    private static final class RemoveMediaOperation extends DbWriteOperation {

        private RemoveMediaOperation(SQLiteDatabase database, boolean isLocal) {
            super(database, isLocal);
        }

        @Override
        int executeInternal(@Nullable Cursor cursor) {
            final boolean isLocal = isLocal();
            final SQLiteQueryBuilder qb = isLocal ? QB_MATCH_LOCAL_ONLY : QB_MATCH_CLOUD;

            int counter = 0;

            while (cursor.moveToNext()) {
                // Need to fetch the local_id before delete because for cloud items
                // we need a db query to fetch the local_id matching the id received from
                // cursor (cloud_id).
                final String localId = getLocalIdFromCursorOrDb(cursor, isLocal);

                // Delete cloud/local row
                final int idIndex = cursor.getColumnIndex(
                        CloudMediaProviderContract.MediaColumns.ID);
                final String[] deleteArgs = {cursor.getString(idIndex)};
                if (qb.delete(getDatabase(), /* selection */ null, deleteArgs) > 0) {
                    counter++;
                }

                promoteCloudMediaToVisible(localId);
            }

            return counter;
        }

        private void promoteCloudMediaToVisible(@Nullable String localId) {
            if (localId == null) {
                return;
            }

            final String[] idProjection = new String[] {KEY_ID};
            final String[] queryArgs = {localId};
            // First query for an exact row id matching the criteria for promotion so that we don't
            // attempt promoting multiple hidden cloud rows matching the |localId|
            final String id = querySingleMedia(QB_MATCH_LOCAL, idProjection, queryArgs,
                    /* columnIndex */ 0);
            if (id == null) {
                Log.w(TAG, "Unable to promote cloud media with localId: " + localId);
                return;
            }

            final String[] updateArgs = {id};
            if (updateMedia(QB_MATCH_ID, CONTENT_VALUE_VISIBLE, updateArgs) == SUCCESS) {
                Log.d(TAG, "Promoted picker db media item to visible. LocalId: " + localId);
            }
        }

        private String getLocalIdFromCursorOrDb(Cursor cursor, boolean isLocal) {
            final String id = cursor.getString(0);

            if (isLocal) {
                // For local, id in cursor is already local_id
                return id;
            } else {
                // For cloud, we need to query db with cloud_id from cursor to fetch local_id
                final String[] localIdProjection = new String[] {KEY_LOCAL_ID};
                final String[] queryArgs = new String[] {id};
                return querySingleMedia(QB_MATCH_CLOUD, localIdProjection, queryArgs,
                        /* columnIndex */ 0);
            }
        }
    }

    private static final class ResetMediaOperation extends DbWriteOperation {

        private ResetMediaOperation(SQLiteDatabase database, boolean isLocal) {
            super(database, isLocal);
        }

        @Override
        int executeInternal(@Nullable Cursor unused) {
            final boolean isLocal = isLocal();
            final SQLiteQueryBuilder qb = createMediaQueryBuilder();

            if (isLocal) {
                qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
            } else {
                qb.appendWhereStandalone(WHERE_NOT_NULL_CLOUD_ID);
            }

            SQLiteDatabase database = getDatabase();
            int counter = qb.delete(database, /* selection */ null, /* selectionArgs */ null);

            if (isLocal) {
                // If we reset local media, we need to promote cloud media items
                // Ignore conflicts in case we have multiple cloud_ids mapped to the
                // same local_id. Promoting either is fine.
                database.updateWithOnConflict(TABLE_MEDIA, CONTENT_VALUE_VISIBLE, /* where */ null,
                        /* whereClause */ null, SQLiteDatabase.CONFLICT_IGNORE);
            }

            return counter;
        }
    }

    /** Filter for {@link #queryMedia} to modify returned results */
    public static class QueryFilter {
        private final int limit;
        private final long dateTakenBeforeMs;
        private final long dateTakenAfterMs;
        private final long id;
        private final long sizeBytes;
        private final String mimeType;
        private final boolean isFavorite;

        private QueryFilter(int limit, long dateTakenBeforeMs, long dateTakenAfterMs, long id,
                long sizeBytes, String mimeType, boolean isFavorite) {
            this.limit = limit;
            this.dateTakenBeforeMs = dateTakenBeforeMs;
            this.dateTakenAfterMs = dateTakenAfterMs;
            this.id = id;
            this.sizeBytes = sizeBytes;
            this.mimeType = mimeType;
            this.isFavorite = isFavorite;
        }
    }

    /** Builder for {@link Query} filter. */
    public static class QueryFilterBuilder {
        public static final long LONG_DEFAULT = -1;
        public static final String STRING_DEFAULT = null;
        public static final boolean BOOLEAN_DEFAULT = false;

        public static final int LIMIT_DEFAULT = 1000;

        private final int limit;
        private long dateTakenBeforeMs = LONG_DEFAULT;
        private long dateTakenAfterMs = LONG_DEFAULT;
        private long id = LONG_DEFAULT;
        private long sizeBytes = LONG_DEFAULT;
        private String mimeType = STRING_DEFAULT;
        private boolean isFavorite = BOOLEAN_DEFAULT;

        public QueryFilterBuilder(int limit) {
            this.limit = limit;
        }

        public QueryFilterBuilder setDateTakenBeforeMs(long dateTakenBeforeMs) {
            this.dateTakenBeforeMs = dateTakenBeforeMs;
            return this;
        }

        public QueryFilterBuilder setDateTakenAfterMs(long dateTakenAfterMs) {
            this.dateTakenAfterMs = dateTakenAfterMs;
            return this;
        }

        /**
         * The {@code id} helps break ties with db rows having the same {@code dateTakenAfterMs} or
         * {@code dateTakenBeforeMs}.
         *
         * If {@code dateTakenAfterMs} is specified, all returned items are either strictly more
         * recent than {@code dateTakenAfterMs} or have a picker db id strictly greater than
         * {@code id} for ties.
         *
         * If {@code dateTakenBeforeMs} is specified, all returned items are either strictly older
         * than {@code dateTakenBeforeMs} or have a picker db id strictly less than {@code id}
         * for ties.
         */
        public QueryFilterBuilder setId(long id) {
            this.id = id;
            return this;
        }

        public QueryFilterBuilder setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public QueryFilterBuilder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /**
         * If {@code isFavorite} is {@code true}, the {@link QueryFilter} returns only
         * favorited items, however, if it is {@code false}, it returns all items including
         * favorited and non-favorited items.
         */
        public QueryFilterBuilder setIsFavorite(boolean isFavorite) {
            this.isFavorite = isFavorite;
            return this;
        }

        public QueryFilter build() {
            return new QueryFilter(limit, dateTakenBeforeMs, dateTakenAfterMs, id, sizeBytes,
                    mimeType, isFavorite);
        }
    }

    /**
     * Returns sorted and deduped cloud and local media items from the picker db.
     *
     * Returns a {@link Cursor} containing picker db media rows with columns as
     * {@link CloudMediaProviderContract#MediaColumns}.
     *
     * The result is sorted in reverse chronological order, i.e. newest first, up to a maximum of
     * {@code limit}. They can also be filtered with {@code query}.
     */
    public Cursor queryMediaForUi(QueryFilter query) {
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        final String[] selectionArgs = buildSelectionArgs(qb, query);

        return queryMediaForUi(qb, selectionArgs, query.limit);
    }

    /**
     * Returns an individual cloud or local item from the picker db matching {@code authority} and
     * {@code mediaId}.
     *
     * Returns a {@link Cursor} containing picker db media rows with columns as {@code projection},
     * a subset of {@link PickerMediaColumns}.
     */
    public Cursor queryMediaIdForApps(String authority, String mediaId,
            @NonNull String[] projection) {
        final String[] selectionArgs = new String[] { mediaId };
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        if (isLocal(authority)) {
            qb.appendWhereStandalone(WHERE_LOCAL_ID);
        } else {
            qb.appendWhereStandalone(WHERE_CLOUD_ID);
        }

        synchronized (mLock) {
            if (authority.equals(mLocalProvider) || authority.equals(mCloudProvider)) {
                return qb.query(mDatabase, getMediaStoreProjectionLocked(projection),
                        /* selection */ null, selectionArgs, /* groupBy */ null, /* having */ null,
                        /* orderBy */ null, /* limitStr */ null);
            }
        }

        return null;
    }

    /** Returns {@code null} if there are no favorited items matching {@code query} */
    public Cursor getFavoriteAlbum(QueryFilter query) {
        final String[] selectionArgs;
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_IS_FAVORITE);
        if (query.mimeType != null) {
            qb.appendWhereStandalone(WHERE_MIME_TYPE);
            selectionArgs = new String [] { query.mimeType.replace('*', '%') };
        } else {
            selectionArgs = null;
        }

        Cursor cursor = qb.query(mDatabase, PROJECTION_ALBUM_DB, /* selection */ null,
                selectionArgs, /* groupBy */ null, /* having */ null,
                /* orderBy */ null, /* limit */ null);

        if (cursor == null || !cursor.moveToFirst()) {
            return null;
        }

        long count = getCursorLong(cursor, CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);
        if (count == 0) {
            return null;
        }

        final MatrixCursor c = new MatrixCursor(PROJECTION_ALBUM_CURSOR);
        final String[] projectionValue = new String[] {
            Category.CATEGORY_FAVORITES,
            getCursorString(cursor, CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS),
            Category.getCategoryName(mContext, Category.CATEGORY_FAVORITES),
            String.valueOf(count),
            getCursorString(cursor, CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID),
            CloudMediaProviderContract.AlbumColumns.TYPE_FAVORITES
        };
        c.addRow(projectionValue);
        return c;
    }

    public static boolean isPickerDbEnabled() {
        return SystemProperties.getBoolean("sys.photopicker.pickerdb.enabled", true);
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private Cursor queryMediaForUi(SQLiteQueryBuilder qb, String[] selectionArgs,
            int limit) {
        // Use the <table>.<column> form to order _id to avoid ordering against the projection '_id'
        final String orderBy = "date_taken_ms DESC," + TABLE_MEDIA + "._id DESC";
        final String limitStr = String.valueOf(limit);

        // Hold lock while checking the cloud provider and querying so that cursor extras containing
        // the cloud provider is consistent with the cursor results and doesn't race with
        // #setCloudProvider
        synchronized (mLock) {
            if (mCloudProvider == null) {
                // If cloud provider is null, skip all cloud items in the picker db
                qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
            }

            return qb.query(mDatabase, getCloudMediaProjectionLocked(), /* selection */ null,
                    selectionArgs, /* groupBy */ null, /* having */ null, orderBy, limitStr);
        }
    }

    private String[] getCloudMediaProjectionLocked() {
        return new String[] {
            getProjectionAuthorityLocked(),
            getProjectionDataLocked(MediaColumns.DATA),
            getProjectionId(MediaColumns.ID),
            getProjectionSimple(KEY_DATE_TAKEN_MS, MediaColumns.DATE_TAKEN_MILLIS),
            getProjectionSimple(KEY_GENERATION_MODIFIED, MediaColumns.GENERATION_MODIFIED),
            getProjectionSimple(KEY_SIZE_BYTES, MediaColumns.SIZE_BYTES),
            getProjectionSimple(KEY_DURATION_MS, MediaColumns.DURATION_MILLIS),
            getProjectionSimple(KEY_MIME_TYPE, MediaColumns.MIME_TYPE),
            getProjectionSimple(KEY_STANDARD_MIME_TYPE_EXTENSION,
                    MediaColumns.STANDARD_MIME_TYPE_EXTENSION),
        };
    }

    private String[] getMediaStoreProjectionLocked(String[] columns) {
        final String[] projection = new String[columns.length];

        for (int i = 0; i < projection.length; i++) {
            switch (columns[i]) {
                case PickerMediaColumns.DATA:
                    projection[i] = getProjectionDataLocked(PickerMediaColumns.DATA);
                    break;
                case PickerMediaColumns.DISPLAY_NAME:
                    projection[i] = getProjectionSimple(getDisplayNameSql(),
                            PickerMediaColumns.DISPLAY_NAME);
                    break;
                case PickerMediaColumns.MIME_TYPE:
                    projection[i] = getProjectionSimple(KEY_MIME_TYPE,
                            PickerMediaColumns.MIME_TYPE);
                    break;
                case PickerMediaColumns.DATE_TAKEN:
                    projection[i] = getProjectionSimple(KEY_DATE_TAKEN_MS,
                            PickerMediaColumns.DATE_TAKEN);
                    break;
                case PickerMediaColumns.SIZE:
                    projection[i] = getProjectionSimple(KEY_SIZE_BYTES, PickerMediaColumns.SIZE);
                    break;
                case PickerMediaColumns.DURATION_MILLIS:
                    projection[i] = getProjectionSimple(KEY_DURATION_MS,
                            PickerMediaColumns.DURATION_MILLIS);
                    break;
                default:
                    projection[i] = "";
                    Log.w(TAG, "Unexpected MediaStore column: " + columns[i]);
            }
        }

        return projection;
    }

    private String getProjectionAuthorityLocked() {
        // Note that we prefer cloud_id over local_id here. It's important to remember that this
        // logic is for computing the projection and doesn't affect the filtering of results which
        // has already been done and ensures that only is_visible=true items are returned.
        // Here, we need to distinguish between cloud+local and local-only items to determine the
        // correct authority. Checking whether cloud_id IS NULL distinguishes the former from the
        // latter.
        return String.format("CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END AS %s",
                KEY_CLOUD_ID, mLocalProvider, mCloudProvider, MediaColumns.AUTHORITY);
    }

    private String getProjectionDataLocked(String asColumn) {
        // _data format:
        // /storage/emulated/<user-id>/.transforms/synthetic/<authority>/media/<display-name>
        // See PickerUriResolver#getMediaUri
        final String authority = String.format("CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                KEY_CLOUD_ID, mLocalProvider, mCloudProvider);
        final String fullPath = "'" + PICKER_PATH + "/'"
                + "||" + authority
                + "||" + "'/" + CloudMediaProviderContract.URI_PATH_MEDIA + "/'"
                + "||" + getDisplayNameSql();

        return String.format("%s AS %s", fullPath, asColumn);
    }

    private String getProjectionId(String asColumn) {
        // We prefer cloud_id first and it only matters for cloud+local items. For those, the row
        // will already be associated with a cloud authority, see #getProjectionAuthorityLocked.
        // Note that hidden cloud+local items will not be returned in the query, so there's no
        // concern of preferring the cloud_id in a cloud+local item over the local_id in a
        // local-only item.
        return String.format("IFNULL(%s, %s) AS %s", KEY_CLOUD_ID, KEY_LOCAL_ID, asColumn);
    }

    private static String getProjectionSimple(String dbColumn, String column) {
        return String.format("%s AS %s", dbColumn, column);
    }

    private String getDisplayNameSql() {
        // _display_name format:
        // <media-id>.<file-extension>
        // See comment in #getProjectionAuthorityLocked for why cloud_id is preferred over local_id
        final String mediaId = String.format("IFNULL(%s, %s)", KEY_CLOUD_ID, KEY_LOCAL_ID);
        // TODO(b/195009139): Add .gif fileextension support
        final String fileExtension =
                String.format("CASE WHEN %s LIKE 'image/%%' THEN '%s' ELSE '%s' END",
                        KEY_MIME_TYPE, IMAGE_FILE_EXTENSION, VIDEO_FILE_EXTENSION);

        return mediaId + "||" + fileExtension;
    }

    private static ContentValues cursorToContentValue(Cursor cursor, boolean isLocal) {
        final ContentValues values = new ContentValues();
        values.put(KEY_IS_VISIBLE, 1);

        final int count = cursor.getColumnCount();
        for (int index = 0; index < count; index++) {
            String key = cursor.getColumnName(index);
            switch (key) {
                case CloudMediaProviderContract.MediaColumns.ID:
                    if (isLocal) {
                        values.put(KEY_LOCAL_ID, cursor.getString(index));
                    } else {
                        values.put(KEY_CLOUD_ID, cursor.getString(index));
                    }
                    break;
                case CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI:
                    String uriString = cursor.getString(index);
                    if (uriString != null) {
                        Uri uri = Uri.parse(uriString);
                        values.put(KEY_LOCAL_ID, ContentUris.parseId(uri));
                    }
                    break;
                case CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS:
                    values.put(KEY_DATE_TAKEN_MS, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.GENERATION_MODIFIED:
                    values.put(KEY_GENERATION_MODIFIED, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.SIZE_BYTES:
                    values.put(KEY_SIZE_BYTES, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.MIME_TYPE:
                    values.put(KEY_MIME_TYPE, cursor.getString(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION:
                    int standardMimeTypeExtension = cursor.getInt(index);
                    if (isValidStandardMimeTypeExtension(standardMimeTypeExtension)) {
                        values.put(KEY_STANDARD_MIME_TYPE_EXTENSION, standardMimeTypeExtension);
                    } else {
                        throw new IllegalArgumentException("Invalid standard mime type extension");
                    }
                    break;
                case CloudMediaProviderContract.MediaColumns.DURATION_MILLIS:
                    values.put(KEY_DURATION_MS, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.IS_FAVORITE:
                    values.put(KEY_IS_FAVORITE, cursor.getInt(index));
                    break;
                default:
                    Log.w(TAG, "Unexpected cursor key: " + key);
            }
        }

        return values;
    }

    private static boolean isValidStandardMimeTypeExtension(int standardMimeTypeExtension) {
        switch (standardMimeTypeExtension) {
            case CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE:
            case CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF:
            case CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_MOTION_PHOTO:
            case CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_ANIMATED_WEBP:
                return true;
            default:
                return false;
        }
    }

    private static String[] buildSelectionArgs(SQLiteQueryBuilder qb, QueryFilter query) {
        List<String> selectArgs = new ArrayList<>();

        if (query.id >= 0) {
            if (query.dateTakenAfterMs >= 0) {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_AFTER);
                // Add date args twice because the sql statement evaluates date twice
                selectArgs.add(String.valueOf(query.dateTakenAfterMs));
                selectArgs.add(String.valueOf(query.dateTakenAfterMs));
            } else {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_BEFORE);
                // Add date args twice because the sql statement evaluates date twice
                selectArgs.add(String.valueOf(query.dateTakenBeforeMs));
                selectArgs.add(String.valueOf(query.dateTakenBeforeMs));
            }
            selectArgs.add(String.valueOf(query.id));
        }

        if (query.sizeBytes >= 0) {
            qb.appendWhereStandalone(WHERE_SIZE_BYTES);
            selectArgs.add(String.valueOf(query.sizeBytes));
        }

        if (query.mimeType != null) {
            qb.appendWhereStandalone(WHERE_MIME_TYPE);
            selectArgs.add(replaceMatchAnyChar(query.mimeType));
        }

        if (query.isFavorite) {
            qb.appendWhereStandalone(WHERE_IS_FAVORITE);
        }

        if (selectArgs.isEmpty()) {
            return null;
        }

        return selectArgs.toArray(new String[selectArgs.size()]);
    }

    private static SQLiteQueryBuilder createMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_MEDIA);

        return qb;
    }

    private static SQLiteQueryBuilder createLocalOnlyMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createLocalMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);

        return qb;
    }

    private static SQLiteQueryBuilder createLocalMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_LOCAL_ID);

        return qb;
    }

    private static SQLiteQueryBuilder createCloudMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_CLOUD_ID);

        return qb;
    }

    private static SQLiteQueryBuilder createIdMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_ID);

        return qb;
    }

    private static SQLiteQueryBuilder createVisibleMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_IS_VISIBLE);

        return qb;
    }

    private static SQLiteQueryBuilder createVisibleLocalMediaQueryBuilder() {
        SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        qb.appendWhereStandalone(WHERE_LOCAL_ID);

        return qb;
    }
}
