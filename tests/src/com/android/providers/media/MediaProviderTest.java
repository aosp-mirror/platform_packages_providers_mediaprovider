/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.MediaProvider.getPathOwnerPackageName;

import static org.junit.Assert.assertEquals;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MediaProviderTest {
    @Test
    public void testPathOwnerPackageName_None() throws Exception {
        assertEquals(null, getPathOwnerPackageName(null));
        assertEquals(null, getPathOwnerPackageName("/data/path"));
    }

    @Test
    public void testPathOwnerPackageName_Emulated() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/DCIM/foo.jpg"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/"));
        assertEquals(null, getPathOwnerPackageName("/storage/emulated/0/Android/data/"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/data/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/obb/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/media/com.example/foo.jpg"));
        assertEquals("com.example",
                getPathOwnerPackageName("/storage/emulated/0/Android/sandbox/com.example/foo.jpg"));
    }

    @Test
    public void testPathOwnerPackageName_Portable() throws Exception {
        assertEquals(null, getPathOwnerPackageName("/storage/0000-0000/DCIM/foo.jpg"));

        assertEquals("com.example",
                getPathOwnerPackageName("/storage/0000-0000/Android/data/com.example/foo.jpg"));
    }
}
