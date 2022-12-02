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

import static com.google.common.truth.Truth.assertThat;

import android.icu.util.VersionInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class DateTimeUtilsTest {

    private static LocalDate FAKE_DATE =
            LocalDate.of(2020 /* year */, 7 /* month */, 7 /* dayOfMonth */);
    private static long FAKE_TIME =
            FAKE_DATE.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    /**
     * ICU 72 started to use '\u202f' instead of ' ' before AM/PM.
     */
    private static final char AM_PM_SPACE_CHAR = VersionInfo.ICU_VERSION.getMajor() >= 72
            ? '\u202f' : ' ';

    @Test
    public void testGetDateHeaderString_today() throws Exception {
        final long when = generateDateTimeMillis(FAKE_DATE);

        String result = DateTimeUtils.getDateHeaderString(when, FAKE_DATE);

        assertThat(result).isEqualTo(DateTimeUtils.getTodayString());
    }

    @Test
    public void testGetDateHeaderString_yesterday() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(1);
        final long when = generateDateTimeMillis(whenDate);

        final String result = DateTimeUtils.getDateHeaderString(when, FAKE_DATE);

        assertThat(result).isEqualTo(DateTimeUtils.getYesterdayString());
    }

    @Test
    public void testGetDateHeaderString_weekday() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(3);
        final long when = generateDateTimeMillis(whenDate);

        final String result = DateTimeUtils.getDateHeaderString(when, FAKE_DATE);

        assertThat(result).isEqualTo("Saturday");
    }

    @Test
    public void testGetDateHeaderString_weekdayAndDate() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusMonths(1);
        final long when = generateDateTimeMillis(whenDate);

        final String result = DateTimeUtils.getDateHeaderString(when, FAKE_DATE);

        assertThat(result).isEqualTo("Sun, Jun 7");
    }

    @Test
    public void testGetDateHeaderString_weekdayDateAndYear() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusYears(1);
        long when = generateDateTimeMillis(whenDate);

        final String result = DateTimeUtils.getDateHeaderString(when, FAKE_DATE);

        assertThat(result).isEqualTo("Sun, Jul 7, 2019");
    }

    /**
     * Test the capitalized issue in different languages b/208864827.
     * E.g. For pt-BR
     * Wrong format: ter, 16 de nov.
     * Right format: Ter, 16 de nov.
     */
    @Test
    public void testCapitalizedInDifferentLanguages() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusMonths(1).minusDays(4);;
        final long when = generateDateTimeMillis(whenDate);
        final String skeleton = "EMMMd";

        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("PT-BR")))
                .isEqualTo("Qua., 3 de jun.");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("ET")))
                .isEqualTo("K, 3. juuni");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("LV")))
                .isEqualTo("Trešd., 3. jūn.");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("BE")))
                .isEqualTo("Ср, 3 чэр");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("RU")))
                .isEqualTo("Ср, 3 июн.");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("SQ")))
                .isEqualTo("Mër, 3 qer");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("IT")))
                .isEqualTo("Mer 3 giu");
        assertThat(DateTimeUtils.getDateTimeString(when, skeleton, new Locale("KK")))
                .isEqualTo("3 мау., ср");
    }

    @Test
    public void testGetDateTimeStringForContentDesc() throws Exception {
        final long when = generateDateTimeMillis(FAKE_DATE);

        String result = DateTimeUtils.getDateTimeStringForContentDesc(when);

        assertThat(result).isEqualTo("Jul 7, 2020, 12:00:00" + AM_PM_SPACE_CHAR + "AM");
    }

    @Test
    public void testGetDateTimeStringForContentDesc_time() throws Exception {
        long when = generateDateTimeMillisAt(
                FAKE_DATE, /* hour */ 10, /* minute */ 10, /* second */ 10);

        final String result = DateTimeUtils.getDateTimeStringForContentDesc(when);

        assertThat(result).isEqualTo("Jul 7, 2020, 10:10:10" + AM_PM_SPACE_CHAR + "AM");
    }

    @Test
    public void testGetDateTimeStringForContentDesc_singleDigitHour() throws Exception {
        long when = generateDateTimeMillisAt(
                FAKE_DATE, /* hour */ 1, /* minute */ 0, /* second */ 0);

        final String result = DateTimeUtils.getDateTimeStringForContentDesc(when);

        assertThat(result).isEqualTo("Jul 7, 2020, 1:00:00" + AM_PM_SPACE_CHAR + "AM");
    }

    @Test
    public void testGetDateTimeStringForContentDesc_timePM() throws Exception {
        long when = generateDateTimeMillisAt(
                FAKE_DATE, /* hour */ 22, /* minute */ 0, /* second */ 0);

        final String result = DateTimeUtils.getDateTimeStringForContentDesc(when);

        assertThat(result).isEqualTo("Jul 7, 2020, 10:00:00" + AM_PM_SPACE_CHAR + "PM");
    }

    @Test
    public void testIsSameDay_differentYear_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusYears(1);
        long when = generateDateTimeMillis(whenDate);

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_differentMonth_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusMonths(1);
        final long when = generateDateTimeMillis(whenDate);

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_differentDay_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(2);
        final long when = generateDateTimeMillis(whenDate);

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_true() throws Exception {
        assertThat(DateTimeUtils.isSameDate(FAKE_TIME, FAKE_TIME)).isTrue();
    }

    private static long generateDateTimeMillis(LocalDate when) {
        return when.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private long generateDateTimeMillisAt(LocalDate when, int hour, int minute, int second) {
        return ZonedDateTime.of(when.atTime(hour, minute, second), ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }
}
