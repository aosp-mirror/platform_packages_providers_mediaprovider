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
import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ItemTest {

    @Test
    public void testConstructor() {
        final String id = "1";
        final long dateTaken = 12345678l;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, duration);
        cursor.moveToFirst();

        final Item item = new Item(cursor, MediaStore.AUTHORITY, UserId.CURRENT_USER);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);

        assertThat(item.isMessage()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsImage() {
        final String id = "1";
        final long dateTaken = 12345678l;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isImage()).isTrue();
        assertThat(item.isMessage()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsVideo() {
        final String id = "1";
        final long dateTaken = 12345678l;
        final String mimeType = "video/mpeg";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isVideo()).isTrue();
        assertThat(item.isMessage()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    @Test
    public void testIsGif() {
        final String id = "1";
        final long dateTaken = 12345678l;
        final String mimeType = "image/gif";
        final long duration = 1000;
        final Item item = generateItem(id, mimeType, dateTaken, duration);

        assertThat(item.isGif()).isTrue();
        assertThat(item.isMessage()).isFalse();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isVideo()).isFalse();
    }

    @Test
    public void testCreateDateItem() {
        final long dateTaken = 12345678l;

        final Item item = Item.createDateItem(dateTaken);

        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.isDate()).isTrue();
    }

    @Test
    public void testCreateMessageItem() {
        final Item item = Item.createMessageItem();

        assertThat(item.isMessage()).isTrue();
        assertThat(item.isDate()).isFalse();
        assertThat(item.isImage()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGif()).isFalse();
    }

    private static Cursor generateCursorForItem(String id, String mimeType, long dateTaken,
            long duration) {
        final MatrixCursor cursor = new MatrixCursor(
                ItemColumns.ALL_COLUMNS_LIST.toArray(new String[0]));
        cursor.addRow(new Object[] {id, mimeType, dateTaken, /* dateModified */ dateTaken,
                duration});
        return cursor;
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
