/**
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

package com.android.tests.fused.lib;

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.fail;

import android.media.ExifInterface;

import androidx.annotation.NonNull;
import androidx.annotation.RawRes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Objects;

/**
 * Helper functions and utils for redactions tests
 */
public class RedactionTestHelper {
    private static final String TAG = "RedactionTestHelper";

    private static final String[] EXIF_GPS_TAGS = {
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_VERSION_ID,
    };

    public static final String EXIF_METADATA_QUERY = "com.android.tests.fused.exif";

    @NonNull
    public static HashMap<String, String> getExifMetadata(@NonNull File file) throws IOException {
        final ExifInterface exif = new ExifInterface(file);
        return dumpExifGpsTagsToMap(exif);
    }

    @NonNull
    public static HashMap<String, String> getExifMetadataFromRawResource(@RawRes int resId)
            throws IOException {
        final ExifInterface exif;
        try (InputStream in = getContext().getResources().openRawResource(resId)) {
            exif = new ExifInterface(in);
        }
        return dumpExifGpsTagsToMap(exif);
    }

    public static void assertExifMetadataMatch(@NonNull HashMap<String, String> actual,
            @NonNull HashMap<String, String> expected) {
        for (String tag : EXIF_GPS_TAGS) {
            assertMetadataEntryMatch(tag, actual.get(tag), expected.get(tag));
        }
    }

    public static void assertExifMetadataMismatch(@NonNull HashMap<String, String> actual,
            @NonNull HashMap<String, String> expected) {
        for (String tag : EXIF_GPS_TAGS) {
            assertMetadataEntryMismatch(tag, actual.get(tag), expected.get(tag));
        }
    }

    private static void assertMetadataEntryMatch(String tag, String actual, String expected) {
        if (!Objects.equals(actual, expected)) {
            fail("Unexpected metadata mismatch for tag: " + tag + "\n"
                    + "expected:" + expected + "\n"
                    + "but was: " + actual);
        }
    }

    private static void assertMetadataEntryMismatch(String tag, String actual, String expected) {
        if (Objects.equals(actual, expected)) {
            fail("Unexpected metadata match for tag: " + tag + "\n"
                    + "expected not to be:" + expected);
        }
    }

    private static HashMap<String, String> dumpExifGpsTagsToMap(ExifInterface exif) {
        final HashMap<String, String> res = new HashMap<>();
        for (String tag : EXIF_GPS_TAGS) {
            res.put(tag, exif.getAttribute(tag));
        }
        return res;
    }
}
