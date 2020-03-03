/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;


import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class MediaDocumentsProviderTest {

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testSimple() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern");
        final ContentResolver resolver = isolatedContext.getContentResolver();

        assertProbe(resolver, "root");

        for (String root : new String[] {
                MediaDocumentsProvider.TYPE_AUDIO_ROOT,
                MediaDocumentsProvider.TYPE_VIDEOS_ROOT,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT,
                MediaDocumentsProvider.TYPE_DOCUMENTS_ROOT,
        }) {
            assertProbe(resolver, "root", root, "search");

            assertProbe(resolver, "document", root);
            assertProbe(resolver, "document", root, "children");
        }

        for (String recent : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_ROOT,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT,
                MediaDocumentsProvider.TYPE_DOCUMENTS_ROOT,
        }) {
            assertProbe(resolver, "root", recent, "recent");
        }

        for (String dir : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
                MediaDocumentsProvider.TYPE_DOCUMENTS_BUCKET,
        }) {
            assertProbe(resolver, "document", dir, "children");
        }

        for (String item : new String[] {
                MediaDocumentsProvider.TYPE_ARTIST,
                MediaDocumentsProvider.TYPE_ALBUM,
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
                MediaDocumentsProvider.TYPE_DOCUMENTS_BUCKET,

                MediaDocumentsProvider.TYPE_AUDIO,
                MediaDocumentsProvider.TYPE_VIDEO,
                MediaDocumentsProvider.TYPE_IMAGE,
                MediaDocumentsProvider.TYPE_DOCUMENT,
        }) {
                assertProbe(resolver, "document", item);
        }
    }

    @Test
    public void testBuildSearchSelection() {
        final String displayName = "foo";
        final String[] mimeTypes = new String[] {"text/csv", "video/*", "image/png", "audio/*"};
        final long lastModifiedAfter = 1000 * 1000;
        final long fileSizeOver = 1000 * 1000;
        final String columnDisplayName = "display";
        final String columnMimeType = "mimeType";
        final String columnLastModified = "lastModified";
        final String columnFileSize = "fileSize";
        final String resultSelection =
                "display LIKE ? AND lastModified > 1000 AND fileSize > 1000000 AND (mimeType LIKE"
                        + " ? OR mimeType LIKE ? OR mimeType IN (?,?))";

        final Pair<String, String[]> selectionPair = MediaDocumentsProvider.buildSearchSelection(
                displayName, mimeTypes, lastModifiedAfter, fileSizeOver, columnDisplayName,
                columnMimeType, columnLastModified, columnFileSize);

        assertEquals(resultSelection, selectionPair.first);
        assertEquals(5, selectionPair.second.length);
        assertEquals("%" + displayName + "%", selectionPair.second[0]);
        assertMimeType(mimeTypes[1], selectionPair.second[1]);
        assertMimeType(mimeTypes[3], selectionPair.second[2]);
        assertMimeType(mimeTypes[0], selectionPair.second[3]);
        assertMimeType(mimeTypes[2], selectionPair.second[4]);
    }

    @Test
    public void testAddDocumentSelection() {
        final String selection = "";
        final String[] selectionArgs = new String[]{};
        final String resultSelection = "media_type=?";

        final Pair<String, String[]> selectionPair = MediaDocumentsProvider.addDocumentSelection(
                selection, selectionArgs);

        assertEquals(resultSelection, selectionPair.first);
        assertEquals(1, selectionPair.second.length);
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT,
                Integer.parseInt(selectionPair.second[0]));
    }

    private static void assertProbe(ContentResolver resolver, String... paths) {
        final Uri.Builder probe = Uri.parse("content://" + MediaDocumentsProvider.AUTHORITY)
                .buildUpon();
        for (String path : paths) {
            probe.appendPath(path);
        }
        try (Cursor c = resolver.query(probe.build(), null, Bundle.EMPTY, null)) {
            assertNotNull(Arrays.toString(paths), c);
        }
    }

    private static void assertMimeType(String expected, String actual) {
        if (expected.endsWith("/*")) {
            assertEquals(expected.substring(0, expected.length() - 1) + "%", actual);
        } else {
            assertEquals(expected, actual);
        }
    }
}
