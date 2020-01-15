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

import static org.junit.Assert.assertNotNull;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.scan.MediaScannerTest.IsolatedContext;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class MediaDocumentsProviderTest {
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
        }) {
            assertProbe(resolver, "root", root, "search");

            assertProbe(resolver, "document", root);
            assertProbe(resolver, "document", root, "children");
        }

        for (String recent : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_ROOT,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT,
        }) {
            assertProbe(resolver, "root", recent, "recent");
        }

        for (String dir : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
        }) {
            assertProbe(resolver, "document", dir, "children");
        }

        for (String item : new String[] {
                MediaDocumentsProvider.TYPE_ARTIST,
                MediaDocumentsProvider.TYPE_ALBUM,
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,

                MediaDocumentsProvider.TYPE_AUDIO,
                MediaDocumentsProvider.TYPE_VIDEO,
                MediaDocumentsProvider.TYPE_IMAGE,
        }) {
                assertProbe(resolver, "document", item);
        }
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
}
