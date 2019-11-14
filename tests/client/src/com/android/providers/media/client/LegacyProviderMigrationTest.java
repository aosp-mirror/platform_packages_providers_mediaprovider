/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.provider.MediaStore.rewriteToLegacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Verify that we preserve information from the old "legacy" provider from
 * before we migrated into a Mainline module.
 * <p>
 * Specifically, values like {@link BaseColumns#_ID} and user edits like
 * {@link MediaColumns#IS_FAVORITE} should be retained.
 */
@RunWith(AndroidJUnit4.class)
public class LegacyProviderMigrationTest {
    private static final String TAG = "LegacyTest";

    // TODO(144247087): expand to verify pending items are migrated

    @Test
    public void testLegacy_Video() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();

        final ProviderInfo legacyProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY_LEGACY, 0);
        final ProviderInfo modernProvider = context.getPackageManager()
                .resolveContentProvider(MediaStore.AUTHORITY, 0);

        // Only continue if we have both providers to test against
        Assume.assumeNotNull(legacyProvider);
        Assume.assumeNotNull(modernProvider);

        final Uri collectionUri = MediaStore.Video.Media
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final long now = System.currentTimeMillis();

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, "test" + System.nanoTime() + ".mp4");
        values.put(MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaColumns.OWNER_PACKAGE_NAME,
                InstrumentationRegistry.getContext().getPackageName());
        values.put(VideoColumns.IS_FAVORITE, String.valueOf(1));
        values.put(VideoColumns.BOOKMARK, String.valueOf(42));
        values.put(VideoColumns.TAGS, "My Tags");
        values.put(VideoColumns.CATEGORY, "My Category");

        // Create a well-known entry in legacy provider, and write data into
        // place to ensure the file is created on disk
        final Uri legacyUri;
        final File legacyFile;
        try (ContentProviderClient legacy = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {
            legacyUri = rewriteToLegacy(legacy.insert(rewriteToLegacy(collectionUri), values));

            try (Cursor cursor = legacy.query(legacyUri, null, null, null)) {
                assertTrue(cursor.moveToFirst());
                copyFromCursorToContentValues(MediaColumns._ID, cursor, values);
                copyFromCursorToContentValues(MediaColumns.DATA, cursor, values);
                copyFromCursorToContentValues(MediaColumns.DATE_ADDED, cursor, values);
            }

            try (ParcelFileDescriptor pfd = legacy.openFile(legacyUri, "rw")) {
            }

            legacyFile = new File(values.getAsString(MediaColumns.DATA));
       }

        // Clear data on the modern provider so that the initial scan recovers
        // metadata from the legacy provider
        executeShellCommand("pm clear " + modernProvider.applicationInfo.packageName, ui);
        MediaStore.scanVolume(context, legacyFile);

        // Confirm that details from legacy provider have migrated
        try (ContentProviderClient modern = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            try (Cursor cursor = modern.query(collectionUri, null, MediaColumns.DATA + "=?",
                    new String[] { legacyFile.getAbsolutePath() }, null)) {
                assertTrue(cursor.moveToFirst());
                for (String key : values.keySet()) {
                    assertEquals(key, String.valueOf(values.get(key)),
                            cursor.getString(cursor.getColumnIndexOrThrow(key)));
                }
            }
        }
    }

    public static String executeShellCommand(String command, UiAutomation uiAutomation)
            throws IOException {
        Log.v(TAG, "$ " + command);
        ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(command.toString());
        BufferedReader br = null;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());) {
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String str = null;
            StringBuilder out = new StringBuilder();
            while ((str = br.readLine()) != null) {
                Log.v(TAG, "> " + str);
                out.append(str);
            }
            return out.toString();
        } finally {
            if (br != null) {
                br.close();
            }
        }
    }

    public static void copyFromCursorToContentValues(@NonNull String column, @NonNull Cursor cursor,
            @NonNull ContentValues values) {
        final int index = cursor.getColumnIndex(column);
        if (index != -1) {
            if (cursor.isNull(index)) {
                values.putNull(column);
            } else {
                values.put(column, cursor.getString(index));
            }
        }
    }
}
