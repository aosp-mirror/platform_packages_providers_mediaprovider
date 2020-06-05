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

import static com.android.providers.media.MediaDocumentsProvider.AUTHORITY;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.MimeUtils;

import com.google.common.base.Objects;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
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
    public void testSimple() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

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

    /**
     * Recursively walk every item published by provider and confirm we can
     * query it, open it, and obtain a thumbnail for it.
     */
    @Test
    public void testTraverse() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        final Uri roots = DocumentsContract.buildRootsUri(AUTHORITY);
        try (Cursor c = resolver.query(roots, null, null, null)) {
            while (c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Root.COLUMN_DOCUMENT_ID));
                final Uri children = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
                doTraversal(resolver, children);
            }
        }
    }

    /**
     * Recursively walk all children documents at the given location.
     */
    public void doTraversal(ContentResolver resolver, Uri child) throws Exception {
        try (Cursor c = resolver.query(child, null, null, null)) {
            while (c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                final String mimeType = c.getString(c.getColumnIndex(Document.COLUMN_MIME_TYPE));

                final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
                final Uri grandchild = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);

                if (Objects.equal(Document.MIME_TYPE_DIR, mimeType)) {
                    doTraversal(resolver, grandchild);
                } else {
                    // Verify we can open
                    try (InputStream in = resolver.openInputStream(uri)) {
                    }

                    // Verify we can fetch metadata for common types
                    final int mediaType = MimeUtils.resolveMediaType(mimeType);
                    switch (mediaType) {
                        case FileColumns.MEDIA_TYPE_AUDIO:
                        case FileColumns.MEDIA_TYPE_VIDEO:
                        case FileColumns.MEDIA_TYPE_IMAGE:
                            assertNotNull(DocumentsContract.getDocumentMetadata(resolver, uri));
                    }
                }
            }
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

    private static void stageTestMedia(Context context) throws Exception {
        final File dir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        dir.mkdirs();
        FileUtils.deleteContents(dir);

        stage(R.raw.test_audio, new File(dir, "audio.mp3"));
        stage(R.raw.test_video, new File(dir, "video.mp4"));
        stage(R.raw.test_image, new File(dir, "image.jpg"));
        stage(R.raw.test_m3u, new File(dir, "playlist.m3u"));
        stage(R.raw.test_srt, new File(dir, "subtitle.srt"));
        stage(R.raw.test_txt, new File(dir, "document.txt"));
        stage(R.raw.test_bin, new File(dir, "random.bin"));

        final MediaScanner scanner = new ModernMediaScanner(context);
        scanner.scanDirectory(dir, REASON_UNKNOWN);
    }
}
