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

package com.android.providers.media.photopicker.data.model;

import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import static com.android.providers.media.photopicker.data.model.Item.ItemColumns;

import static com.google.common.truth.Truth.assertThat;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ItemTest {

    @Test
    public void testConstructor() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final int specialFormat = _SPECIAL_FORMAT_NONE;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, generationModified,
                duration, specialFormat);
        cursor.moveToFirst();

        final Item item = new Item(cursor, UserId.CURRENT_USER);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getGenerationModified()).isEqualTo(generationModified);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(Uri.parse("content://media/external/file/1"));

        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testConstructor_differentUser() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final int specialFormat = _SPECIAL_FORMAT_NONE;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, generationModified,
                duration, specialFormat);
        cursor.moveToFirst();
        final UserId userId = UserId.of(UserHandle.of(10));

        final Item item = new Item(cursor, userId);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getGenerationModified()).isEqualTo(generationModified);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(Uri.parse("content://10@media/external/file/1"));

        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testIsImage() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, generationModified, duration);

        assertThat(item.isImage()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testIsVideo() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "video/mpeg";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, generationModified, duration);

        assertThat(item.isVideo()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testIsMotionPhoto() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/jpeg";
        final long duration = 1000;
        final Item item = generateSpecialFormatItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_MOTION_PHOTO);

        assertThat(item.isMotionPhoto()).isTrue();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isVideo()).isFalse();
    }

    @Test
    public void testIsGif() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/jpeg";
        final long duration = 1000;
        final Item item = generateSpecialFormatItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_GIF);

        assertThat(item.isGif()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
    }

    @Test
    public void testIsGifDoesNotUseMimeType() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/gif";
        final long duration = 1000;
        final Item item = generateSpecialFormatItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_NONE);

        assertThat(item.isImage()).isTrue();
        assertThat(item.isGif()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testCreateDateItem() {
        final long dateTaken = 12345678L;

        final Item item = Item.createDateItem(dateTaken);

        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.isDate()).isTrue();
    }

    @Test
    public void testCompareTo_differentDateTaken() {
        final String id1 = "1";
        final long dateTaken1 = 1000000L;
        final long generationModified1 = 1L;
        final Item item1 = generateJpegItem(id1, dateTaken1, generationModified1);

        final String id2 = "2";
        final long dateTaken2 = 20000000L;
        final long generationModified2 = 2L;
        final Item item2 = generateJpegItem(id2, dateTaken2, generationModified2);

        assertThat(item1.compareTo(item2)).isEqualTo(-1);
        assertThat(item2.compareTo(item1)).isEqualTo(1);
    }

    @Test
    public void testCompareTo_sameDateTaken() {
        final long dateTaken = 12345678L;
        final long generationModified = 1L;

        final String id1 = "1";
        final Item item1 = generateJpegItem(id1, dateTaken, generationModified);

        final String id2 = "2";
        final Item item2 = generateJpegItem(id2, dateTaken, generationModified);

        assertThat(item1.compareTo(item2)).isEqualTo(-1);
        assertThat(item2.compareTo(item1)).isEqualTo(1);

        // Compare the same object
        assertThat(item2.compareTo(item2)).isEqualTo(0);

        // Compare two items with same dateTaken and same id. This will never happen in real world
        // use-case because ids are always unique.
        final Item item2SameValues = generateJpegItem(id2, dateTaken, generationModified);
        assertThat(item2SameValues.compareTo(item2)).isEqualTo(0);
    }

    private static Cursor generateCursorForItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration, int specialFormat) {
        final MatrixCursor cursor = new MatrixCursor(ItemColumns.ALL_COLUMNS);
        cursor.addRow(new Object[] {id, mimeType, dateTaken, /* dateModified */ dateTaken,
                generationModified, duration, specialFormat});
        return cursor;
    }

    private static Item generateJpegItem(String id, long dateTaken, long generationModified) {
        final String mimeType = "image/jpeg";
        final long duration = 1000;
        return generateItem(id, mimeType, dateTaken, generationModified, duration);
    }

    /**
     * Generate the {@link Item}
     * @param id the id
     * @param mimeType the mime type
     * @param dateTaken the time of date taken
     * @param generationModified the generation number associated with the media
     * @param duration the duration
     * @return the Item
     */
    public static Item generateItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration) {
        return new Item(id, mimeType, dateTaken, generationModified, duration,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id)),
                _SPECIAL_FORMAT_NONE);
    }

    /**
     * Generate the {@link Item}
     * @param id the id
     * @param mimeType the mime type
     * @param dateTaken the time of date taken
     * @param generationModified the generation number associated with the media
     * @param duration the duration
     * @param specialFormat the special format. See
     * {@link MediaStore.Files.FileColumns#_SPECIAL_FORMAT}
     * @return the Item
     */
    public static Item generateSpecialFormatItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration, int specialFormat) {
        return new Item(id, mimeType, dateTaken, generationModified, duration,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id)),
                specialFormat);
    }
}
