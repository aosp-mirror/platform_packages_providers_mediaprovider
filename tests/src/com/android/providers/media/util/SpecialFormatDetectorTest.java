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

package com.android.providers.media.util;

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class SpecialFormatDetectorTest {
    private static final String TAG = "SpecialFormatDetectorTest";
    private ContentResolver mIsolatedResolver;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(android.Manifest.permission.LOG_COMPAT_CHANGE,
                        android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        android.Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext =
                new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = isolatedContext.getContentResolver();
    }

    @Test
    public void testDetect_gif() throws Exception {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final File file = stage(R.raw.test_gif, new File(dir, TAG + System.nanoTime() + ".jpg"));

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertThat(uri).isNotNull();

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                ContentUris.parseId(uri));

        try (Cursor cr = mIsolatedResolver.query(filesUri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns._SPECIAL_FORMAT}, null, null, null)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo("image/jpeg");
            assertThat(cr.getInt(1)).isEqualTo(
                    MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF);
        }

        file.delete();
    }

    @Test
    public void testDetect_motionPhoto() throws Exception {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final File file = stage(R.raw.test_motion_photo, new File(dir, TAG + System.nanoTime() +
                ".jpg"));

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertThat(uri).isNotNull();

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                ContentUris.parseId(uri));

        try (Cursor cr = mIsolatedResolver.query(filesUri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns._SPECIAL_FORMAT}, null, null, null)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo("image/jpeg");
            assertThat(cr.getInt(1)).isEqualTo(
                    MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO);
        }

        file.delete();
    }

    @Test
    public void testDetect_animatedWebp() throws Exception {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final File file = stage(R.raw.test_animated_webp, new File(dir, TAG + System.nanoTime() +
                ".webp"));

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertThat(uri).isNotNull();

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                ContentUris.parseId(uri));

        try (Cursor cr = mIsolatedResolver.query(filesUri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns._SPECIAL_FORMAT}, null, null, null)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo("image/webp");
            assertThat(cr.getInt(1)).isEqualTo(
                    MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP);
        }

        file.delete();
    }

    @Test
    public void testDetect_nonAnimatedWebp() throws Exception {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final File file = stage(R.raw.test_non_animated_webp, new File(dir, TAG + System.nanoTime()
                + ".webp"));

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertThat(uri).isNotNull();

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                ContentUris.parseId(uri));

        try (Cursor cr = mIsolatedResolver.query(filesUri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns._SPECIAL_FORMAT}, null, null, null)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo("image/webp");
            assertThat(cr.getInt(1)).isEqualTo(MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE);
        }

        file.delete();
    }

    @Test
    public void testDetect_notSpecialFormat() throws Exception {
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        final File file = stage(R.raw.test_image, new File(dir, TAG + System.nanoTime() + ".jpg"));

        final Uri uri = MediaStore.scanFile(mIsolatedResolver, file);
        assertThat(uri).isNotNull();

        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                ContentUris.parseId(uri));

        try (Cursor cr = mIsolatedResolver.query(filesUri,
                new String[]{MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns._SPECIAL_FORMAT}, null, null, null)) {
            assertThat(cr.getCount()).isEqualTo(1);
            cr.moveToFirst();
            assertThat(cr.getString(0)).isEqualTo("image/jpeg");
            assertThat(cr.getInt(1)).isEqualTo(MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE);
        }

        file.delete();
    }
}
