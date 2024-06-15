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

package com.android.providers.media.photopicker.util;

import static com.android.providers.media.util.MimeUtils.getExtensionFromMimeType;

import static com.google.common.truth.Truth.assertWithMessage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.data.PickerDbFacade;

public class PickerDbTestUtils {
    public static final long SIZE_BYTES = 7000;
    public static final long DATE_TAKEN_MS = 1623852851911L;
    public static final long GENERATION_MODIFIED = 1L;
    public static final long DURATION_MS = 5;
    public static final int HEIGHT = 720;
    public static final int WIDTH = 1080;
    public static final int ORIENTATION = 90;
    public static final String LOCAL_ID = "50";
    public static final String LOCAL_ID_1 = "501";
    public static final String LOCAL_ID_2 = "502";
    public static final String LOCAL_ID_3 = "503";
    public static final String LOCAL_ID_4 = "504";
    //TODO(b/329122491): Test with media ids that contain special characters.
    public static final String CLOUD_ID = "asdfghjkl";
    public static final String CLOUD_ID_1 = "asdfghjkl1";
    public static final String CLOUD_ID_2 = "asdfghjkl2";
    public static final String CLOUD_ID_3 = "asdfghjkl3";
    public static final String CLOUD_ID_4 = "asdfghjkl4";
    public static final String ALBUM_ID = "testAlbum";
    public static final String MP4_VIDEO_MIME_TYPE = "video/mp4";
    public static final String WEBM_VIDEO_MIME_TYPE = "video/webm";
    public static final String MPEG_VIDEO_MIME_TYPE = "video/mpeg";
    public static final String M4V_VIDEO_MIME_TYPE = "video/m4v";
    public static final String[] VIDEO_MIME_TYPES_QUERY = new String[]{"video/mp4"};
    public static final String JPEG_IMAGE_MIME_TYPE = "image/jpeg";
    public static final String GIF_IMAGE_MIME_TYPE = "image/gif";
    public static final String PNG_IMAGE_MIME_TYPE = "image/png";
    public static final String[] IMAGE_MIME_TYPES_QUERY = new String[]{"image/jpeg"};
    public static final int STANDARD_MIME_TYPE_EXTENSION =
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF;

    public static final String LOCAL_PROVIDER = "com.local.provider";
    public static final String CLOUD_PROVIDER = "com.cloud.provider";

