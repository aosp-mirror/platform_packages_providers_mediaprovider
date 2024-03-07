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

import static com.android.providers.media.util.FileCreationUtils.stageMp4File;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.providers.media.R;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class IsoInterfaceTest {
    @Test
    public void testSupportedMimeTypes() throws Exception {
        assertThat(IsoInterface.isSupportedMimeType("video/mp4")).isTrue();
        assertThat(IsoInterface.isSupportedMimeType("image/jpeg")).isFalse();
    }

    @Test
    public void testTypeToString() throws Exception {
        assertThat(IsoInterface.typeToString(0x6d6f6f76)).isEqualTo("moov");
    }

    @Test
    public void testRepeated() throws Exception {
        final File file = stageMp4File(R.raw.test_video);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        final long[] ranges = mp4.getBoxRanges(0x746b6864); // tkhd
        assertThat(ranges.length).isEqualTo(4);
        assertThat(ranges[0]).isEqualTo(105534 + 8);
        assertThat(ranges[1]).isEqualTo(105534 + 92);
        assertThat(ranges[2]).isEqualTo(118275 + 8);
        assertThat(ranges[3]).isEqualTo(118275 + 92);
    }

    @Test
    public void testGps() throws Exception {
        final File file = stageMp4File(R.raw.test_video_gps);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        final long[] ranges = mp4.getBoxRanges(0xa978797a); // ?xyz
        assertThat(ranges.length).isEqualTo(2);
        assertThat(ranges[0]).isEqualTo(3369 + 8);
        assertThat(ranges[1]).isEqualTo(3369 + 30);
    }

    @Test
    public void testGpsInIlst() throws Exception {
        final File file = stageMp4File(R.raw.test_video_gps_ilst_tag);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        final long[] ranges = mp4.getBoxRanges(0xa978797a); // ?xyz
        assertThat(ranges.length).isEqualTo(4);
        assertThat(ranges[0]).isEqualTo(2267);
        assertThat(ranges[1]).isEqualTo(2267);
        assertThat(ranges[2]).isEqualTo(2275);
        assertThat(ranges[3]).isEqualTo(2309);
    }

    @Test
    public void testXmp() throws Exception {
        final File file = stageMp4File(R.raw.test_video_xmp);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        final XmpInterface xmp = XmpDataParser.createXmpInterface(mp4);

        assertThat(xmp.getFormat()).isEqualTo("image/dng");
        assertThat(xmp.getDocumentId()).isEqualTo("xmp.did:041dfd42-0b46-4302-918a-836fba5016ed");
        assertThat(xmp.getInstanceId()).isEqualTo("xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed");
        assertThat(xmp.getOriginalDocumentId()).isEqualTo("3F9DD7A46B26513A7C35272F0D623A06");
    }


    @Test
    @Ignore // This test creates a file that causes MediaProvider to OOM with our current
    // IsoInterface implementation.
    // While MediaProvider should now be resistant to that, we cannot leave this test safely enabled
    // in a test suite as for b/316578793
    // Leaving its implementation here to test further improvement to IsoInterface implementation.
    public void testFileWithTooManyBoxesDoesNotRunOutOfMemory() throws Exception {
        final File file = createFileWithLotsOfBoxes("too-many-boxes");
        assertThrows(IOException.class, () -> IsoInterface.fromFile(file));
    }

    @Test
    public void testIsoMeta() throws Exception {
        final IsoInterface isoMeta = IsoInterface.fromFile(stageMp4File(R.raw.test_video_xmp));
        final long[] hdlrRanges = isoMeta.getBoxRanges(IsoInterface.BOX_HDLR);

        // There are 3 hdlr boxes, the 3rd is inside the meta box. Check it was parsed correctly.
        assertThat(hdlrRanges.length).isEqualTo(3 * 2);
        assertThat(hdlrRanges[2 * 2 + 0]).isEqualTo(30145);
        assertThat(hdlrRanges[2 * 2 + 1]).isEqualTo(30170);
    }

    @Test
    public void testQtMeta() throws Exception {
        final IsoInterface qtMeta = IsoInterface.fromFile(stageMp4File(R.raw.testvideo_meta));
        final long[] hdlrRanges = qtMeta.getBoxRanges(IsoInterface.BOX_HDLR);

        // There are 3 hdlr boxes, the 1st is inside the meta box. Check it was parsed correctly.
        assertThat(hdlrRanges.length).isEqualTo(3 * 2);
        assertThat(hdlrRanges[2 * 0 + 0]).isEqualTo(16636);
        assertThat(hdlrRanges[2 * 0 + 1]).isEqualTo(16661);
    }

    private static File createFileWithLotsOfBoxes(String filename) throws Exception {
        File file = File.createTempFile(filename, ".mp4");
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] sizeHeader = new byte[]{0x00, 0x00, 0x00, 0x08};
            out.write(sizeHeader);
            out.write("ftyp".getBytes());
            byte[] freeBlock = "free".getBytes();
            for (int i = 0; i < 5000000; i++) {
                out.write(sizeHeader);
                out.write(freeBlock);
            }
        }
        return file;
    }
}
