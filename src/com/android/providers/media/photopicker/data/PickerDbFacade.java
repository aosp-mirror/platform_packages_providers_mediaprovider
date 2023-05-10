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

import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.MediaStore.PickerMediaColumns;

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.DatabaseUtils.replaceMatchAnyChar;
import static com.android.providers.media.util.SyntheticPathUtils.getPickerRelativePath;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Trace;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the picker db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class PickerDbFacade {
    private static final String VIDEO_MIME_TYPES = "video/%";

    // TODO(b/278562157): If there is a dependency on
    //  {@link PickerSyncController#mCloudProviderLock}, always acquire
    //  {@link PickerSyncController#mCloudProviderLock} before {@link mLock} to avoid deadlock.
    @NonNull
    private final Object mLock = new Object();
    private final Context mContext;
    private final SQLiteDatabase mDatabase;
    private final String mLocalProvider;
    // This is the cloud provider the database is synced with. It can be set as null to disable
    // cloud queries when database is not in sync with the current cloud provider.
    @Nullable
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
    // Intentionally use /sdcard path so that the receiving app resolves it to it's per-user
    // external storage path, e.g. /storage/emulated/<userid>. That way FUSE cross-user access is
    // not required for picker paths sent across users
    private static final String PICKER_PATH = "/sdcard/" + getPickerRelativePath();
    private static final String TABLE_ALBUM_MEDIA = "album_media";

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
    public static final String KEY_SYNC_GENERATION = "sync_generation";
    @VisibleForTesting
    public static final String KEY_SIZE_BYTES = "size_bytes";
    @VisibleForTesting
    public static final String KEY_DURATION_MS = "duration_ms";
    @VisibleForTesting
    public static final String KEY_MIME_TYPE = "mime_type";
    public static final String KEY_STANDARD_MIME_TYPE_EXTENSION = "standard_mime_type_extension";
    @VisibleForTesting
    public static final String KEY_IS_FAVORITE = "is_favorite";
    @VisibleForTesting
    public static final String KEY_ALBUM_ID = "album_id";
    @VisibleForTesting
    public static final String KEY_HEIGHT = "height";
    @VisibleForTesting
    public static final String KEY_WIDTH = "width";
    @VisibleForTesting
    public static final String KEY_ORIENTATION = "orientation";

    private static final String WHERE_ID = KEY_ID + " = ?";
    private static final String WHERE_LOCAL_ID = KEY_LOCAL_ID + " = ?";
    private static final String WHERE_CLOUD_ID = KEY_CLOUD_ID + " = ?";
    private static final String WHERE_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NULL";
    private static final String WHERE_NOT_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NOT NULL";
    private static final String WHERE_NOT_NULL_LOCAL_ID = KEY_LOCAL_ID + " IS NOT NULL";
    private static final String WHERE_IS_VISIBLE = KEY_IS_VISIBLE + " = 1";
    private static final String WHERE_MIME_TYPE = KEY_MIME_TYPE + " LIKE ? ";
    private static final String WHERE_SIZE_BYTES = KEY_SIZE_BYTES + " <= ?";
    private static final String WHERE_DATE_TAKEN_MS_AFTER =
            String.format("%s > ? OR (%s = ? AND %s > ?)",
                    KEY_DATE_TAKEN_MS, KEY_DATE_TAKEN_MS, KEY_ID);
    private static final String WHERE_DATE_TAKEN_MS_BEFORE =
            String.format("%s < ? OR (%s = ? AND %s < ?)",
                    KEY_DATE_TAKEN_MS, KEY_DATE_TAKEN_MS, KEY_ID);
    private static final String WHERE_ALBUM_ID = KEY_ALBUM_ID  + " = ?";

    // This where clause returns all rows for media items that are local-only and are marked as
    // favorite.
    //
    // 'cloud_id' IS NULL AND 'is_favorite' = 1
    private static final String WHERE_FAVORITE_LOCAL_ONLY = String.format(
            "%s IS NULL AND %s = 1", KEY_CLOUD_ID, KEY_IS_FAVORITE);
    // This where clause returns all rows for media items that are cloud-only and are marked as
    // favorite.
    //
    // 'local_id' IS NULL AND 'is_favorite' = 1
    private static final String WHERE_FAVORITE_CLOUD_ONLY = String.format(
            "%s IS NULL AND %s = 1", KEY_LOCAL_ID, KEY_IS_FAVORITE);
    // This where clause returns all local rows from media items for which either local row is
    // marked as favorite or corresponding cloud row is marked as favorite.
    // E.g., Rows -
    // Row1 : local_id=1,    cloud_id=null, is_favorite=0
    // Row2 : local_id=2,    cloud_id=null, is_favorite=0
    // Row3 : local_id=3,    cloud_id=null, is_favorite=1
    // Row4 : local_id=4,    cloud_id=null, is_favorite=1
    // --
    // Row5 : local_id=2,    cloud_id=c1,   is_favorite=1
    // Row6 : local_id=3,    cloud_id=c2,   is_favorite=1
    // Row7 : local_id=null, cloud_id=c3,   is_favorite=1
    //
    // Returns -
    // Row2 : local_id=2,    cloud_id=null, is_favorite=0
    // Row3 : local_id=3,    cloud_id=null, is_favorite=1
    // Row4 : local_id=4,    cloud_id=null, is_favorite=1
    //
    // 'local_id' IN (SELECT 'local_id'
    //      FROM 'media'
    //      WHERE 'local_id' IS NOT NULL
    //      GROUP BY 'local_id'
    //      HAVING SUM('is_favorite') >= 1)
    private static final String WHERE_FAVORITE_LOCAL_PLUS_CLOUD = String.format(
            "%s IN (SELECT %s FROM %s WHERE %s IS NOT NULL GROUP BY %s HAVING SUM(%s) >= 1)",
            KEY_LOCAL_ID, KEY_LOCAL_ID, TABLE_MEDIA, KEY_LOCAL_ID, KEY_LOCAL_ID, KEY_IS_FAVORITE);
    // This where clause returns all rows for media items that are marked as favorite.
    // Note that this is different from "WHERE_FAVORITE_LOCAL_ONLY + WHERE_FAVORITE_CLOUD_ONLY"
    // because for local+cloud row with is_favorite=1 we need to pick corresponding local row.
    private static final String WHERE_FAVORITE_ALL = String.format(
            "( %s OR %s )", WHERE_FAVORITE_LOCAL_PLUS_CLOUD, WHERE_FAVORITE_CLOUD_ONLY);

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
    // Matches strictly local-only media
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
     * Returns {@link DbWriteOperation} to add album_media belonging to {@code authority}
     * into the picker db.
     */
    public DbWriteOperation beginAddAlbumMediaOperation(String authority, String albumId) {
        return new AddAlbumMediaOperation(mDatabase, isLocal(authority), albumId);
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
     * Returns {@link DbWriteOperation} to clear album media for a given albumId from the picker
     * db.
     *
     * <p>The {@link DbWriteOperation} clears local or cloud album based on {@code authority} and
     * {@code albumId}. If {@code albumId} is null, it clears all local or cloud albums based on
     * {@code authority}.
     *
     * @param authority to determine whether local or cloud media should be cleared
     */
    public DbWriteOperation beginResetAlbumMediaOperation(String authority, String albumId) {
        return new ResetAlbumOperation(mDatabase, isLocal(authority), albumId);
    }

    /**
     * Returns {@link UpdateMediaOperation} to update media belonging to {@code authority} in the
     * picker db.
     *
     * @param authority to determine whether local or cloud media should be updated
     */
    public UpdateMediaOperation beginUpdateMediaOperation(String authority) {
        return new UpdateMediaOperation(mDatabase, isLocal(authority));
    }

    /**
     * Represents an atomic write operation to the picker database.
     *
     * <p>This class is not thread-safe and is meant to be used within a single thread only.
     */
    public abstract static class DbWriteOperation implements AutoCloseable {

        private final SQLiteDatabase mDatabase;
        private final boolean mIsLocal;

        private boolean mIsSuccess = false;

        private DbWriteOperation(SQLiteDatabase database, boolean isLocal) {
            mDatabase = database;
            mIsLocal = isLocal;
            mDatabase.beginTransaction();
        }

        /**
         * Execute a write operation.
         *
         * @param cursor containing items to add/remove
         * @return number of {@code cursor} items that were inserted/updated/deleted in the db
         * @throws {@link IllegalStateException} if no DB transaction is active
         */
        public int execute(@Nullable Cursor cursor) {
            if (!mDatabase.inTransaction()) {
                throw new IllegalStateException("No ongoing DB transaction.");
            }
            final String traceSectionName = getClass().getSimpleName()
                    + ".execute[" + (mIsLocal ? "local" : "cloud") + ']';
            Trace.beginSection(traceSectionName);
            try {
                return executeInternal(cursor);
            } finally {
                Trace.endSection();
            }
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
                    Log.v(TAG, "Failed to update picker db media. ContentValues: " + values);
                    return FAIL;
                }
            } catch (SQLiteConstraintException e) {
                Log.v(TAG, "Failed to update picker db media. ContentValues: " + values, e);
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

    /**
     * Represents an atomic media update operation to the picker database.
     *
     * <p>This class is not thread-safe and is meant to be used within a single thread only.
     */
    public static final class UpdateMediaOperation extends DbWriteOperation {

        private UpdateMediaOperation(SQLiteDatabase database, boolean isLocal) {
            super(database, isLocal);
        }

        /**
         * Execute a media update operation.
         *
         * @param id id of the media to be updated
         * @param contentValues key-value pairs indicating fields to be updated for the media
         * @return boolean indicating success/failure of the update
         * @throws {@link IllegalStateException} if no DB transaction is active
         */
        public boolean execute(String id, ContentValues contentValues) {
            final SQLiteDatabase database = getDatabase();
            if (!database.inTransaction()) {
                throw new IllegalStateException("No ongoing DB transaction.");
            }

            final SQLiteQueryBuilder qb = isLocal() ? QB_MATCH_LOCAL_ONLY : QB_MATCH_CLOUD;
            return qb.update(database, contentValues, /* selection */ null, new String[] {id}) > 0;
        }

        @Override
        int executeInternal(@Nullable Cursor cursor) {
            throw new UnsupportedOperationException("Cursor updates are not supported.");
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
                    Log.v(TAG, "Failed to insert picker db media. ContentValues: " + values);
                    return FAIL;
                }
            } catch (SQLiteConstraintException e) {
                Log.v(TAG, "Failed to insert picker db media. ContentValues: " + values, e);
                return RETRY;
            }
        }

        private int upsertMedia(SQLiteQueryBuilder qb,
                ContentValues values, String[] selectionArgs) {
            int res = insertMedia(values);
            if (res == RETRY) {
                // Attempt equivalent of CONFLICT_REPLACE resolution
                Log.v(TAG, "Retrying failed insert as update. ContentValues: " + values);
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
        private final int mLimit;
        private final long mDateTakenBeforeMs;
        private final long mDateTakenAfterMs;
        private final long mId;
        private final String mAlbumId;
        private final long mSizeBytes;
        private final String[] mMimeTypes;
        private final boolean mIsFavorite;
        private final boolean mIsVideo;
        public boolean mIsLocalOnly;

        private QueryFilter(int limit, long dateTakenBeforeMs, long dateTakenAfterMs, long id,
                String albumId, long sizeBytes, String[] mimeTypes, boolean isFavorite,
                boolean isVideo, boolean isLocalOnly) {
            this.mLimit = limit;
            this.mDateTakenBeforeMs = dateTakenBeforeMs;
            this.mDateTakenAfterMs = dateTakenAfterMs;
            this.mId = id;
            this.mAlbumId = albumId;
            this.mSizeBytes = sizeBytes;
            this.mMimeTypes = mimeTypes;
            this.mIsFavorite = isFavorite;
            this.mIsVideo = isVideo;
            this.mIsLocalOnly = isLocalOnly;
        }
    }

    /** Builder for {@link Query} filter. */
    public static class QueryFilterBuilder {
        public static final long LONG_DEFAULT = -1;
        public static final String STRING_DEFAULT = null;
        public static final String[] STRING_ARRAY_DEFAULT = null;
        public static final boolean BOOLEAN_DEFAULT = false;

        public static final int LIMIT_DEFAULT = 1000;

        private final int limit;
        private long dateTakenBeforeMs = LONG_DEFAULT;
        private long dateTakenAfterMs = LONG_DEFAULT;
        private long id = LONG_DEFAULT;
        private String albumId = STRING_DEFAULT;
        private long sizeBytes = LONG_DEFAULT;
        private String[] mimeTypes = STRING_ARRAY_DEFAULT;
        private boolean isFavorite = BOOLEAN_DEFAULT;
        private boolean mIsVideo = BOOLEAN_DEFAULT;
        private boolean mIsLocalOnly = BOOLEAN_DEFAULT;

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
         * If {@code dateTakenAfterMs} is specified, all returned items are equal or more
         * recent than {@code dateTakenAfterMs} and have a picker db id equal or greater than
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
        public QueryFilterBuilder setAlbumId(String albumId) {
            this.albumId = albumId;
            return this;
        }

        public QueryFilterBuilder setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public QueryFilterBuilder setMimeTypes(String[] mimeTypes) {
            this.mimeTypes = mimeTypes;
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

        /**
         * If {@code isVideo} is {@code true}, the {@link QueryFilter} returns only
         * video items, however, if it is {@code false}, it returns all items including
         * video and non-video items.
         */
        public QueryFilterBuilder setIsVideo(boolean isVideo) {
            this.mIsVideo = isVideo;
            return this;
        }

        /**
         * If {@code isLocalOnly} is {@code true}, the {@link QueryFilter} returns only
         * local items.
         */
        public QueryFilterBuilder setIsLocalOnly(boolean isLocalOnly) {
            this.mIsLocalOnly = isLocalOnly;
            return this;
        }

        public QueryFilter build() {
            return new QueryFilter(limit, dateTakenBeforeMs, dateTakenAfterMs, id, albumId,
                    sizeBytes, mimeTypes, isFavorite, mIsVideo, mIsLocalOnly);
        }
    }

    /**
     * Returns sorted and deduped cloud and local media items from the picker db.
     *
     * Returns a {@link Cursor} containing picker db media rows with columns as
     * {@link CloudMediaProviderContract.MediaColumns}.
     *
     * The result is sorted in reverse chronological order, i.e. newest first, up to a maximum of
     * {@code limit}. They can also be filtered with {@code query}.
     */
    public Cursor queryMediaForUi(QueryFilter query) {
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        final String[] selectionArgs = buildSelectionArgs(qb, query);

        final String cloudProvider;
        synchronized (mLock) {
            // If the cloud sync is in progress or the cloud provider has changed but a sync has not
            // been completed and committed, {@link PickerDBFacade.mCloudProvider} will be
            // {@code null}.
            cloudProvider = mCloudProvider;
        }

        return queryMediaForUi(qb, selectionArgs, query.mLimit, TABLE_MEDIA, cloudProvider);
    }

    /**
     * Returns sorted cloud or local media items from the picker db for a given album (either cloud
     * or local).
     *
     * Returns a {@link Cursor} containing picker db media rows with columns as
     * {@link CloudMediaProviderContract#MediaColumns} except for is_favorites column because that
     * column is only used for fetching the Favorites album.
     *
     * The result is sorted in reverse chronological order, i.e. newest first, up to a maximum of
     * {@code limit}. They can also be filtered with {@code query}.
     */
    public Cursor queryAlbumMediaForUi(QueryFilter query, String authority) {
        final SQLiteQueryBuilder qb = createAlbumMediaQueryBuilder(isLocal(authority));
        final String[] selectionArgs = buildSelectionArgs(qb, query);

        return queryMediaForUi(qb, selectionArgs, query.mLimit, TABLE_ALBUM_MEDIA, authority);
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

        if (authority.equals(mLocalProvider)) {
            return queryMediaIdForAppsInternal(qb, projection, selectionArgs);
        }

        synchronized (mLock) {
            if (authority.equals(mCloudProvider)) {
                return queryMediaIdForAppsInternal(qb, projection, selectionArgs);
            }
        }

        return null;
    }

    private Cursor queryMediaIdForAppsInternal(@NonNull SQLiteQueryBuilder qb,
            @NonNull String[] projection, @NonNull String[] selectionArgs) {
        return qb.query(mDatabase, getMediaStoreProjectionLocked(projection),
                /* selection */ null, selectionArgs, /* groupBy */ null, /* having */ null,
                /* orderBy */ null, /* limitStr */ null);
    }

    /**
     * Returns empty {@link Cursor} if there are no items matching merged album constraints {@code
     * query}
     */
    public Cursor getMergedAlbums(QueryFilter query) {
        final MatrixCursor c = new MatrixCursor(AlbumColumns.ALL_PROJECTION);
        List<String> mergedAlbums = List.of(ALBUM_ID_FAVORITES, ALBUM_ID_VIDEOS);
        for (String albumId : mergedAlbums) {
            List<String> selectionArgs = new ArrayList<>();
            final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();

            if (query.mIsLocalOnly) {
                qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
            }

            if (albumId.equals(ALBUM_ID_FAVORITES)) {
                qb.appendWhereStandalone(getWhereForFavorite(query.mIsLocalOnly));
            } else if (albumId.equals(ALBUM_ID_VIDEOS)) {
                qb.appendWhereStandalone(WHERE_MIME_TYPE);
                selectionArgs.add("video/%");
            }
            addMimeTypesToQueryBuilderAndSelectionArgs(qb, selectionArgs, query.mMimeTypes);

            Cursor cursor = qb.query(mDatabase, getMergedAlbumProjection(), /* selection */ null,
                    selectionArgs.toArray(new String[0]), /* groupBy */ null, /* having */ null,
                    /* orderBy */ null, /* limit */ null);

            if (cursor == null || !cursor.moveToFirst()) {
                continue;
            }

            long count = getCursorLong(cursor, CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);
            if (count == 0) {
                continue;
            }

            final String[] projectionValue = new String[]{
                    /* albumId */ albumId,
                    getCursorString(cursor, AlbumColumns.DATE_TAKEN_MILLIS),
                    /* displayName */ albumId,
                    getCursorString(cursor, AlbumColumns.MEDIA_COVER_ID),
                    String.valueOf(count),
                    getCursorString(cursor, AlbumColumns.AUTHORITY),
            };
            c.addRow(projectionValue);
        }
        return c;
    }

    private String[] getMergedAlbumProjection() {
        return new String[] {
                "COUNT(" + KEY_ID + ") AS " + CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
                "MAX(" + KEY_DATE_TAKEN_MS + ") AS "
                        + CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
                String.format("IFNULL(%s, %s) AS %s", KEY_CLOUD_ID,
                        KEY_LOCAL_ID, CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID),
                // Note that we prefer cloud_id over local_id here. This logic is for computing the
                // projection and doesn't affect the filtering of results which has already been
                // done and ensures that only is_visible=true items are returned.
                // Here, we need to distinguish between cloud+local and local-only items to
                // determine the correct authority.
                String.format("CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END AS %s",
                        KEY_CLOUD_ID, mLocalProvider, mCloudProvider, AlbumColumns.AUTHORITY)
        };
    }

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private Cursor queryMediaForUi(SQLiteQueryBuilder qb, String[] selectionArgs,
            int limit, String tableName, String authority) {
        // Use the <table>.<column> form to order _id to avoid ordering against the projection '_id'
        final String orderBy = getOrderClause(tableName);
        final String limitStr = String.valueOf(limit);

        // Hold lock while checking the cloud provider and querying so that cursor extras containing
        // the cloud provider is consistent with the cursor results and doesn't race with
        // #setCloudProvider
        synchronized (mLock) {
            if (mCloudProvider == null || !Objects.equals(mCloudProvider, authority)) {
                // TODO(b/278086344): If cloud provider is null or has changed from what we received
                //  from the UI, skip all cloud items in the picker db.
                qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
            }

            return qb.query(mDatabase, getCloudMediaProjectionLocked(), /* selection */ null,
                    selectionArgs, /* groupBy */ null, /* having */ null, orderBy, limitStr);
        }
    }

    private static String getOrderClause(String tableName) {
        return "date_taken_ms DESC," + tableName + "._id DESC";
    }

    private String[] getCloudMediaProjectionLocked() {
        return new String[] {
            getProjectionAuthorityLocked(),
            getProjectionDataLocked(MediaColumns.DATA),
            getProjectionId(MediaColumns.ID),
            getProjectionSimple(KEY_DATE_TAKEN_MS, MediaColumns.DATE_TAKEN_MILLIS),
            getProjectionSimple(KEY_SYNC_GENERATION, MediaColumns.SYNC_GENERATION),
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
                    projection[i] =
                            getProjectionSimple(
                                    getDisplayNameSql(), PickerMediaColumns.DISPLAY_NAME);
                    break;
                case PickerMediaColumns.MIME_TYPE:
                    projection[i] =
                            getProjectionSimple(KEY_MIME_TYPE, PickerMediaColumns.MIME_TYPE);
                    break;
                case PickerMediaColumns.DATE_TAKEN:
                    projection[i] =
                            getProjectionSimple(KEY_DATE_TAKEN_MS, PickerMediaColumns.DATE_TAKEN);
                    break;
                case PickerMediaColumns.SIZE:
                    projection[i] = getProjectionSimple(KEY_SIZE_BYTES, PickerMediaColumns.SIZE);
                    break;
                case PickerMediaColumns.DURATION_MILLIS:
                    projection[i] =
                            getProjectionSimple(
                                    KEY_DURATION_MS, PickerMediaColumns.DURATION_MILLIS);
                    break;
                case PickerMediaColumns.HEIGHT:
                    projection[i] = getProjectionSimple(KEY_HEIGHT, PickerMediaColumns.HEIGHT);
                    break;
                case PickerMediaColumns.WIDTH:
                    projection[i] = getProjectionSimple(KEY_WIDTH, PickerMediaColumns.WIDTH);
                    break;
                case PickerMediaColumns.ORIENTATION:
                    projection[i] =
                            getProjectionSimple(KEY_ORIENTATION, PickerMediaColumns.ORIENTATION);
                    break;
                default:
                    projection[i] = getProjectionSimple("NULL", columns[i]);
                    // Ignore unsupported columns; we do not throw error here to support
                    // backward compatibility
                    Log.w(TAG, "Unexpected Picker column: " + columns[i]);
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
        // /sdcard/.transforms/synthetic/picker/<user-id>/<authority>/media/<display-name>
        // See PickerUriResolver#getMediaUri
        final String authority = String.format("CASE WHEN %s IS NULL THEN '%s' ELSE '%s' END",
                KEY_CLOUD_ID, mLocalProvider, mCloudProvider);
        final String fullPath = "'" + PICKER_PATH + "/'"
                + "||" + "'" + MediaStore.MY_USER_ID + "/'"
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
        final String fileExtension = String.format("_GET_EXTENSION(%s)", KEY_MIME_TYPE);

        return mediaId + "||" + fileExtension;
    }

    private static ContentValues cursorToContentValue(Cursor cursor, boolean isLocal) {
        return cursorToContentValue(cursor, isLocal, "");
    }

    private static ContentValues cursorToContentValue(Cursor cursor, boolean isLocal,
            String albumId) {
        final ContentValues values = new ContentValues();
        if (TextUtils.isEmpty(albumId)) {
            values.put(KEY_IS_VISIBLE, 1);
        }
        else {
            values.put(KEY_ALBUM_ID, albumId);
        }

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
                case CloudMediaProviderContract.MediaColumns.SYNC_GENERATION:
                    values.put(KEY_SYNC_GENERATION, cursor.getLong(index));
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
                    if (TextUtils.isEmpty(albumId)) {
                        values.put(KEY_IS_FAVORITE, cursor.getInt(index));
                    }
                    break;

                    /* The below columns are only included if this is not the album_media table
                     * (AlbumId is an empty string)
                     *
                     * The columns should be in the cursor either way, but we don't duplicate these
                     * columns to album_media since they are not needed for the UI.
                     */
                case CloudMediaProviderContract.MediaColumns.WIDTH:
                    if (TextUtils.isEmpty(albumId)) {
                        values.put(KEY_WIDTH, cursor.getInt(index));
                    }
                    break;
                case CloudMediaProviderContract.MediaColumns.HEIGHT:
                    if (TextUtils.isEmpty(albumId)) {
                        values.put(KEY_HEIGHT, cursor.getInt(index));
                    }
                    break;
                case CloudMediaProviderContract.MediaColumns.ORIENTATION:
                    if (TextUtils.isEmpty(albumId)) {
                        values.put(KEY_ORIENTATION, cursor.getInt(index));
                    }
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

        if (query.mIsLocalOnly) {
            qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
        }

        if (query.mId >= 0) {
            if (query.mDateTakenAfterMs >= 0) {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_AFTER);
                // Add date args twice because the sql statement evaluates date twice
                selectArgs.add(String.valueOf(query.mDateTakenAfterMs));
                selectArgs.add(String.valueOf(query.mDateTakenAfterMs));
            } else {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_BEFORE);
                // Add date args twice because the sql statement evaluates date twice
                selectArgs.add(String.valueOf(query.mDateTakenBeforeMs));
                selectArgs.add(String.valueOf(query.mDateTakenBeforeMs));
            }
            selectArgs.add(String.valueOf(query.mId));
        }

        if (query.mSizeBytes >= 0) {
            qb.appendWhereStandalone(WHERE_SIZE_BYTES);
            selectArgs.add(String.valueOf(query.mSizeBytes));
        }

        addMimeTypesToQueryBuilderAndSelectionArgs(qb, selectArgs, query.mMimeTypes);

        if (query.mIsVideo) {
            qb.appendWhereStandalone(WHERE_MIME_TYPE);
            selectArgs.add(VIDEO_MIME_TYPES);
        } else if (query.mIsFavorite) {
            qb.appendWhereStandalone(getWhereForFavorite(query.mIsLocalOnly));
        } else if (!TextUtils.isEmpty(query.mAlbumId)) {
            qb.appendWhereStandalone(WHERE_ALBUM_ID);
            selectArgs.add(query.mAlbumId);
        }

        if (selectArgs.isEmpty()) {
            return null;
        }

        return selectArgs.toArray(new String[selectArgs.size()]);
    }

    /**
     * Returns where clause to obtain rows that are marked as favorite
     *
     * Favorite information can either come from local or from cloud. In case where an item is
     * marked as favorite in cloud provider, we try to obtain the local row corresponding to this
     * cloud row to avoid downloading cloud file unnecessarily.
     * See {@code WHERE_FAVORITE_LOCAL_PLUS_CLOUD}
     *
     * For queries that are local only, we don't need any of these complex queries, hence we stick
     * to simple query like {@code WHERE_FAVORITE_LOCAL_ONLY}
     */
    private static String getWhereForFavorite(boolean isLocalOnly) {
        if (isLocalOnly) {
            return WHERE_FAVORITE_LOCAL_ONLY;
        } else {
            return WHERE_FAVORITE_ALL;
        }
    }

    static void addMimeTypesToQueryBuilderAndSelectionArgs(SQLiteQueryBuilder qb,
            List<String> selectionArgs, String[] mimeTypes) {
        if (mimeTypes == null) {
            return;
        }

        mimeTypes = replaceMatchAnyChar(mimeTypes);
        ArrayList<String> whereMimeTypes = new ArrayList<>();
        for (String mimeType : mimeTypes) {
            if (!TextUtils.isEmpty(mimeType)) {
                whereMimeTypes.add(WHERE_MIME_TYPE);
                selectionArgs.add(mimeType);
            }
        }

        if (whereMimeTypes.isEmpty()) {
            return;
        }
        qb.appendWhereStandalone(TextUtils.join(" OR ", whereMimeTypes));
    }

    private static SQLiteQueryBuilder createMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_MEDIA);

        return qb;
    }

    private static SQLiteQueryBuilder createAlbumMediaQueryBuilder(boolean isLocal) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_ALBUM_MEDIA);

        // In case of local albums, local_id cannot be null.
        // In case of cloud albums, there can be 2 types of media items:
        // 1. Cloud-only - Only cloud_id will be populated and local_id will be null.
        // 2. Local + Cloud - Only local_id will be populated and cloud_id will be null as showing
        // local copy is preferred over cloud copy.
        if (isLocal) {
            qb.appendWhereStandalone(WHERE_NOT_NULL_LOCAL_ID);
        }

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

    private abstract static class AlbumWriteOperation extends DbWriteOperation {

        private final String mAlbumId;

        private AlbumWriteOperation(SQLiteDatabase database, boolean isLocal, String albumId) {
            super(database, isLocal);
            mAlbumId = albumId;
        }

        String getAlbumId() {
            return mAlbumId;
        }
    }

    private static final class ResetAlbumOperation extends AlbumWriteOperation {

        private ResetAlbumOperation(SQLiteDatabase database, boolean isLocal, String albumId) {
            super(database, isLocal, albumId);
        }

        @Override
        int executeInternal(@Nullable Cursor unused) {
            final String albumId = getAlbumId();
            final boolean isLocal = isLocal();

            final SQLiteQueryBuilder qb = createAlbumMediaQueryBuilder(isLocal);

            String[] selectionArgs = null;
            if (!TextUtils.isEmpty(albumId)) {
                qb.appendWhereStandalone(WHERE_ALBUM_ID);
                selectionArgs = new String[]{albumId};
            }

            return qb.delete(getDatabase(), /* selection */ null, /* selectionArgs */
                    selectionArgs);
        }
    }

    private static final class AddAlbumMediaOperation extends AlbumWriteOperation {
        private static final String[] sLocalMediaProjection = new String[] {
                KEY_DATE_TAKEN_MS,
                KEY_SYNC_GENERATION,
                KEY_SIZE_BYTES,
                KEY_DURATION_MS,
                KEY_MIME_TYPE,
                KEY_STANDARD_MIME_TYPE_EXTENSION
        };

        private AddAlbumMediaOperation(SQLiteDatabase database, boolean isLocal, String albumId) {
            super(database, isLocal, albumId);

            if (TextUtils.isEmpty(albumId)) {
                throw new IllegalArgumentException("Missing albumId.");
            }
        }

        @Override
        int executeInternal(@Nullable Cursor cursor) {
            final boolean isLocal = isLocal();
            final String albumId = getAlbumId();
            final SQLiteQueryBuilder qb = createAlbumMediaQueryBuilder(isLocal);
            int counter = 0;

            while (cursor.moveToNext()) {
                ContentValues values = cursorToContentValue(cursor, isLocal, albumId);

                // In case of cloud albums, cloud provider returns both local and cloud ids.
                // We give preference to inserting media data for the local copy of an item instead
                // of the cloud copy. Hence, if local copy is available, fetch metadata from media
                // table and update the album_media row accordingly.
                if (!isLocal) {
                    final String localId = values.getAsString(KEY_LOCAL_ID);
                    final String cloudId = values.getAsString(KEY_CLOUD_ID);
                    if (!TextUtils.isEmpty(localId) && !TextUtils.isEmpty(cloudId)) {
                        // Fetch local media item details from media table.
                        try (Cursor cursorLocalMedia = getLocalMediaMetadata(localId)) {
                            if (cursorLocalMedia != null && cursorLocalMedia.getCount() == 1) {
                                // If local media item details are present in the media table,
                                // update content values and remove cloud id.
                                values.putNull(KEY_CLOUD_ID);
                                updateContentValues(values, cursorLocalMedia);
                            } else {
                                // If local media item details are NOT present in the media table,
                                // insert cloud row after removing local_id. This will only happen
                                // when local id points to a deleted item.
                                values.putNull(KEY_LOCAL_ID);
                            }
                        }
                    }
                }

                try {
                    if (qb.insert(getDatabase(), values) > 0) {
                        counter++;
                    } else {
                        Log.v(TAG, "Failed to insert album_media. ContentValues: " + values);
                    }
                } catch (SQLiteConstraintException e) {
                    Log.v(TAG, "Failed to insert album_media. ContentValues: " + values, e);
                }
            }

            return counter;
        }

        private void updateContentValues(ContentValues values, Cursor cursor) {
            if (cursor.moveToFirst()) {
                for (int columnIndex = 0; columnIndex < cursor.getColumnCount(); columnIndex++) {
                    String column = cursor.getColumnName(columnIndex);
                    switch (column) {
                        case KEY_DATE_TAKEN_MS:
                        case KEY_SYNC_GENERATION:
                        case KEY_SIZE_BYTES:
                        case KEY_DURATION_MS:
                        case KEY_STANDARD_MIME_TYPE_EXTENSION:
                            values.put(column, cursor.getLong(columnIndex));
                            break;
                        case KEY_MIME_TYPE:
                            values.put(column, cursor.getString(columnIndex));
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "Column " + column + " not recognized.");
                    }
                }
            }
        }

        private Cursor getLocalMediaMetadata(String localId) {
            final SQLiteQueryBuilder qb = createVisibleLocalMediaQueryBuilder();
            final String[] selectionArgs = new String[] {localId};
            qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);

            return qb.query(getDatabase(), sLocalMediaProjection, /* selection */ null,
                    selectionArgs, /* groupBy */ null, /* having */ null,
                    /* orderBy */ null);
        }
    }
}
