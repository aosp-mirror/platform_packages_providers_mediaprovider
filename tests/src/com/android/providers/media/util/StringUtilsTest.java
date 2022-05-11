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

package com.android.providers.media.util;

import static com.android.providers.media.util.StringUtils.equalIgnoreCase;
import static com.android.providers.media.util.StringUtils.startsWithIgnoreCase;
import static com.android.providers.media.util.StringUtils.verifySupportedUncachedRelativePaths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class StringUtilsTest {
    @Test
    public void testEqualIgnoreCase() throws Exception {
        assertTrue(equalIgnoreCase("image/jpg", "image/jpg"));
        assertTrue(equalIgnoreCase("image/jpg", "Image/Jpg"));

        assertFalse(equalIgnoreCase("image/jpg", "image/png"));
        assertFalse(equalIgnoreCase("image/jpg", null));
        assertFalse(equalIgnoreCase(null, "image/jpg"));
        assertFalse(equalIgnoreCase(null, null));
    }

    @Test
    public void testStartsWithIgnoreCase() throws Exception {
        assertTrue(startsWithIgnoreCase("image/jpg", "image/"));
        assertTrue(startsWithIgnoreCase("Image/Jpg", "image/"));

        assertFalse(startsWithIgnoreCase("image/", "image/jpg"));

        assertFalse(startsWithIgnoreCase("image/jpg", "audio/"));
        assertFalse(startsWithIgnoreCase("image/jpg", null));
        assertFalse(startsWithIgnoreCase(null, "audio/"));
        assertFalse(startsWithIgnoreCase(null, null));
    }

    @Test public void testVerifySupportedUncachedRelativePaths() throws Exception {
        assertEquals(
                new ArrayList<String>(Arrays.asList("path/", "path/path/")),
                verifySupportedUncachedRelativePaths(
                        new ArrayList<String>(
                                Arrays.asList(null, "", "/",
                                              "path", "/path", "path/", "/path/",
                                              "path/path", "/path/path", "path/path/"))));
    }
}
