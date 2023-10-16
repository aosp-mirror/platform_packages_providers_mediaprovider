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

package com.android.providers.media.client;

import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.createNewPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.deletePublicVolumes;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.mountPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.unmountPublicVolume;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class PublicVolumeTest {
    @BeforeClass
    public static void setUp() throws Exception {
        createNewPublicVolume();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        deletePublicVolumes();
    }

    /**
     * Test that we can query database rows of recently unmounted volume
     */
    @Ignore("Re-enable once b/273569662 is fixed")
    @Test
    public void testIncludeRecentlyUnmountedVolumes() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        ContentResolver contentResolver = context.getContentResolver();
        MediaStore.waitForIdle(contentResolver);
        final String displayName = "UnmountedVolumeTest" + System.nanoTime();

        // Create image files in all volumes
        for (String volumeName : MediaStore.getExternalVolumeNames(context)) {
            ContentValues values = new ContentValues();
            values.clear();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName + ".jpeg");

            final Uri targetUri = contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(volumeName), values);
            assertNotNull(targetUri);

            try (OutputStream out = contentResolver.openOutputStream(targetUri)) {
            }
        }

        final int volumeCount = MediaStore.getExternalVolumeNames(context).size();
        Bundle extras = new Bundle();
        // Filter only image files added by this test
        extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?");
        extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] {displayName + "%"});
        // Verify that we can see both image files.
        try (Cursor c = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, extras, null)) {
            assertThat(c.getCount()).isEqualTo(volumeCount);
        }

        unmountPublicVolume();

        // Verify that we don't see image file of unmounted volume.
        try (Cursor c = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, extras, null)) {
            assertThat(c.getCount()).isEqualTo(volumeCount - 1);
        }

        // Verify that querying with QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES
        // includes database rows of unmounted volume.
        extras.putBoolean(MediaStore.QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES, true);
        try (Cursor c = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, extras, null)) {
            assertThat(c.getCount()).isEqualTo(volumeCount);
        }

        // Mount public volume to avoid side effects to other tests which reuse
        // the same public volume
        mountPublicVolume();
    }
}

