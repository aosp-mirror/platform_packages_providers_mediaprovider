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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipDescription;
import android.mtp.MtpConstants;
import android.provider.MediaStore.Files.FileColumns;

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
        assertEquals("image",
                MimeUtils.extractPrimaryType("image/jpeg"));
    }

    @Test
    public void testResolveMediaType() throws Exception {
        assertEquals(FileColumns.MEDIA_TYPE_AUDIO,
                MimeUtils.resolveMediaType("audio/mpeg"));
        assertEquals(FileColumns.MEDIA_TYPE_VIDEO,
                MimeUtils.resolveMediaType("video/mpeg"));
        assertEquals(FileColumns.MEDIA_TYPE_IMAGE,
                MimeUtils.resolveMediaType("image/jpeg"));
        assertEquals(FileColumns.MEDIA_TYPE_DOCUMENT,
                MimeUtils.resolveMediaType("text/plain"));
        assertEquals(FileColumns.MEDIA_TYPE_DOCUMENT,
                MimeUtils.resolveMediaType("application/pdf"));
        assertEquals(FileColumns.MEDIA_TYPE_DOCUMENT,
                MimeUtils.resolveMediaType("application/msword"));
        assertEquals(FileColumns.MEDIA_TYPE_DOCUMENT,
                MimeUtils.resolveMediaType("application/vnd.ms-excel"));
        assertEquals(FileColumns.MEDIA_TYPE_DOCUMENT,
                MimeUtils.resolveMediaType("application/vnd.ms-powerpoint"));
        assertEquals(FileColumns.MEDIA_TYPE_NONE,
                MimeUtils.resolveMediaType("application/x-does-not-exist"));

        // Make sure we catch playlists before audio
        assertEquals(FileColumns.MEDIA_TYPE_PLAYLIST,
                MimeUtils.resolveMediaType("audio/mpegurl"));
    }

    @Test
    public void testIsDocumentMimeType() throws Exception {
        assertTrue(MimeUtils.isDocumentMimeType(
                "application/vnd.ms-excel.addin.macroEnabled.12"));
        assertTrue(MimeUtils.isDocumentMimeType(
                "application/vnd.ms-powerpoint.addin.macroEnabled.12"));
        assertTrue(MimeUtils.isDocumentMimeType(
                "application/vnd.ms-word.document.macroEnabled.12"));

        assertFalse(MimeUtils.isDocumentMimeType(
                "application/zip"));
    }

    @Test
    public void testResolveFormatCode() throws Exception {
        assertEquals(MtpConstants.FORMAT_UNDEFINED_AUDIO,
                MimeUtils.resolveFormatCode("audio/mpeg"));
        assertEquals(MtpConstants.FORMAT_UNDEFINED_VIDEO,
                MimeUtils.resolveFormatCode("video/mpeg"));
        assertEquals(MtpConstants.FORMAT_DEFINED,
                MimeUtils.resolveFormatCode("image/jpeg"));
        assertEquals(MtpConstants.FORMAT_UNDEFINED,
                MimeUtils.resolveFormatCode("application/x-does-not-exist"));
    }
}
