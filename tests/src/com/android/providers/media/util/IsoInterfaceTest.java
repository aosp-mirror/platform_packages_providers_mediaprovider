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

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class IsoInterfaceTest {
    @Test
    public void testSupportedMimeTypes() throws Exception {
        assertTrue(IsoInterface.isSupportedMimeType("video/mp4"));
        assertFalse(IsoInterface.isSupportedMimeType("image/jpeg"));
    }

    @Test
    public void testTypeToString() throws Exception {
        assertEquals("moov", IsoInterface.typeToString(0x6d6f6f76));
    }

    @Test
    public void testRepeated() throws Exception {
        final File file = stageFile(R.raw.test_video);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        final long[] ranges = mp4.getBoxRanges(0x746b6864); // tkhd
        assertEquals(4, ranges.length);
        assertEquals(105534 + 8, ranges[0]);
        assertEquals(105534 + 92, ranges[1]);
        assertEquals(118275 + 8, ranges[2]);
        assertEquals(118275 + 92, ranges[3]);
    }

    @Test
    public void testGps() throws Exception {
        final File file = stageFile(R.raw.test_video_gps);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        final long[] ranges = mp4.getBoxRanges(0xa978797a); // ?xyz
        assertEquals(2, ranges.length);
        assertEquals(3369 + 8, ranges[0]);
        assertEquals(3369 + 30, ranges[1]);
    }

    @Test
    public void testXmp() throws Exception {
        final File file = stageFile(R.raw.test_video_xmp);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        final XmpInterface xmp = XmpInterface.fromContainer(mp4);

        assertEquals("image/dng", xmp.getFormat());
        assertEquals("xmp.did:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getDocumentId());
        assertEquals("xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getInstanceId());
        assertEquals("3F9DD7A46B26513A7C35272F0D623A06", xmp.getOriginalDocumentId());
    }

    @Test
    public void testIsoMeta() throws Exception {
        final IsoInterface isoMeta = IsoInterface.fromFile(stageFile(R.raw.test_video_xmp));
        final long[] hdlrRanges = isoMeta.getBoxRanges(IsoInterface.BOX_HDLR);

        // There are 3 hdlr boxes, the 3rd is inside the meta box. Check it was parsed correctly.
        assertEquals(3 * 2, hdlrRanges.length);
        assertEquals(30145, hdlrRanges[2 * 2 + 0]);
        assertEquals(30170, hdlrRanges[2 * 2 + 1]);
    }

    @Test
    public void testQtMeta() throws Exception {
        final IsoInterface qtMeta = IsoInterface.fromFile(stageFile(R.raw.testvideo_meta));
        final long[] hdlrRanges = qtMeta.getBoxRanges(IsoInterface.BOX_HDLR);

        // There are 3 hdlr boxes, the 1st is inside the meta box. Check it was parsed correctly.
        assertEquals(3 * 2, hdlrRanges.length);
        assertEquals(16636, hdlrRanges[2 * 0 + 0]);
        assertEquals(16661, hdlrRanges[2 * 0 + 1]);
    }

    private static File stageFile(int resId) throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        final File file = File.createTempFile("test", ".mp4");
        try (InputStream in = context.getResources().openRawResource(resId);
                OutputStream out = new FileOutputStream(file)) {
            FileUtils.copy(in, out);
        }
        return file;
    }
}
