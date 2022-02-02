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

import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.DatabaseUtils.replaceMatchAnyChar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Environment;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.util.MimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the external db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class ExternalDbFacade {
    private static final String TAG = "ExternalDbFacade";
    @VisibleForTesting
    static final String TABLE_FILES = "files";

    @VisibleForTesting
    static final String TABLE_DELETED_MEDIA = "deleted_media";
    @VisibleForTesting
    static final String COLUMN_OLD_ID = "old_id";
    private static final String COLUMN_OLD_ID_AS_ID = COLUMN_OLD_ID + " AS " +
            CloudMediaProviderContract.MediaColumns.ID;
    private static final String COLUMN_GENERATION_MODIFIED = MediaColumns.GENERATION_MODIFIED;

    private static final String[] PROJECTION_MEDIA_COLUMNS = new String[] {
        MediaColumns._ID + " AS " + CloudMediaProviderContract.MediaColumns.ID,
        "COALESCE(" + MediaColumns.DATE_TAKEN + "," + MediaColumns.DATE_MODIFIED +
                    "* 1000) AS " + CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
        MediaColumns.GENERATION_MODIFIED + " AS " +
                CloudMediaProviderContract.MediaColumns.GENERATION_MODIFIED,
        MediaColumns.SIZE + " AS " + CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
        MediaColumns.MIME_TYPE + " AS " + CloudMediaProviderContract.MediaColumns.MIME_TYPE,
        FileColumns._SPECIAL_FORMAT + " AS " +
                CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
        MediaColumns.DURATION + " AS " + CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
        MediaColumns.IS_FAVORITE + " AS " + CloudMediaProviderContract.MediaColumns.IS_FAVORITE
    };
    private static final String[] PROJECTION_MEDIA_INFO = new String[] {
        "COUNT(" + MediaColumns.GENERATION_MODIFIED + ") AS "
        + CloudMediaProviderContract.MediaInfo.MEDIA_COUNT,
        "MAX(" + MediaColumns.GENERATION_MODIFIED + ") AS "
        + CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION
    };
    private static final String[] PROJECTION_DELETED_MEDIA_INFO = new String[] {
        "MAX(" + MediaColumns.GENERATION_MODIFIED + ") AS "
        + CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION
    };
    private static final String[] PROJECTION_ALBUM_DB = new String[] {
        "COUNT(" + MediaColumns._ID + ") AS " + CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
        "MAX(COALESCE(" + MediaColumns.DATE_TAKEN + "," + MediaColumns.DATE_MODIFIED +
                    "* 1000)) AS " + CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
        MediaColumns._ID + " AS " + CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
    };

    private static final String[] PROJECTION_ALBUM_CURSOR = new String[] {
            CloudMediaProviderContract.AlbumColumns.ID,
            CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
            CloudMediaProviderContract.AlbumColumns.TYPE,
    };

    private static final String WHERE_IMAGE_TYPE = FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_IMAGE;
    private static final String WHERE_VIDEO_TYPE = FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_VIDEO;
    private static final String WHERE_MEDIA_TYPE = WHERE_IMAGE_TYPE + " OR " + WHERE_VIDEO_TYPE;
    private static final String WHERE_IS_FAVORITE = MediaColumns.IS_FAVORITE + " = 1";
    private static final String WHERE_IS_DOWNLOAD = MediaColumns.IS_DOWNLOAD + " = 1";
    private static final String WHERE_NOT_TRASHED = MediaColumns.IS_TRASHED + " = 0";
    private static final String WHERE_NOT_PENDING = MediaColumns.IS_PENDING + " = 0";
    private static final String WHERE_ID = MediaColumns._ID + " = ?";
    private static final String WHERE_GREATER_GENERATION =
            MediaColumns.GENERATION_MODIFIED + " > ?";
    private static final String WHERE_RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
            + " LIKE ?";
    private static final String WHERE_MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE
            + " LIKE ?";

    public static final String RELATIVE_PATH_SCREENSHOTS =
            "%/" + Environment.DIRECTORY_SCREENSHOTS + "/%";

    public static final String RELATIVE_PATH_CAMERA = Environment.DIRECTORY_DCIM + "/Camera/%";

    private final DatabaseHelper mDatabaseHelper;
    private final Context mContext;

    public ExternalDbFacade(Context context, DatabaseHelper databaseHelper) {
        mContext = context;
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
     * Adds or removes media to the deleted_media tables
     *
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileUpdated(long oldId, int oldMediaType, int newMediaType,
            boolean oldIsTrashed, boolean newIsTrashed, boolean oldIsPending,
            boolean newIsPending, boolean oldIsFavorite, boolean newIsFavorite,
            int oldSpecialFormat, int newSpecialFormat) {
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
            removeDeletedMedia(oldId);
            return true;
        } else if (oldIsVisibleMedia && !newIsVisibleMedia) {
            // Was visible media and is now not visible media
            addDeletedMedia(oldId);
            return true;
        }

        if (newIsVisibleMedia) {
            return (oldIsFavorite != newIsFavorite) || (oldSpecialFormat != newSpecialFormat);
        }


        // Do nothing, not an interesting change
        return false;
    }

    /**
     * Adds or removes media to the deleted_media tables
     *
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

        addDeletedMedia(id);
        return true;
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
                String[] selectionArgs = new String[] {String.valueOf(oldId)};

                return qb.update(db, cv, select, selectionArgs) > 0;
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
            String[] selectionArgs = new String[] {String.valueOf(generation)};

            return qb.query(db, projection, select, selectionArgs,  /* groupBy */ null,
                    /* having */ null, /* orderBy */ null);
         });
    }

    /**
     * Returns all items from the files table where {@link MediaColumns#GENERATION_MODIFIED}
     * is greater than {@code generation}.
     */
    public Cursor queryMediaGeneration(long generation, String albumId, String mimeType) {
        final List<String> selectionArgs = new ArrayList<>();
        final String orderBy = CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS + " DESC";

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qb = createMediaQueryBuilder();
                qb.appendWhereStandalone(WHERE_GREATER_GENERATION);
                selectionArgs.add(String.valueOf(generation));

                selectionArgs.addAll(appendWhere(qb, albumId, mimeType));

                return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null,
                        selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                        /* having */ null, orderBy);
            });
    }

    /** Returns the media item from the files table with row id {@code id}. */
    public Cursor queryMediaId(long id) {
        final String[] selectionArgs = new String[] {String.valueOf(id)};

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qb = createMediaQueryBuilder();
                qb.appendWhereStandalone(WHERE_ID);

                return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null, selectionArgs,
                        /* groupBy */ null, /* having */ null, /* orderBy */ null);
            });
    }

    /**
     * Returns the total count and max {@link MediaColumns#GENERATION_MODIFIED} value
     * of the media items in the files table greater than {@code generation}.
     */
    public Cursor getMediaInfo(long generation) {
        final String[] selectionArgs = new String[] {String.valueOf(generation)};
        final String[] projection = new String[] {
            CloudMediaProviderContract.MediaInfo.MEDIA_COUNT,
            CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION
        };

        return mDatabaseHelper.runWithTransaction(db -> {
                SQLiteQueryBuilder qbMedia = createMediaQueryBuilder();
                qbMedia.appendWhereStandalone(WHERE_GREATER_GENERATION);
                SQLiteQueryBuilder qbDeletedMedia = createDeletedMediaQueryBuilder();
                qbDeletedMedia.appendWhereStandalone(WHERE_GREATER_GENERATION);

                try (Cursor mediaCursor = query(qbMedia, db, PROJECTION_MEDIA_INFO, selectionArgs);
                        Cursor deletedMediaCursor = query(qbDeletedMedia, db,
                                PROJECTION_DELETED_MEDIA_INFO, selectionArgs)) {
                    final int mediaCountIndex = mediaCursor.getColumnIndexOrThrow(
                            CloudMediaProviderContract.MediaInfo.MEDIA_COUNT);
                    final int mediaGenerationIndex = mediaCursor.getColumnIndexOrThrow(
                            CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION);
                    final int deletedMediaGenerationIndex =
                            deletedMediaCursor.getColumnIndexOrThrow(
                                    CloudMediaProviderContract.MediaInfo.MEDIA_GENERATION);

                    long mediaCount = 0;
                    long mediaGeneration = 0;
                    if (mediaCursor.moveToFirst()) {
                        mediaCount = mediaCursor.getLong(mediaCountIndex);
                        mediaGeneration = mediaCursor.getLong(mediaGenerationIndex);
                    }

                    long deletedMediaGeneration = 0;
                    if (deletedMediaCursor.moveToFirst()) {
                        deletedMediaGeneration = deletedMediaCursor.getLong(
                                deletedMediaGenerationIndex);
                    }

                    long maxGeneration = Math.max(mediaGeneration, deletedMediaGeneration);
                    MatrixCursor result = new MatrixCursor(projection);
                    result.addRow(new Long[] { mediaCount, maxGeneration });

                    return result;
                }
            });
    }

    /**
     * Returns the media item categories from the files table.
     * Categories are determined with the {@link Category#CATEGORIES_LIST}.
     * If there are no media items under a category, the category is skipped from the results.
     */
    public Cursor queryAlbums(String mimeType) {
        final MatrixCursor c = new MatrixCursor(PROJECTION_ALBUM_CURSOR);

        for (String category: Category.CATEGORIES_LIST) {
            if (Category.CATEGORY_FAVORITES.equals(category)) {
                // TODO(b/196071169): Remove after removing favorites from CATEGORIES_LIST
                continue;
            }

            Cursor cursor = mDatabaseHelper.runWithTransaction(db -> {
                final SQLiteQueryBuilder qb = createMediaQueryBuilder();
                final List<String> selectionArgs = new ArrayList<>();
                selectionArgs.addAll(appendWhere(qb, category, mimeType));

                return qb.query(db, PROJECTION_ALBUM_DB, /* selection */ null,
                        selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                        /* having */ null, /* orderBy */ null);
            });

            if (cursor == null || !cursor.moveToFirst()) {
                continue;
            }

            long count = getCursorLong(cursor, CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);
            if (count == 0) {
                continue;
            }

            final String[] projectionValue = new String[] {
                category,
                getCursorString(cursor, CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS),
                Category.getCategoryName(mContext, category),
                String.valueOf(count),
                getCursorString(cursor, CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID),
                CloudMediaProviderContract.AlbumColumns.TYPE_LOCAL
            };

            c.addRow(projectionValue);
        }

        return c;
    }

        private static Cursor query(SQLiteQueryBuilder qb, SQLiteDatabase db, String[] projection,
                String[] selectionArgs) {
            return qb.query(db, PROJECTION_MEDIA_INFO, /* select */ null, selectionArgs,
                    /* groupBy */ null, /* having */ null, /* orderBy */ null);
        }

    private static List<String> appendWhere(SQLiteQueryBuilder qb, String albumId,
            String mimeType) {
        final List<String> selectionArgs = new ArrayList<>();

        if (mimeType != null) {
            qb.appendWhereStandalone(WHERE_MIME_TYPE);
            selectionArgs.add(replaceMatchAnyChar(mimeType));
        }

        if (albumId == null) {
            return selectionArgs;
        }

        switch (albumId) {
            case Category.CATEGORY_VIDEOS:
                qb.appendWhereStandalone(WHERE_VIDEO_TYPE);
                break;
            case Category.CATEGORY_CAMERA:
                qb.appendWhereStandalone(WHERE_RELATIVE_PATH);
                selectionArgs.add(RELATIVE_PATH_CAMERA);
                break;
            case Category.CATEGORY_SCREENSHOTS:
                qb.appendWhereStandalone(WHERE_RELATIVE_PATH);
                selectionArgs.add(RELATIVE_PATH_SCREENSHOTS);
                break;
            case Category.CATEGORY_DOWNLOADS:
                qb.appendWhereStandalone(WHERE_IS_DOWNLOAD);
                break;
            default:
                Log.w(TAG, "No match for album: " + albumId);
                break;
        }

        return selectionArgs;
    }

    private static SQLiteQueryBuilder createDeletedMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_DELETED_MEDIA);

        return qb;
    }

    private static SQLiteQueryBuilder createMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_FILES);
        qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
        qb.appendWhereStandalone(WHERE_NOT_TRASHED);
        qb.appendWhereStandalone(WHERE_NOT_PENDING);

        return qb;
    }
}
