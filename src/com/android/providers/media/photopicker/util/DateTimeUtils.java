/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.util;

import static android.icu.text.DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE;
import static android.icu.text.RelativeDateTimeFormatter.Style.LONG;

import android.content.Context;
import android.icu.text.RelativeDateTimeFormatter;
import android.icu.text.RelativeDateTimeFormatter.Direction;
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit;
import android.icu.util.ULocale;
import android.text.format.DateUtils;

import androidx.annotation.VisibleForTesting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Provide the utility methods to handle date time.
 */
public class DateTimeUtils {

    /**
     * Formats a time according to the local conventions.
     *
     * If the difference of the date between the time and now is zero, show
     * "Today".
     * If the difference is 1, show "Yesterday".
     * If the difference is less than 7, show the weekday. E.g. "Sunday".
     * Otherwise, show the weekday and the date. E.g. "Sat, Jun 5".
     * If they have different years, show the weekday, the date and the year.
     * E.g. "Sat, Jun 5, 2021"
     *
     * @param context the context
     * @param when    the time to be formatted. The unit is in milliseconds
     *                since January 1, 1970 00:00:00.0 UTC.
     * @return the formatted string
     */
    public static String getDateTimeString(Context context, long when) {
        // Get the system time zone
        final ZoneId zoneId = ZoneId.systemDefault();
        final LocalDate nowDate = LocalDate.now(zoneId);

        return getDateTimeString(context, when, nowDate);
    }

    @VisibleForTesting
    static String getDateTimeString(Context context, long when, LocalDate nowDate) {
        // Get the system time zone
        final ZoneId zoneId = ZoneId.systemDefault();
        final LocalDate whenDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(when),
                zoneId).toLocalDate();

        final long dayDiff = ChronoUnit.DAYS.between(whenDate, nowDate);
        if (dayDiff == 0) {
            return getTodayString();
        } else if (dayDiff == 1) {
            return getYesterdayString();
        } else if (dayDiff > 0 && dayDiff < 7) {
            return whenDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
        } else {
            int flags = DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                    | DateUtils.FORMAT_ABBREV_ALL;
            if (whenDate.getYear() == nowDate.getYear()) {
                flags |= DateUtils.FORMAT_NO_YEAR;
            } else {
                flags |= DateUtils.FORMAT_SHOW_YEAR;
            }
            return DateUtils.formatDateTime(context, when, flags);
        }
    }

    /**
     * It is borrowed from {@link DateUtils} since it is no official API yet.
     *
     * @param oneMillis the first time. The unit is in milliseconds since
     *                  January 1, 1970 00:00:00.0 UTC.
     * @param twoMillis the second time. The unit is in milliseconds since
     *                  January 1, 1970 00:00:00.0 UTC.
     * @return True, the date is the same. Otherwise, return false.
     */
    public static boolean isSameDate(long oneMillis, long twoMillis) {
        // Get the system time zone
        final ZoneId zoneId = ZoneId.systemDefault();

        final Instant oneInstant = Instant.ofEpochMilli(oneMillis);
        final LocalDateTime oneLocalDateTime = LocalDateTime.ofInstant(oneInstant, zoneId);

        final Instant twoInstant = Instant.ofEpochMilli(twoMillis);
        final LocalDateTime twoLocalDateTime = LocalDateTime.ofInstant(twoInstant, zoneId);

        return (oneLocalDateTime.getYear() == twoLocalDateTime.getYear())
                && (oneLocalDateTime.getMonthValue() == twoLocalDateTime.getMonthValue())
                && (oneLocalDateTime.getDayOfMonth() == twoLocalDateTime.getDayOfMonth());
    }

    @VisibleForTesting
    static String getTodayString() {
        final RelativeDateTimeFormatter fmt = RelativeDateTimeFormatter.getInstance(
                ULocale.getDefault(), null, LONG, CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
        return fmt.format(Direction.THIS, AbsoluteUnit.DAY);
    }

    @VisibleForTesting
    static String getYesterdayString() {
        final RelativeDateTimeFormatter fmt = RelativeDateTimeFormatter.getInstance(
                ULocale.getDefault(), null, LONG, CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE);
        return fmt.format(Direction.LAST, AbsoluteUnit.DAY);
    }
}
