/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.media;

import static android.os.Environment.buildPath;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.R;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class IdleServiceTest {
    private static final String TAG = MediaProviderTest.TAG;

    @Test
    @Ignore("Enable as part of b/142561358")
    public void testPruneThumbnails() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern");
        final ContentResolver isolatedResolver = isolatedContext.getContentResolver();

        // Insert valid item into database
        final ContentValues values = new ContentValues();
        final File dir = Environment.getExternalStorageDirectory();
        final File file = MediaScannerTest.stage(R.raw.test_image,
                new File(dir, System.nanoTime() + ".jpg"));
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        final Uri uri = isolatedResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        final long id = ContentUris.parseId(uri);

        // Touch some thumbnail files
        final File a = buildPath(dir, Environment.DIRECTORY_PICTURES, ".thumbnails", "1234567.jpg");
        final File b = buildPath(dir, Environment.DIRECTORY_MOVIES, ".thumbnails", "7654321.jpg");
        final File c = buildPath(dir, Environment.DIRECTORY_PICTURES, ".thumbnails", id + ".jpg");
        final File d = buildPath(dir, Environment.DIRECTORY_PICTURES, ".thumbnails", "random.bin");

        createNewFileWithMkdirs(a);
        createNewFileWithMkdirs(b);
        createNewFileWithMkdirs(c);
        createNewFileWithMkdirs(d);

        // Idle maintenance pass should clean up unknown files
        try (ContentProviderClient cpc = isolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider())
                    .onIdleMaintenance(new CancellationSignal());
        }

        assertFalse(a.exists());
        assertFalse(b.exists());
        assertTrue(c.exists());
        assertFalse(d.exists());
    }

    private static void createNewFileWithMkdirs(File file) throws IOException {
        file.getParentFile().mkdirs();
        file.createNewFile();
    }
}
