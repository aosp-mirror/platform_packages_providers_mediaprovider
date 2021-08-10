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

import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore.MediaColumns;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.photopicker.PickerSyncController;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the picker db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class PickerDbFacade {
    private final Object mLock = new Object();
    private final SQLiteDatabase mDatabase;
    private final String mLocalProvider;
    private String mCloudProvider;

    public PickerDbFacade(Context context) {
        final PickerDatabaseHelper databaseHelper = new PickerDatabaseHelper(context);
        mDatabase = databaseHelper.getWritableDatabase();
        mLocalProvider = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
    }

    @VisibleForTesting
    public PickerDbFacade(Context context, String localProvider) {
        final PickerDatabaseHelper databaseHelper = new PickerDatabaseHelper(context);
        mDatabase = databaseHelper.getWritableDatabase();
        mLocalProvider = localProvider;
    }

    private static final String TAG = "PickerDbFacade";

    private static final int RETRY = 0;
    private static final int SUCCESS = 1;
    private static final int FAIL = -1;

    public static final String KEY_LOCAL_PROVIDER = "local_provider";
    public static final String KEY_CLOUD_PROVIDER = "cloud_provider";

    private static final String TABLE_MEDIA = "media";

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
    public static final String KEY_SIZE_BYTES = "size_bytes";
    @VisibleForTesting
    public static final String KEY_DURATION_MS = "duration_ms";
    @VisibleForTesting
    public static final String KEY_MIME_TYPE = "mime_type";

    private static final String WHERE_ID = KEY_ID + " = ?";
    private static final String WHERE_LOCAL_ID = KEY_LOCAL_ID + " = ?";
    private static final String WHERE_CLOUD_ID = KEY_CLOUD_ID + " = ?";
    private static final String WHERE_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NULL";
    private static final String WHERE_NOT_NULL_CLOUD_ID = KEY_CLOUD_ID + " IS NOT NULL";
    private static final String WHERE_IS_VISIBLE = KEY_IS_VISIBLE + " = 1";
    private static final String WHERE_MIME_TYPE = KEY_MIME_TYPE + " LIKE ? ";
    private static final String WHERE_SIZE_BYTES = KEY_SIZE_BYTES + " <= ?";
    private static final String WHERE_DATE_TAKEN_MS_AFTER =
            "date_taken_ms > ? OR (date_taken_ms = ? AND _id > ?)";
    private static final String WHERE_DATE_TAKEN_MS_BEFORE =
            "date_taken_ms < ? OR (date_taken_ms = ? AND _id < ?)";

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

    {
        CONTENT_VALUE_VISIBLE.put(KEY_IS_VISIBLE, 1);
        CONTENT_VALUE_HIDDEN.putNull(KEY_IS_VISIBLE);
    }

    /*
     * Add media belonging to {@code authority} into the picker db.
     *
     * @param cursor containing items to add
     * @param authority to add media from
     * @return the number of {@code cursor} items that were inserted/updated in the picker db
     */
    public int addMedia(Cursor cursor, String authority) {
        final boolean isLocal = isLocal(authority);
        final SQLiteQueryBuilder qb = isLocal ? QB_MATCH_LOCAL_ONLY : QB_MATCH_CLOUD;
        int counter = 0;

        mDatabase.beginTransaction();
        try {
            while (cursor.moveToNext()) {
                ContentValues values = cursorToContentValue(cursor, isLocal);

                String[] upsertArgs = {values.getAsString(isLocal ? KEY_LOCAL_ID : KEY_CLOUD_ID)};
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
                    continue;
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        return counter;
    }

    /*
     * Remove media belonging to {@code authority} from the picker db.
     *
     * @param cursor containing items to remove
     * @param idIndex column index in {@code cursor} of the local id
     * @param authority to remove media from
     * @return the number of {@code cursor} items that were deleted/updated in the picker db
     */
    public int removeMedia(Cursor cursor, int idIndex, String authority) {
        final boolean isLocal = isLocal(authority);
        final SQLiteQueryBuilder qb = isLocal ? QB_MATCH_LOCAL_ONLY : QB_MATCH_CLOUD;

        int counter = 0;

        mDatabase.beginTransaction();
        try {
            while (cursor.moveToNext()) {
                // Need to fetch the local_id before delete because for cloud items
                // we need a db query to fetch the local_id matching the id received from
                // cursor (cloud_id).
                final String localId = getLocalIdFromCursorOrDb(cursor, isLocal);

                // Delete cloud/local row
                final String deleteArgs[] = {cursor.getString(idIndex)};
                if (qb.delete(mDatabase, /* selection */ null, deleteArgs) > 0) {
                    counter++;
                }

                promoteCloudMediaToVisible(localId);
            }

            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        return counter;
    }

    /**
     * Clear local media or all cloud media from the picker db. If {@code authority} is
     * null, we also clear all cloud media.
     *
     * @param authority to determine whether local or cloud media should be cleared
     * @return the number of items deleted
     */
    public int resetMedia(String authority) {
        final boolean isLocal = isLocal(authority);
        final SQLiteQueryBuilder qb = createMediaQueryBuilder();

        if (isLocal) {
            qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
        } else {
            qb.appendWhereStandalone(WHERE_NOT_NULL_CLOUD_ID);
        }

        int counter = 0;

        mDatabase.beginTransaction();
        try {
            counter = qb.delete(mDatabase, /* selection */ null, /* selectionArgs */ null);

            if (isLocal) {
                // If we reset local media, we need to promote cloud media items
                // Ignore conflicts in case we have multiple cloud_ids mapped to the
                // same local_id. Promoting either is fine.
                mDatabase.updateWithOnConflict(TABLE_MEDIA, CONTENT_VALUE_VISIBLE, /* where */ null,
                        /* whereClause */ null, SQLiteDatabase.CONFLICT_IGNORE);
            }

            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        return counter;
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

    private boolean isLocal(String authority) {
        return mLocalProvider.equals(authority);
    }

    private int insertMedia(ContentValues values) {
        try {
            if (QB_MATCH_ALL.insert(mDatabase, values) > 0) {
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

    private int updateMedia(SQLiteQueryBuilder qb, ContentValues values, String[] selectionArgs) {
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

    private int upsertMedia(SQLiteQueryBuilder qb, ContentValues values, String[] selectionArgs) {
        int res = insertMedia(values);
        if (res == RETRY) {
            // Attempt equivalent of CONFLICT_REPLACE resolution
            Log.d(TAG, "Retrying failed insert as update. ContentValues: " + values);
            res = updateMedia(qb, values, selectionArgs);
        }

        return res;
    }

    private String querySingleMedia(SQLiteQueryBuilder qb, String[] projection,
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

    private void demoteCloudMediaToHidden(@Nullable String cloudId) {
        if (cloudId == null) {
            return;
        }

        final String[] updateArgs = new String[] {cloudId};
        if (updateMedia(QB_MATCH_CLOUD, CONTENT_VALUE_HIDDEN, updateArgs) == SUCCESS) {
            Log.d(TAG, "Demoted picker db media item to hidden. CloudId: " + cloudId);
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

    private String getVisibleCloudIdFromDb(String localId) {
        final String[] cloudIdProjection = new String[] {KEY_CLOUD_ID};
        final String[] queryArgs = new String[] {localId};
        return querySingleMedia(QB_MATCH_VISIBLE_LOCAL, cloudIdProjection, queryArgs,
                /* columnIndex */ 0);
    }

    /*
     * Returns sorted and deduped cloud and local media items from the picker db.
     *
     * Returns a {@link Cursor} containing the most recent picker db media rows sorted in reverse
     * chronological order, i.e. newest first, up to a maximum of {@code limit}.
     *
     * The results can be further filtered with the {@code mimeTypeFilter} and {@code sizeBytesMax}
     * filters.
     */
    public Cursor queryMediaAll(int limit, String mimeTypeFilter, long sizeBytesMax) {
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        final String[] selectionArgs = buildSelectionArgs(qb, /* isQueryAfter */ false,
                /* dateTakenMs */ 0, /* id */ 0, mimeTypeFilter, sizeBytesMax);

        return queryMedia(qb, selectionArgs, limit);
    }

    /*
     * Returns sorted and deduped cloud and local media items from the picker db.
     *
     * Returns a {@link Cursor} containing picker db media rows sorted in reverse chronological
     * order, i.e. newest first, up to a maximum of {@code limit}.
     *
     * All returned items are either strictly more recent than {@code dateTakenMs} or if
     * they were captured at the same time, have a picker db id strictly greater than {@code id}.
     *
     * The results can be further filtered with the {@code mimeTypeFilter} and {@code sizeBytesMax}
     * filters.
     */
    public Cursor queryMediaAfter(long dateTakenMs, long id, int limit, String mimeTypeFilter,
            long sizeBytesMax) {
        final SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        final String[] selectArgs = buildSelectionArgs(qb, /* isQueryAfter */ true, dateTakenMs, id,
                mimeTypeFilter, sizeBytesMax);

        return queryMedia(qb, selectArgs, limit);
    }

    /*
     * Returns sorted and deduped cloud and local media items from the picker db.
     *
     * Returns a {@link Cursor} containing picker db media rows sorted in reverse chronological
     * order, i.e. newest first, up to a maximum of {@code limit}.
     *
     * All returned items are either strictly older than {@code dateTakenMs} or if
     * they were captured at the same time, have a picker db id strictly less than {@code id}.
     *
     * The results can be further filtered with the {@code mimeTypeFilter} and {@code sizeBytesMax}
     * filters.
     */
    public Cursor queryMediaBefore(long dateTakenMs, long id, int limit, String mimeTypeFilter,
            long sizeBytesMax) {
        SQLiteQueryBuilder qb = createVisibleMediaQueryBuilder();
        String[] selectArgs = buildSelectionArgs(qb, /* isQueryAfter */ false, dateTakenMs, id,
                mimeTypeFilter, sizeBytesMax);

        return queryMedia(qb, selectArgs, limit);
    }

    private Cursor queryMedia(SQLiteQueryBuilder qb, String[] selectionArgs, int limit) {
        final String[] projection = new String[] {
            KEY_LOCAL_ID,
            KEY_CLOUD_ID,
            KEY_DATE_TAKEN_MS,
            KEY_SIZE_BYTES,
            KEY_DURATION_MS,
            KEY_MIME_TYPE
        };

        final String orderBy = "date_taken_ms DESC,_id DESC";
        final String limitStr = String.valueOf(limit);

        final Cursor cursor;
        final Bundle bundle = new Bundle();
        bundle.putString(KEY_LOCAL_PROVIDER, mLocalProvider);

        // Hold lock while checking the cloud provider and querying so that cursor extras containing
        // the cloud provider is consistent with the cursor results and doesn't race with
        // #setCloudProvider
        synchronized (mLock) {
            if (mCloudProvider == null) {
                // If cloud provider is null, avoid all cloud items in the picker db
                qb.appendWhereStandalone(WHERE_NULL_CLOUD_ID);
            } else {
                // If cloud provider is not null, return the current cloud provider as part of the
                // cursor result
                bundle.putString(KEY_CLOUD_PROVIDER, mCloudProvider);
            }

            cursor = qb.query(mDatabase, projection, /* selection */ null, selectionArgs,
                    /* groupBy */ null, /* having */ null, orderBy, limitStr);
        }

        cursor.setExtras(bundle);
        return cursor;
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
                case CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MS:
                    values.put(KEY_DATE_TAKEN_MS, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.SIZE_BYTES:
                    values.put(KEY_SIZE_BYTES, cursor.getLong(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.MIME_TYPE:
                    values.put(KEY_MIME_TYPE, cursor.getString(index));
                    break;
                case CloudMediaProviderContract.MediaColumns.DURATION_MS:
                    values.put(KEY_DURATION_MS, cursor.getLong(index));
                    break;
            }
        }

        return values;
    }

    private static String[] buildSelectionArgs(SQLiteQueryBuilder qb, boolean isQueryAfter,
            long dateTakenMs, long id, String mimeTypeFilter, long sizeBytesMax) {
        List<String> selectArgs = new ArrayList<>();

        if (id > 0) {
            if (isQueryAfter) {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_AFTER);
            } else {
                qb.appendWhereStandalone(WHERE_DATE_TAKEN_MS_BEFORE);
            }
            selectArgs.add(String.valueOf(dateTakenMs));
            selectArgs.add(String.valueOf(dateTakenMs));
            selectArgs.add(String.valueOf(id));
        }

        if (sizeBytesMax > 0) {
            qb.appendWhereStandalone(WHERE_SIZE_BYTES);
            selectArgs.add(String.valueOf(sizeBytesMax));
        }

        if (mimeTypeFilter != null) {
            qb.appendWhereStandalone(WHERE_MIME_TYPE);
            selectArgs.add(mimeTypeFilter.replace('*', '%'));
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
