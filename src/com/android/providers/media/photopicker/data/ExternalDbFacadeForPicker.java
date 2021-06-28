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
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.annotation.VisibleForTesting;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.util.MimeUtils;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the external db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class ExternalDbFacadeForPicker {
    private static final String TAG = "ExternalDbFacade";
    @VisibleForTesting
    static final String TABLE_FILES = "files";

    private static final String TABLE_DELETED_MEDIA = "deleted_media";
    private static final String COLUMN_OLD_ID = "old_id";
    // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
    private static final String COLUMN_OLD_ID_AS_ID = COLUMN_OLD_ID + " AS " + "id";
    private static final String COLUMN_GENERATION_MODIFIED = MediaColumns.GENERATION_MODIFIED;

    // TODO(b/190713331): Use constants from CloudMediaProviderContract#MediaColumns
    private static final String[] PROJECTION_MEDIA_COLUMNS = new String[] {
        MediaColumns._ID + " AS " + "id",
        "COALESCE(" + MediaColumns.DATE_TAKEN + "," + MediaColumns.DATE_MODIFIED +
                    "* 1000) AS " + "date_taken_ms",
        MediaColumns.SIZE + " AS " + "size_bytes",
        MediaColumns.MIME_TYPE + " AS " + "mime_type",
        MediaColumns.DURATION + " AS " + "duration_ms"
    };
    private static final String[] PROJECTION_MEDIA_INFO = new String[] {
        "COUNT(" + MediaColumns.GENERATION_MODIFIED + ")" + " AS " + "media_count",
        "MAX(" + MediaColumns.GENERATION_MODIFIED + ")" + " AS " + "media_generation"
    };

    private static final String WHERE_MEDIA_TYPE = FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_IMAGE + " OR " + FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_VIDEO;
    private static final String WHERE_NOT_TRASHED = MediaColumns.IS_TRASHED + " = 0";
    private static final String WHERE_NOT_PENDING = MediaColumns.IS_PENDING + " = 0";
    private static final String WHERE_ID = MediaColumns._ID + " = ?";
    private static final String WHERE_GREATER_GENERATION =
            MediaColumns.GENERATION_MODIFIED + " > ?";

    private final DatabaseHelper mDatabaseHelper;

    public ExternalDbFacadeForPicker(DatabaseHelper databaseHelper) {
        mDatabaseHelper = databaseHelper;
    }

    /**
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileInserted(int mediaType, boolean isPending) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }

        return !isPending && MimeUtils.isImageOrVideoMediaType(mediaType);
    }

    /**
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileUpdated(long oldId, int oldMediaType, int newMediaType,
            boolean oldIsTrashed, boolean newIsTrashed, boolean oldIsPending,
            boolean newIsPending) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }

        final boolean oldIsMedia= MimeUtils.isImageOrVideoMediaType(oldMediaType);
        final boolean newIsMedia = MimeUtils.isImageOrVideoMediaType(newMediaType);

        final boolean oldIsVisible = !oldIsTrashed && !oldIsPending;
        final boolean newIsVisible = !newIsTrashed && !newIsPending;

        final boolean oldIsVisibleMedia = oldIsVisible && oldIsMedia;
        final boolean newIsVisibleMedia = newIsVisible && newIsMedia;

        if (!oldIsVisibleMedia && newIsVisibleMedia) {
            // Was not visible media and is now visible media
            return removeDeletedMedia(oldId);
        } else if (oldIsVisibleMedia && !newIsVisibleMedia) {
            // Was visible media and is now not visible media
            return addDeletedMedia(oldId);
        }

        // Do nothing, not an interesting change for deleted_media
        return false;
    }

    /**
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileDeleted(long id, int mediaType) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }
        if (!MimeUtils.isImageOrVideoMediaType(mediaType)) {
            return false;
        }

        return addDeletedMedia(id);
    }

    /**
     * Adds media with row id {@code oldId} to the deleted_media table. Returns {@code true} if
     * if it was successfully added, {@code false} otherwise.
     */
    @VisibleForTesting
    boolean addDeletedMedia(long oldId) {
        return mDatabaseHelper.runWithTransaction((db) -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();

            ContentValues cv = new ContentValues();
            cv.put(COLUMN_OLD_ID, oldId);
            cv.put(COLUMN_GENERATION_MODIFIED, DatabaseHelper.getGeneration(db));

            try {
                return qb.insert(db, cv) > 0;
            } catch (SQLiteConstraintException e) {
                String select = COLUMN_OLD_ID + " = ?";
                String[] selectArg = new String[] {String.valueOf(oldId)};

                return qb.update(db, cv, select, selectArg) > 0;
            }
         });
    }

    /**
     * Removes media with row id {@code oldId} from the deleted_media table. Returns {@code true} if
     * it was successfully removed, {@code false} otherwise.
     */
    @VisibleForTesting
    boolean removeDeletedMedia(long oldId) {
        return mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();

            return qb.delete(db, COLUMN_OLD_ID + " = ?", new String[] {String.valueOf(oldId)}) > 0;
         });
    }

    /**
     * Returns all items from the deleted_media table.
     */
    public Cursor queryDeletedMedia(long generation) {
        return mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();
            String[] projection = new String[] {COLUMN_OLD_ID_AS_ID};
            String select = COLUMN_GENERATION_MODIFIED + " > ?";
            String[] selectArg = new String[] {String.valueOf(generation)};

            return qb.query(db, projection, select, selectArg,  /* groupBy */ null,
                    /* having */ null, /* orderBy */ null);
         });
    }

    /**
     * Returns all items from the files table where {@link MediaColumns#GENERATION_MODIFIED}
     * is greater than {@code generation}.
     */
    public Cursor queryMediaGeneration(long generation) {
        final String[] selectArg = new String[] {String.valueOf(generation)};
        final String orderBy = MediaColumns.DATE_TAKEN + " DESC";

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qb = createFilesQueryBuilder();
                qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
                qb.appendWhereStandalone(WHERE_NOT_TRASHED);
                qb.appendWhereStandalone(WHERE_NOT_PENDING);
                qb.appendWhereStandalone(WHERE_GREATER_GENERATION);

                return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null, selectArg,
                        /* groupBy */ null, /* having */ null, orderBy);
            });
    }

    /** Returns the media item from the files table with row id {@code id}. */
    public Cursor queryMediaId(long id) {
        final String[] selectArg = new String[] {String.valueOf(id)};

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qb = createFilesQueryBuilder();
                qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
                qb.appendWhereStandalone(WHERE_NOT_TRASHED);
                qb.appendWhereStandalone(WHERE_NOT_PENDING);
                qb.appendWhereStandalone(WHERE_ID);

                return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null, selectArg,
                        /* groupBy */ null, /* having */ null, /* orderBy */ null);
            });
    }

    /**
     * Returns the total count and max {@link MediaColumns#GENERATION_MODIFIED} value
     * of the media items in the files table greater than {@code generation}.
     */
    public Cursor getMediaInfo(long generation) {
        final String[] selectArg = new String[] {String.valueOf(generation)};

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qb = createFilesQueryBuilder();
                qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
                qb.appendWhereStandalone(WHERE_NOT_TRASHED);
                qb.appendWhereStandalone(WHERE_NOT_PENDING);
                qb.appendWhereStandalone(WHERE_GREATER_GENERATION);

                return qb.query(db, PROJECTION_MEDIA_INFO, /* select */ null, selectArg,
                        /* groupBy */ null, /* having */ null, /* orderBy */ null);
            });
    }

    private static SQLiteQueryBuilder createDeletedMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_DELETED_MEDIA);

        return qb;
    }

    private static SQLiteQueryBuilder createFilesQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_FILES);

        return qb;
    }
}
