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

package com.android.providers.media.util;

import static android.media.ExifInterface.TAG_DATETIME;
import static android.media.ExifInterface.TAG_DATETIME_DIGITIZED;
import static android.media.ExifInterface.TAG_DATETIME_ORIGINAL;
import static android.media.ExifInterface.TAG_GPS_DATESTAMP;
import static android.media.ExifInterface.TAG_GPS_TIMESTAMP;
import static android.media.ExifInterface.TAG_OFFSET_TIME;
import static android.media.ExifInterface.TAG_OFFSET_TIME_DIGITIZED;
import static android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL;
import static android.media.ExifInterface.TAG_SUBSEC_TIME;
import static android.media.ExifInterface.TAG_SUBSEC_TIME_DIGITIZED;
import static android.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.media.ExifInterface;

import androidx.annotation.NonNull;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;


/**
 * Utility methods borrowed from {@link ExifInterface} since they're not
 * official APIs yet.
 */
public class ExifUtils {
    // Pattern to check non zero timestamp
    private static final Pattern sNonZeroTimePattern = Pattern.compile(".*[1-9].*");

    private static final SimpleDateFormat sFormatter;
    private static final SimpleDateFormat sFormatterTz;

    static {
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        sFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        sFormatterTz = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss XXX");
        sFormatterTz.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Returns parsed {@code DateTime} value, or -1 if unavailable or invalid.
     */
    public static @CurrentTimeMillisLong long getDateTime(@NonNull ExifInterface exif) {
        return parseDateTime(exif.getAttribute(TAG_DATETIME),
                exif.getAttribute(TAG_SUBSEC_TIME),
                exif.getAttribute(TAG_OFFSET_TIME));
    }

    /**
     * Returns parsed {@code DateTimeDigitized} value, or -1 if unavailable or
     * invalid.
     */
    public static @CurrentTimeMillisLong long getDateTimeDigitized(@NonNull ExifInterface exif) {
        return parseDateTime(exif.getAttribute(TAG_DATETIME_DIGITIZED),
                exif.getAttribute(TAG_SUBSEC_TIME_DIGITIZED),
                exif.getAttribute(TAG_OFFSET_TIME_DIGITIZED));
    }

    /**
     * Returns parsed {@code DateTimeOriginal} value, or -1 if unavailable or
     * invalid.
     */
    public static @CurrentTimeMillisLong long getDateTimeOriginal(@NonNull ExifInterface exif) {
        return parseDateTime(exif.getAttribute(TAG_DATETIME_ORIGINAL),
                exif.getAttribute(TAG_SUBSEC_TIME_ORIGINAL),
                exif.getAttribute(TAG_OFFSET_TIME_ORIGINAL));
    }

    /**
     * Returns parsed {@code GPSDateStamp} value, or -1 if unavailable or
     * invalid.
     */
    public static long getGpsDateTime(ExifInterface exif) {
        String date = exif.getAttribute(TAG_GPS_DATESTAMP);
        String time = exif.getAttribute(TAG_GPS_TIMESTAMP);
        if (date == null || time == null
                || (!sNonZeroTimePattern.matcher(date).matches()
                && !sNonZeroTimePattern.matcher(time).matches())) {
            return -1;
        }

        String dateTimeString = date + ' ' + time;

        ParsePosition pos = new ParsePosition(0);
        try {
            Date datetime = sFormatter.parse(dateTimeString, pos);
            if (datetime == null) return -1;
            return datetime.getTime();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static @CurrentTimeMillisLong long parseDateTime(@Nullable String dateTimeString,
            @Nullable String subSecs, @Nullable String offsetString) {
        if (dateTimeString == null
                || !sNonZeroTimePattern.matcher(dateTimeString).matches()) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            // The exif field is in local time. Parsing it as if it is UTC will yield time
            // since 1/1/1970 local time
            Date datetime = sFormatter.parse(dateTimeString, pos);

            if (offsetString != null) {
                dateTimeString = dateTimeString + " " + offsetString;
                ParsePosition position = new ParsePosition(0);
                datetime = sFormatterTz.parse(dateTimeString, position);
            }

            if (datetime == null) return -1;
            long msecs = datetime.getTime();

            if (subSecs != null) {
                try {
                    long sub = Long.parseLong(subSecs);
                    while (sub > 1000) {
                        sub /= 10;
                    }
                    msecs += sub;
                } catch (NumberFormatException e) {
                    // Ignored
                }
            }
            return msecs;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }
}