    public static Cursor queryMediaAll(PickerDbFacade mFacade) {
        return mFacade.queryMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).build());
    }

    public static Cursor queryAlbumMedia(PickerDbFacade mFacade, String albumId, boolean isLocal) {
        final String authority = isLocal ? LOCAL_PROVIDER : CLOUD_PROVIDER;

        return mFacade.queryAlbumMediaForUi(
                new PickerDbFacade.QueryFilterBuilder(1000).setAlbumId(albumId).build(),
                authority);
    }

    public static void assertAddMediaOperation(PickerDbFacade mFacade, String authority,
            Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    public static void assertAddAlbumMediaOperation(PickerDbFacade mFacade, String authority,
            Cursor cursor, int writeCount, String albumId) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginAddAlbumMediaOperation(authority, albumId)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    public static void assertRemoveMediaOperation(PickerDbFacade mFacade, String authority,
            Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginRemoveMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    public static void assertResetMediaOperation(PickerDbFacade mFacade, String authority,
            Cursor cursor, int writeCount) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginResetMediaOperation(authority)) {
            assertWriteOperation(operation, cursor, writeCount);
            operation.setSuccess();
        }
    }

    public static void assertResetAlbumMediaOperation(PickerDbFacade mFacade, String authority,
            int writeCount, String albumId) {
        try (PickerDbFacade.DbWriteOperation operation =
                     mFacade.beginResetAlbumMediaOperation(authority, albumId)) {
            assertWriteOperation(operation, null, writeCount);
            operation.setSuccess();
        }
    }

    public static void assertWriteOperation(PickerDbFacade.DbWriteOperation operation,
            Cursor cursor, int expectedWriteCount) {
        final int writeCount = operation.execute(cursor);
        assertWithMessage("Unexpected write count on operation.execute(cursor).")
                .that(writeCount).isEqualTo(expectedWriteCount);
    }

    // TODO(b/190713331): s/id/CloudMediaProviderContract#MediaColumns#ID/
    public static Cursor getDeletedMediaCursor(String id) {
        MatrixCursor c =
                new MatrixCursor(new String[]{"id"});
        c.addRow(new String[]{id});
        return c;
    }

    public static Cursor getMediaCursor(String id, long dateTakenMs, long generationModified,
            String mediaStoreUri, long sizeBytes, String mimeType, int standardMimeTypeExtension,
            boolean isFavorite) {
        String[] projectionKey = new String[]{
                CloudMediaProviderContract.MediaColumns.ID,
                CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
                CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
                CloudMediaProviderContract.MediaColumns.MIME_TYPE,
                CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
                CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
                CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
                CloudMediaProviderContract.MediaColumns.HEIGHT,
                CloudMediaProviderContract.MediaColumns.WIDTH,
                CloudMediaProviderContract.MediaColumns.ORIENTATION,
        };

        String[] projectionValue = new String[]{
                id,
                mediaStoreUri,
                String.valueOf(dateTakenMs),
                String.valueOf(generationModified),
                String.valueOf(sizeBytes),
                mimeType,
                String.valueOf(standardMimeTypeExtension),
                String.valueOf(DURATION_MS),
                String.valueOf(isFavorite ? 1 : 0),
                String.valueOf(HEIGHT),
                String.valueOf(WIDTH),
                String.valueOf(ORIENTATION),
        };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    public static Cursor getAlbumCursor(String albumId, long dateTakenMs, String coverId,
            String authority) {
        String[] projectionKey = new String[]{
                CloudMediaProviderContract.AlbumColumns.ID,
                CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
                CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
                CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
                CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
                CloudMediaProviderContract.AlbumColumns.AUTHORITY,
        };

        String[] projectionValue = new String[]{
                albumId,
                albumId,
                String.valueOf(dateTakenMs),
                coverId,
                "0",
                authority,
        };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    public static Cursor getAlbumMediaCursor(
            String id,
            long dateTakenMs,
            long generationModified,
            String mediaStoreUri,
            long sizeBytes,
            String mimeType,
            int standardMimeTypeExtension) {
        String[] projectionKey =
                new String[]{
                        CloudMediaProviderContract.MediaColumns.ID,
                        CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
                        CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                        CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
                        CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
                        CloudMediaProviderContract.MediaColumns.MIME_TYPE,
                        CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
                        CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
                };

        String[] projectionValue =
                new String[]{
                        id,
                        mediaStoreUri,
                        String.valueOf(dateTakenMs),
                        String.valueOf(generationModified),
                        String.valueOf(sizeBytes),
                        mimeType,
                        String.valueOf(standardMimeTypeExtension),
                        String.valueOf(DURATION_MS),
                };

        MatrixCursor c = new MatrixCursor(projectionKey);
        c.addRow(projectionValue);
        return c;
    }

    public static Cursor getLocalMediaCursor(String localId, long dateTakenMs) {
        return getMediaCursor(localId, dateTakenMs, GENERATION_MODIFIED, toMediaStoreUri(localId),
                SIZE_BYTES, MP4_VIDEO_MIME_TYPE, STANDARD_MIME_TYPE_EXTENSION,
                /* isFavorite */ false);
    }

    public static Cursor getAlbumMediaCursor(String localId, String cloudId, long dateTakenMs) {
        return getAlbumMediaCursor(cloudId, dateTakenMs, GENERATION_MODIFIED,
                toMediaStoreUri(localId), SIZE_BYTES, MP4_VIDEO_MIME_TYPE,
                STANDARD_MIME_TYPE_EXTENSION);
    }

    public static Cursor getCloudMediaCursor(String cloudId, String localId,
            long dateTakenMs) {
        return getCloudMediaCursor(cloudId, localId, dateTakenMs, MP4_VIDEO_MIME_TYPE,
                /* isFavorite */ false);
    }

    public static Cursor getCloudMediaCursor(String cloudId, String localId,
            long dateTakenMs, String mimeType, boolean isFavorite) {
        return getMediaCursor(cloudId, dateTakenMs, GENERATION_MODIFIED, toMediaStoreUri(localId),
                SIZE_BYTES, mimeType, STANDARD_MIME_TYPE_EXTENSION, isFavorite);
    }

    public static String toMediaStoreUri(String localId) {
        if (localId == null) {
            return null;
        }
        return "content://media/external/file/" + localId;
    }

    public static String getDisplayName(String mediaId, String mimeType) {
        return mediaId + getExtensionFromMimeType(mimeType);
    }

    public static String getData(String authority, String displayName, String pickerSegmentType) {
        return "/sdcard/.transforms/synthetic/" + pickerSegmentType + "/0/" + authority + "/media/"
                + displayName;
    }

    public static void assertCloudAlbumCursor(Cursor cursor, String albumId, String displayName,
            String mediaCoverId, long dateTakenMs, long mediaCount) {
        assertWithMessage("Unexpected value of AlbumColumns.ID for cloud album cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.ID)))
                .isEqualTo(albumId);
        assertWithMessage("Unexpected value of AlbumColumns.DISPLAY_NAME for cloud album cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME)))
                .isEqualTo(displayName);
        assertWithMessage("Unexpected value of AlbumColumns.MEDIA_COVER_ID for cloud album cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID)))
                .isEqualTo(mediaCoverId);
        assertWithMessage(
                "Unexpected value of AlbumColumns.DATE_TAKEN_MILLIS for cloud album cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertWithMessage("Unexpected value of AlbumColumns.MEDIA_COUNT for cloud album cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT)))
                .isEqualTo(mediaCount);
    }

    public static void assertCloudMediaCursor(Cursor cursor, String id, String mimeType) {
        final String displayName = getDisplayName(id, mimeType);
        final String localData = getData(LOCAL_PROVIDER, displayName,
                PickerUriResolver.PICKER_SEGMENT);
        final String cloudData = getData(CLOUD_PROVIDER, displayName,
                PickerUriResolver.PICKER_SEGMENT);

        assertWithMessage("Unexpected value of MediaColumns.ID for the cloud media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.MediaColumns.ID)))
                .isEqualTo(id);
        assertWithMessage("Unexpected value of MediaColumns.AUTHORITY for the cloud media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.MediaColumns.AUTHORITY)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? LOCAL_PROVIDER : CLOUD_PROVIDER);
        assertWithMessage("Unexpected value of MediaColumns.DATA for the cloud media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.MediaColumns.DATA)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? localData : cloudData);
    }

    public static void assertCloudMediaCursor(Cursor cursor, String id, long dateTakenMs) {
        assertCloudMediaCursor(cursor, id, MP4_VIDEO_MIME_TYPE);

        assertWithMessage("Unexpected value of MediaColumns.MIME_TYPE for the cloud media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.MediaColumns.MIME_TYPE)))
                .isEqualTo(MP4_VIDEO_MIME_TYPE);
        assertWithMessage(
                "Unexpected value of MediaColumns.STANDARD_MIME_TYPE_EXTENSION for the cloud "
                        + "media cursor.")
                .that(cursor.getInt(
                        cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                                .MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        assertWithMessage(
                "Unexpected value of MediaColumns.DATE_TAKEN_MILLIS for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertWithMessage(
                "Unexpected value of MediaColumns.SYNC_GENERATION for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.SYNC_GENERATION)))
                .isEqualTo(GENERATION_MODIFIED);
        assertWithMessage("Unexpected value of MediaColumns.SIZE_BYTES for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.SIZE_BYTES)))
                .isEqualTo(SIZE_BYTES);
        assertWithMessage(
                "Unexpected value of MediaColumns.DURATION_MILLIS for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
    }

    public static void assertCloudMediaCursor(
            Cursor cursor, String id, long dateTakenMs, String mimeType) {
        assertCloudMediaCursor(cursor, id, mimeType);

        assertWithMessage("Unexpected value for MediaColumns.MIME_TYPE for the cloud media cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        CloudMediaProviderContract.MediaColumns.MIME_TYPE)))
                .isEqualTo(mimeType);
        assertWithMessage(
                "Unexpected value for MediaColumns.STANDARD_MIME_TYPE_EXTENSION for the cloud "
                        + "media cursor.")
                .that(cursor.getInt(
                        cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                                .MediaColumns.STANDARD_MIME_TYPE_EXTENSION)))
                .isEqualTo(STANDARD_MIME_TYPE_EXTENSION);
        assertWithMessage(
                "Unexpected value for MediaColumns.DATE_TAKEN_MILLIS for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.DATE_TAKEN_MILLIS)))
                .isEqualTo(dateTakenMs);
        assertWithMessage(
                "Unexpected value for MediaColumns.SYNC_GENERATION for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.SYNC_GENERATION)))
                .isEqualTo(GENERATION_MODIFIED);
        assertWithMessage(
                "Unexpected value for MediaColumns.SIZE_BYTES for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.SIZE_BYTES)))
                .isEqualTo(SIZE_BYTES);
        assertWithMessage(
                "Unexpected value for MediaColumns.DURATION_MILLIS for the cloud media cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(CloudMediaProviderContract
                        .MediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
    }

    public static void assertAllMediaCursor(
            Cursor cursor, String[] mediaIds, long[] dateTakenMs, String[] mimeTypes) {
        int mediaCount = cursor.getCount();
        for (int mediaNo = 0; mediaNo < mediaCount; mediaNo = mediaNo + 1) {
            if (mediaNo == 0) {
                cursor.moveToFirst();
            } else {
                cursor.moveToNext();
            }
            assertCloudMediaCursor(cursor, mediaIds[mediaNo], dateTakenMs[mediaNo],
                    mimeTypes[mediaNo]);
        }
    }

    public static void assertMediaStoreCursor(Cursor cursor, String id, long dateTakenMs,
            String pickerSegmentType) {
        final String displayName = getDisplayName(id, MP4_VIDEO_MIME_TYPE);
        final String localData = getData(LOCAL_PROVIDER, displayName, pickerSegmentType);
        final String cloudData = getData(CLOUD_PROVIDER, displayName, pickerSegmentType);

        assertWithMessage(
                "Unexpected value for PickerMediaColumns.DISPLAY_NAME for the media store cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.DISPLAY_NAME)))
                .isEqualTo(displayName);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.DATA for the media store cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.DATA)))
                .isEqualTo(id.startsWith(LOCAL_ID) ? localData : cloudData);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.MIME_TYPE for the media store cursor.")
                .that(cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.MIME_TYPE)))
                .isEqualTo(MP4_VIDEO_MIME_TYPE);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.DATE_TAKEN for the media store cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.DATE_TAKEN)))
                .isEqualTo(dateTakenMs);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.SIZE for the media store cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.SIZE)))
                .isEqualTo(SIZE_BYTES);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.DURATION_MILLIS for the media store "
                        + "cursor.")
                .that(cursor.getLong(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.DURATION_MILLIS)))
                .isEqualTo(DURATION_MS);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.HEIGHT for the media store cursor.")
                .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.HEIGHT)))
                .isEqualTo(HEIGHT);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.WIDTH for the media store cursor.")
                .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.WIDTH)))
                .isEqualTo(WIDTH);
        assertWithMessage(
                "Unexpected value for PickerMediaColumns.ORIENTATION for the media store cursor.")
                .that(cursor.getInt(cursor.getColumnIndexOrThrow(
                        MediaStore.PickerMediaColumns.ORIENTATION)))
                .isEqualTo(ORIENTATION);
    }
}
