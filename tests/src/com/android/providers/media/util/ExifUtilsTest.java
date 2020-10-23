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

import android.media.ExifInterface;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import static org.junit.Assert.assertEquals;

public class ExifUtilsTest {
    @Test
    public void testConstructor() {
        new ExifUtils();
    }

    @Test
    public void testGetDateTime() throws Exception {
        final ExifInterface exif = createTestExif();
        assertParseDateTime(exif, ExifUtils::getDateTime);
    }

    @Test
    public void testGetDateTimeOriginal() throws Exception {
        final ExifInterface exif = createTestExif();
        assertParseDateTime(exif, ExifUtils::getDateTimeOriginal);
    }

    @Test
    public void testGetDateTimeDigitized() throws Exception {
        final ExifInterface exif = createTestExif();
        assertParseDateTime(exif, ExifUtils::getDateTimeDigitized);
    }

    @Test
    public void testGetGpsDateTime() throws Exception {
        final ExifInterface exif = createTestExif();
        assertParseDateTime(exif, ExifUtils::getGpsDateTime);
    }

    private ExifInterface createTestExif() throws Exception {
        final File file = File.createTempFile("test", ".jpg");
        final ExifInterface exif = new ExifInterface(file);
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2016:01:28 09:17:34");
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2016:01:28 09:17:34");
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "2016:01:28 09:17:34 UTC");
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2016:01:28");
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "09:14:00");
        return exif;
    }

    private void assertParseDateTime(ExifInterface exif, ToLongFunction<ExifInterface> func) {
        final int numOfThreads = 5;
        final CountDownLatch latch = new CountDownLatch(numOfThreads);
        final AtomicInteger count = new AtomicInteger(numOfThreads);

        for (int i = 0; i < numOfThreads; i++) {
            new Thread(() -> {
                if (parseDateTime(exif, func)) count.decrementAndGet();
                latch.countDown();
            }).start();
        }

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        assertEquals(0, count.get());
    }

    private boolean parseDateTime(ExifInterface exif, ToLongFunction<ExifInterface> func) {
        final long expected = func.applyAsLong(exif);
        try {
            for (int i = 0; i < 1000; ++i) {
                final long actual = func.applyAsLong(exif);
                if (expected != actual) {
                    return false;
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            return false;
        }
        return true;
    }

    @Test
    public void testSubSeconds() throws Exception {
        assertEquals(0L, ExifUtils.parseSubSeconds("0"));
        assertEquals(100L, ExifUtils.parseSubSeconds("1"));
        assertEquals(10L, ExifUtils.parseSubSeconds("01"));
        assertEquals(120L, ExifUtils.parseSubSeconds("12"));
        assertEquals(123L, ExifUtils.parseSubSeconds("123"));
        assertEquals(123L, ExifUtils.parseSubSeconds("1234"));
        assertEquals(12L, ExifUtils.parseSubSeconds("01234"));
    }

}

