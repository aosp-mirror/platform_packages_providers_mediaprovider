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

package com.android.providers.media.scan;

import static android.provider.MediaStore.VOLUME_EXTERNAL;

import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.MediaDocumentsProvider;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.R;
import com.android.providers.media.util.FileUtils;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class MediaScannerTest {
    private static final String TAG = "MediaScannerTest";

    public static class IsolatedContext extends ContextWrapper {
        private final File mDir;
        private final MockContentResolver mResolver;
        private final MediaProvider mProvider;
        private final MediaDocumentsProvider mDocumentsProvider;

        public IsolatedContext(Context base, String tag) {
            super(base);
            mDir = new File(base.getFilesDir(), tag);
            mDir.mkdirs();
            FileUtils.deleteContents(mDir);

            mResolver = new MockContentResolver(this);

            final ProviderInfo info = base.getPackageManager()
                    .resolveContentProvider(MediaStore.AUTHORITY, 0);
            mProvider = new MediaProvider();
            mProvider.attachInfo(this, info);
            mResolver.addProvider(MediaStore.AUTHORITY, mProvider);

            final ProviderInfo documentsInfo = base.getPackageManager()
                    .resolveContentProvider(MediaDocumentsProvider.AUTHORITY, 0);
            mDocumentsProvider = new MediaDocumentsProvider();
            mDocumentsProvider.attachInfo(this, documentsInfo);
            mResolver.addProvider(MediaDocumentsProvider.AUTHORITY, mDocumentsProvider);

            mResolver.addProvider(Settings.AUTHORITY, new MockContentProvider() {
                @Override
                public Bundle call(String method, String request, Bundle args) {
                    return Bundle.EMPTY;
                }
            });

            MediaStore.waitForIdle(mResolver);
        }

        @Override
        public File getDatabasePath(String name) {
            return new File(mDir, name);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }

    private MediaScanner mLegacy;
    private MediaScanner mModern;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();

        mLegacy = new LegacyMediaScanner(new IsolatedContext(context, "legacy"));
        mModern = new ModernMediaScanner(new IsolatedContext(context, "modern"));
    }

    /**
     * Ask both legacy and modern scanners to example sample files and assert
     * the resulting database modifications are identical.
     */
    @Test
    @Ignore
    public void testCorrectness() throws Exception {
        final File dir = Environment.getExternalStorageDirectory();
        stage(R.raw.test_audio, new File(dir, "test.mp3"));
        stage(R.raw.test_video, new File(dir, "test.mp4"));
        stage(R.raw.test_image, new File(dir, "test.jpg"));

        // Execute both scanners in isolation
        scanDirectory(mLegacy, dir, "legacy");
        scanDirectory(mModern, dir, "modern");

        // Confirm that they both agree on scanned details
        for (Uri uri : new Uri[] {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        }) {
            final Context legacyContext = mLegacy.getContext();
            final Context modernContext = mModern.getContext();
            try (Cursor cl = legacyContext.getContentResolver().query(uri, null, null, null);
                    Cursor cm = modernContext.getContentResolver().query(uri, null, null, null)) {
                try {
                    // Must have same count
                    assertEquals(cl.getCount(), cm.getCount());

                    while (cl.moveToNext() && cm.moveToNext()) {
                        for (int i = 0; i < cl.getColumnCount(); i++) {
                            final String columnName = cl.getColumnName(i);
                            if (columnName.equals(MediaColumns._ID)) continue;
                            if (columnName.equals(MediaColumns.DATE_ADDED)) continue;

                            // Must have same name
                            assertEquals(cl.getColumnName(i), cm.getColumnName(i));
                            // Must have same data types
                            assertEquals(columnName + " type",
                                    cl.getType(i), cm.getType(i));
                            // Must have same contents
                            assertEquals(columnName + " value",
                                    cl.getString(i), cm.getString(i));
                        }
                    }
                } catch (AssertionError e) {
                    Log.d(TAG, "Legacy:");
                    DatabaseUtils.dumpCursor(cl);
                    Log.d(TAG, "Modern:");
                    DatabaseUtils.dumpCursor(cm);
                    throw e;
                }
            }
        }
    }

    @Test
    @Ignore
    public void testSpeed_Legacy() throws Exception {
        testSpeed(mLegacy);
    }

    @Test
    @Ignore
    public void testSpeed_Modern() throws Exception {
        testSpeed(mModern);
    }

    private void testSpeed(MediaScanner scanner) throws IOException {
        final File scanDir = Environment.getExternalStorageDirectory();
        final File dir = new File(Environment.getExternalStorageDirectory(),
                "test" + System.nanoTime());

        stage(dir, 4, 3);
        scanDirectory(scanner, scanDir, "Initial");
        scanDirectory(scanner, scanDir, "No-op");

        FileUtils.deleteContents(dir);
        dir.delete();
        scanDirectory(scanner, scanDir, "Clean");
    }

    private static void scanDirectory(MediaScanner scanner, File dir, String tag) {
        final Context context = scanner.getContext();
        final long beforeTime = SystemClock.elapsedRealtime();
        final int[] beforeCounts = getCounts(context);

        scanner.scanDirectory(dir, REASON_UNKNOWN);

        final long deltaTime = SystemClock.elapsedRealtime() - beforeTime;
        final int[] deltaCounts = subtract(getCounts(context), beforeCounts);
        Log.i(TAG, "Scan " + tag + ": " + deltaTime + "ms " + Arrays.toString(deltaCounts));
    }

    private static int[] subtract(int[] a, int[] b) {
        final int[] c = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] - b[i];
        }
        return c;
    }

    private static int[] getCounts(Context context) {
        return new int[] {
                getCount(context, MediaStore.Files.getContentUri(VOLUME_EXTERNAL)),
                getCount(context, MediaStore.Audio.Media.getContentUri(VOLUME_EXTERNAL)),
                getCount(context, MediaStore.Video.Media.getContentUri(VOLUME_EXTERNAL)),
                getCount(context, MediaStore.Images.Media.getContentUri(VOLUME_EXTERNAL)),
        };
    }

    private static int getCount(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[] { BaseColumns._ID }, null, null)) {
            return c.getCount();
        }
    }

    private static void stage(File dir, int deep, int wide) throws IOException {
        dir.mkdirs();

        if (deep > 0) {
            stage(new File(dir, "dir" + System.nanoTime()), deep - 1, wide * 2);
        }

        for (int i = 0; i < wide; i++) {
            stage(R.raw.test_image, new File(dir, System.nanoTime() + ".jpg"));
            stage(R.raw.test_video, new File(dir, System.nanoTime() + ".mp4"));
        }
    }

    public static File stage(int resId, File file) throws IOException {
        final Context context = InstrumentationRegistry.getContext();
        try (InputStream source = context.getResources().openRawResource(resId);
                OutputStream target = new FileOutputStream(file)) {
            FileUtils.copy(source, target);
        }
        return file;
    }
}
