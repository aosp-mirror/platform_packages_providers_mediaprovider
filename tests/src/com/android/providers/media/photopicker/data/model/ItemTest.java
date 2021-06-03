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

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ItemTest {

    @Test
    public void testConstructor() {
        final long id = 1;
        final long dateTaken = 12345678l;
        final String mimeType = "image/png";
        final String displayName = "123.png";
        final String volumeName = "primary";
        final long duration = 1000;

        final Cursor cursor = generateCursorForItem(id, mimeType, displayName, volumeName,
                dateTaken, duration);
        cursor.moveToFirst();

        final Item item = new Item(cursor, UserId.CURRENT_USER);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getDisplayName()).isEqualTo(displayName);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getVolumeName()).isEqualTo(volumeName);
        assertThat(item.getDuration()).isEqualTo(duration);
    }

    private static Cursor generateCursorForItem(long id, String mimeType,
            String displayName, String volumeName, long dateTaken, long duration) {
        final MatrixCursor cursor = new MatrixCursor(
                ItemColumns.ALL_COLUMNS_LIST.toArray(new String[0]));
        cursor.addRow(new Object[] {id, mimeType, displayName, volumeName, dateTaken, duration});
        return cursor;
    }
}
