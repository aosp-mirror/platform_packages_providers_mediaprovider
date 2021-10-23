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

import static com.android.providers.media.photopicker.data.model.Item.ItemColumns;

import static com.google.common.truth.Truth.assertThat;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ItemTest {

    @Test
    public void testConstructor() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, duration);
        cursor.moveToFirst();

        final Item item = new Item(cursor, UserId.CURRENT_USER);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(Uri.parse("content://media/external/file/1"));

        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testConstructor_differentUser() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, duration);
        cursor.moveToFirst();
        final UserId userId = UserId.of(UserHandle.of(10));

        final Item item = new Item(cursor, userId);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(Uri.parse("content://10@media/external/file/1"));

        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsImage() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isImage()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsVideo() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final String mimeType = "video/mpeg";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isVideo()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsGif() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final String mimeType = "image/gif";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isGif()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isVideo()).isFalse();
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
        final Item item1 = generateJpegItem(id1, dateTaken1);

        final String id2 = "2";
        final long dateTaken2 = 20000000L;
        final Item item2 = generateJpegItem(id2, dateTaken2);

        assertThat(item1.compareTo(item2)).isEqualTo(-1);
        assertThat(item2.compareTo(item1)).isEqualTo(1);
    }

    @Test
    public void testCompareTo_sameDateTaken() {
        final long dateTaken = 12345678L;

        final String id1 = "1";
        final Item item1 = generateJpegItem(id1, dateTaken);

        final String id2 = "2";
        final Item item2 = generateJpegItem(id2, dateTaken);

        assertThat(item1.compareTo(item2)).isEqualTo(-1);
        assertThat(item2.compareTo(item1)).isEqualTo(1);

        // Compare the same object
        assertThat(item2.compareTo(item2)).isEqualTo(0);

        // Compare two items with same dateTaken and same id. This will never happen in real world
        // use-case because ids are always unique.
        final Item item2SameValues = generateJpegItem(id2, dateTaken);
        assertThat(item2SameValues.compareTo(item2)).isEqualTo(0);
    }

    private static Cursor generateCursorForItem(String id, String mimeType, long dateTaken,
            long duration) {
        final MatrixCursor cursor = new MatrixCursor(ItemColumns.ALL_COLUMNS);
        cursor.addRow(new Object[] {id, mimeType, dateTaken, /* dateModified */ dateTaken,
                duration});
        return cursor;
    }

    private static Item generateJpegItem(String id, long dateTaken) {
        final String mimeType = "image/jpeg";
        final long duration = 1000;
        return generateItem(id, mimeType, dateTaken, duration);
    }

    /**
     * Generate the {@link Item}
     * @param id the id
     * @param mimeType the mime type
     * @param dateTaken the time of date taken
     * @param duration the duration
     * @return the Item
     */
    public static Item generateItem(String id, String mimeType, long dateTaken, long duration) {
        return new Item(id, mimeType, dateTaken, duration,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id)));
    }
}
