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

package com.android.providers.media.scan;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class LegacyMediaScannerTest {
    @Test
    public void testSimple() throws Exception {
        final LegacyMediaScanner scanner = new LegacyMediaScanner(
                InstrumentationRegistry.getTargetContext());
        assertNotNull(scanner.getContext());

        try {
            scanner.scanDirectory(new File("/dev/null"), MediaScanner.REASON_UNKNOWN);
            fail();
        } catch (UnsupportedOperationException expected) {
        }
        try {
            scanner.scanFile(new File("/dev/null"), MediaScanner.REASON_UNKNOWN);
            fail();
        } catch (UnsupportedOperationException expected) {
        }
        try {
            scanner.onDetachVolume(null);
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    /**
      * This implementation was copied verbatim from the legacy
      * {@code frameworks/base/media/java/android/media/MediaScanner.java}.
      */
    static boolean isNonMediaFile(String path) {
        // special case certain file names
        // I use regionMatches() instead of substring() below
        // to avoid memory allocation
        final int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 2 < path.length()) {
            // ignore those ._* files created by MacOS
            if (path.regionMatches(lastSlash + 1, "._", 0, 2)) {
                return true;
            }

            // ignore album art files created by Windows Media Player:
            // Folder.jpg, AlbumArtSmall.jpg, AlbumArt_{...}_Large.jpg
            // and AlbumArt_{...}_Small.jpg
            if (path.regionMatches(true, path.length() - 4, ".jpg", 0, 4)) {
                if (path.regionMatches(true, lastSlash + 1, "AlbumArt_{", 0, 10) ||
                        path.regionMatches(true, lastSlash + 1, "AlbumArt.", 0, 9)) {
                    return true;
                }
                int length = path.length() - lastSlash - 1;
                if ((length == 17 && path.regionMatches(
                        true, lastSlash + 1, "AlbumArtSmall", 0, 13)) ||
                        (length == 10
                         && path.regionMatches(true, lastSlash + 1, "Folder", 0, 6))) {
                    return true;
                }
            }
        }
        return false;
    }
}
