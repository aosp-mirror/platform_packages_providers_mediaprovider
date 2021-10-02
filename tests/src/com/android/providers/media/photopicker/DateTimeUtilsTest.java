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

package com.android.providers.media.photopicker;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class DateTimeUtilsTest {

    private Context mContext;
    private static LocalDate FAKE_DATE =
            LocalDate.of(2020 /* year */, 7 /* month */, 7 /* dayOfMonth */);
    private static long FAKE_TIME =
            FAKE_DATE.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testGetDateTimeString_today() throws Exception {
        final String result = DateTimeUtils.getDateTimeString(mContext, FAKE_TIME, FAKE_DATE);

        assertThat(result).isEqualTo(DateTimeUtils.getTodayString());
    }

    @Test
    public void testGetDateTimeString_yesterday() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(1);
        final long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        final String result = DateTimeUtils.getDateTimeString(mContext, when, FAKE_DATE);

        assertThat(result).isEqualTo(DateTimeUtils.getYesterdayString());
    }

    @Test
    public void testGetDateTimeString_weekday() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(3);
        final long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        final String result = DateTimeUtils.getDateTimeString(mContext, when, FAKE_DATE);

        assertThat(result).isEqualTo("Saturday");
    }

    @Test
    public void testGetDateTimeString_weekdayAndDate() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusMonths(1);
        final long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        final String result = DateTimeUtils.getDateTimeString(mContext, when, FAKE_DATE);

        assertThat(result).isEqualTo("Sun, Jun 7");
    }

    @Test
    public void testGetDateTimeString_weekdayDateAndYear() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusYears(1);
        long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        final String result = DateTimeUtils.getDateTimeString(mContext, when, FAKE_DATE);

        assertThat(result).isEqualTo("Sun, Jul 7, 2019");
    }

    @Test
    public void testIsSameDay_differentYear_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusYears(1);
        long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_differentMonth_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusMonths(1);
        final long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_differentDay_false() throws Exception {
        final LocalDate whenDate = FAKE_DATE.minusDays(1);
        final long when = generateDateTimeMillis(whenDate.getYear(), whenDate.getMonthValue(),
                whenDate.getDayOfMonth());

        assertThat(DateTimeUtils.isSameDate(when, FAKE_TIME)).isFalse();
    }

    @Test
    public void testIsSameDay_true() throws Exception {
        assertThat(DateTimeUtils.isSameDate(FAKE_TIME, FAKE_TIME)).isTrue();
    }

    private static long generateDateTimeMillis(int year, int month, int dayOfMonth) {
        final LocalDate result = LocalDate.of(year, month, dayOfMonth);
        return result.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}

