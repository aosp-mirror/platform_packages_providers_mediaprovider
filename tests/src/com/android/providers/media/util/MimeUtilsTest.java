/*
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

package com.android.providers.media.util;

import static org.junit.Assert.assertEquals;

import android.content.ClipDescription;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class MimeUtilsTest {
    @Test
    public void testResolveMimeType() throws Exception {
        assertEquals("image/jpeg",
                MimeUtils.resolveMimeType(new File("foo.jpg")));

        assertEquals(ClipDescription.MIMETYPE_UNKNOWN,
                MimeUtils.resolveMimeType(new File("foo")));
        assertEquals(ClipDescription.MIMETYPE_UNKNOWN,
                MimeUtils.resolveMimeType(new File("foo.doesnotexist")));
    }

    @Test
    public void testExtractPrimaryType() throws Exception {
        assertEquals("image", MimeUtils.extractPrimaryType("image/jpeg"));
    }
}
