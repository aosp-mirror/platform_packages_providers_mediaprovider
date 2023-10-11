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

import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;

import static com.android.providers.media.photopicker.data.model.ModelTestUtils.generateItem;
import static com.android.providers.media.photopicker.data.model.ModelTestUtils.generateJpegItem;
import static com.android.providers.media.photopicker.data.model.ModelTestUtils.generateSpecialFormatItem;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.icu.util.VersionInfo;
import android.net.Uri;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.PickerSyncController;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;

@RunWith(AndroidJUnit4.class)
public class ItemTest {
    /**
     * ICU 72 started to use '\u202f' instead of ' ' before AM/PM.
     */
    private static final char AM_PM_SPACE_CHAR = VersionInfo.ICU_VERSION.getMajor() >= 72
            ? '\u202f' : ' ';

    @Test
    public void testConstructor() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_NONE);
        cursor.moveToFirst();

        final Item item = new Item(cursor, UserId.CURRENT_USER);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getGenerationModified()).isEqualTo(generationModified);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(
                Uri.parse("content://com.android.providers.media.photopicker/media/1"));

        assertThat(item.isImage()).isTrue();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGifOrAnimatedWebp()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
    }

    @Test
    public void testConstructor_differentUser() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/png";
        final long duration = 1000;
        final Cursor cursor = generateCursorForItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_NONE);
        cursor.moveToFirst();
        final UserId userId = UserId.of(UserHandle.of(10));

        final Item item = new Item(cursor, userId);

        assertThat(item.getId()).isEqualTo(id);
        assertThat(item.getDateTaken()).isEqualTo(dateTaken);
        assertThat(item.getGenerationModified()).isEqualTo(generationModified);
        assertThat(item.getMimeType()).isEqualTo(mimeType);
        assertThat(item.getDuration()).isEqualTo(duration);
        assertThat(item.getContentUri()).isEqualTo(
                Uri.parse("content://10@com.android.providers.media.photopicker/media/1"));

        assertThat(item.isImage()).isTrue();

        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGifOrAnimatedWebp()).isFalse();
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

        assertThat(item.isVideo()).isFalse();
        assertThat(item.isGifOrAnimatedWebp()).isFalse();
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

        assertThat(item.isImage()).isFalse();
        assertThat(item.isGifOrAnimatedWebp()).isFalse();
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

        assertThat(item.isGifOrAnimatedWebp()).isFalse();
        assertThat(item.isVideo()).isFalse();
    }

    @Test
    public void testIsGifOrAnimatedWebp() {
        final String id = "1";
        final long dateTaken = 12345678L;
        final long generationModified = 1L;
        final String mimeType = "image/jpeg";
        final long duration = 1000;
        final Item gifItem = generateSpecialFormatItem(id, mimeType, dateTaken, generationModified,
                duration, _SPECIAL_FORMAT_GIF);

        assertThat(gifItem.isGifOrAnimatedWebp()).isTrue();
        assertThat(gifItem.isGif()).isTrue();
        assertThat(gifItem.isImage()).isTrue();

        assertThat(gifItem.isAnimatedWebp()).isFalse();
        assertThat(gifItem.isVideo()).isFalse();

        final Item animatedWebpItem = generateSpecialFormatItem(id, mimeType, dateTaken,
                generationModified, duration, _SPECIAL_FORMAT_ANIMATED_WEBP);

        assertThat(animatedWebpItem.isGifOrAnimatedWebp()).isTrue();
        assertThat(animatedWebpItem.isAnimatedWebp()).isTrue();
        assertThat(animatedWebpItem.isImage()).isTrue();

        assertThat(animatedWebpItem.isGif()).isFalse();
        assertThat(animatedWebpItem.isVideo()).isFalse();
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

        assertThat(item.isGifOrAnimatedWebp()).isFalse();
        assertThat(item.isVideo()).isFalse();
        assertThat(item.isMotionPhoto()).isFalse();
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

    @Test
    public void testGetContentDescription() {
        final String id = "1";
        final long dateTaken = LocalDate.of(2020 /* year */, 7 /* month */, 7 /* dayOfMonth */)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        final long generationModified = 1L;
        final long duration = 1000;
        final Context context = InstrumentationRegistry.getTargetContext();

        Item item = generateItem(id, "image/jpeg", dateTaken, generationModified, duration);
        assertThat(item.getContentDescription(context))
                .isEqualTo("Photo taken on Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM");

        item = generateItem(id, "video/mp4", dateTaken, generationModified, duration);
        assertThat(item.getContentDescription(context)).isEqualTo(
                "Video taken on Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM with duration "
                        + item.getDurationText());

        item = generateSpecialFormatItem(id, "image/gif", dateTaken, generationModified, duration,
                _SPECIAL_FORMAT_GIF);
        assertThat(item.getContentDescription(context))
                .isEqualTo("GIF taken on Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM");

        item = generateSpecialFormatItem(id, "image/webp", dateTaken, generationModified, duration,
                _SPECIAL_FORMAT_ANIMATED_WEBP);
        assertThat(item.getContentDescription(context))
                .isEqualTo("GIF taken on Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM");

        item = generateSpecialFormatItem(id, "image/jpeg", dateTaken, generationModified, duration,
                _SPECIAL_FORMAT_MOTION_PHOTO);
        assertThat(item.getContentDescription(context))
                .isEqualTo("Motion Photo taken on Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM");
    }

    @Test
    public void testGetDurationText() {
        final String id = "1";
        final long dateTaken = LocalDate.of(2020 /* year */, 7 /* month */, 7 /* dayOfMonth */)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        final long generationModified = 1L;

        // no duration
        Item item = generateItem(id, "video", dateTaken, generationModified, -1);
        assertThat(item.getDurationText()).isEqualTo("");

        // duration = 1000 ms
        item = generateItem(id, "video", dateTaken, generationModified, 1000);
        assertThat(item.getDurationText()).isEqualTo("00:01");

        // duration = 10000 ms
        item = generateItem(id, "video", dateTaken, generationModified, 10000);
        assertThat(item.getDurationText()).isEqualTo("00:10");

        // duration = 60000 ms
        item = generateItem(id, "video", dateTaken, generationModified, 60000);
        assertThat(item.getDurationText()).isEqualTo("01:00");

        // duration = 600000 ms
        item = generateItem(id, "video", dateTaken, generationModified, 600000);
        assertThat(item.getDurationText()).isEqualTo("10:00");
    }

    private static Cursor generateCursorForItem(String id, String mimeType, long dateTaken,
            long generationModified, long duration, int specialFormat) {
        final MatrixCursor cursor = new MatrixCursor(MediaColumns.ALL_PROJECTION);
        cursor.addRow(new Object[] {
                id,
                dateTaken,
                generationModified,
                mimeType,
                specialFormat,
                "1", // size_bytes
                null, // media_store_uri
                duration,
                "0", // is_favorite
                800, // width
                500, // height
                0, // orientation
                "/storage/emulated/0/foo", // data
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                }
        );
        return cursor;
    }
}
