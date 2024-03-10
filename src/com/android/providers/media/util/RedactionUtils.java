/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.media.ExifInterface;
import android.os.Trace;
import android.util.ArraySet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

public class RedactionUtils {
    /**
     * Set of Exif tags that should be considered for redaction.
     */
    private static final String[] REDACTED_EXIF_TAGS = new String[]{
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_VERSION_ID,
    };

    /**
     * Set of ISO boxes that should be considered for redaction.
     */
    private static final int[] REDACTED_ISO_BOXES = new int[]{
            IsoInterface.BOX_LOCI,
            IsoInterface.BOX_XYZ,
            IsoInterface.BOX_GPS,
            IsoInterface.BOX_GPS0,
    };

    private static final Set<String> sRedactedExifTags = new ArraySet<>(
            Arrays.asList(REDACTED_EXIF_TAGS));

    /**
     * Set of Exif tags that should be considered for redaction.
     */
    public static Set<String> getsRedactedExifTags() {
        return sRedactedExifTags;
    }

    /**
     * Calculates the ranges containing sensitive metadata that should be redacted if the caller
     * doesn't have the required permissions.
     *
     * @param file file to be redacted
     * @return the ranges to be redacted in a RedactionInfo object, could be empty redaction ranges
     * if there's sensitive metadata
     * @throws IOException if an IOException happens while calculating the redaction ranges
     */
    public static long[] getRedactionRanges(File file) throws IOException {
        try (FileInputStream is = new FileInputStream(file)) {
            return getRedactionRanges(is, MimeUtils.resolveMimeType(file));
        } catch (FileNotFoundException ignored) {
            // If file not found, then there's nothing to redact
            return new long[0];
        } catch (IOException e) {
            throw new IOException("Failed to redact " + file, e);
        }
    }

    /**
     * Calculates the ranges containing sensitive metadata that should be redacted if the caller
     * doesn't have the required permissions.
     *
     * @param fis {@link FileInputStream} to be redacted
     * @return the ranges to be redacted in a RedactionInfo object, could be empty redaction ranges
     * if there's sensitive metadata
     * @throws IOException if an IOException happens while calculating the redaction ranges
     */
    public static long[] getRedactionRanges(FileInputStream fis, String mimeType)
            throws IOException {
        final LongArray res = new LongArray();

        Trace.beginSection("MP.getRedactionRanges");
        try {
            if (ExifInterface.isSupportedMimeType(mimeType)) {
                final ExifInterface exif = new ExifInterface(fis.getFD());
                for (String tag : REDACTED_EXIF_TAGS) {
                    final long[] range = exif.getAttributeRange(tag);
                    if (range != null) {
                        res.add(range[0]);
                        res.add(range[0] + range[1]);
                    }
                }
                // Redact xmp where present
                res.addAll(XmpDataParser.getRedactionRanges(exif));
            }

            if (IsoInterface.isSupportedMimeType(mimeType)) {
                final IsoInterface iso = IsoInterface.fromFileDescriptor(fis.getFD());
                for (int box : REDACTED_ISO_BOXES) {
                    final long[] ranges = iso.getBoxRanges(box);
                    for (int i = 0; i < ranges.length; i += 2) {
                        long boxTypeOffset = ranges[i] - 4;
                        res.add(boxTypeOffset);
                        res.add(ranges[i + 1]);
                    }
                }
                // Redact xmp where present
                res.addAll(XmpDataParser.getRedactionRanges(iso));
            }

            return res.toArray();
        } finally {
            Trace.endSection();
        }
    }
}
