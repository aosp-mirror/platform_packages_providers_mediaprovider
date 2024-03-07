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

import android.content.Context;
import android.media.ExifInterface;
import android.util.Xml;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.R;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class XmpDataParserTest {
    @Test
    public void testContainer_Empty() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.test_image)) {
            final ExifInterface exifInterface = new ExifInterface(in);
            final XmpInterface xmp = XmpDataParser.createXmpInterface(exifInterface);
            assertThat(xmp.getFormat()).isNull();
            assertThat(xmp.getDocumentId()).isNull();
            assertThat(xmp.getInstanceId()).isNull();
            assertThat(xmp.getOriginalDocumentId()).isNull();
        }
    }

    @Test
    public void testContainer_ValidAttrs() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_jpg)) {
            final ExifInterface exifInterface = new ExifInterface(in);
            final XmpInterface xmp = XmpDataParser.createXmpInterface(exifInterface);
            assertThat(xmp.getFormat()).isEqualTo("image/dng");
            assertThat(xmp.getDocumentId()).isEqualTo(
                    "xmp.did:041dfd42-0b46-4302-918a-836fba5016ed");
            assertThat(xmp.getInstanceId()).isEqualTo(
                    "xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed");
            assertThat(xmp.getOriginalDocumentId()).isEqualTo("3F9DD7A46B26513A7C35272F0D623A06");
        }
    }

    @Test
    public void testContainer_ValidTags() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_dng)) {
            final ExifInterface exifInterface = new ExifInterface(in);
            final XmpInterface xmp = XmpDataParser.createXmpInterface(exifInterface);
            assertThat(xmp.getFormat()).isEqualTo("image/dng");
            assertThat(xmp.getDocumentId()).isEqualTo(
                    "xmp.did:041dfd42-0b46-4302-918a-836fba5016ed");
            assertThat(xmp.getInstanceId()).isEqualTo(
                    "xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed");
            assertThat(xmp.getOriginalDocumentId()).isEqualTo("3F9DD7A46B26513A7C35272F0D623A06");
        }
    }

    @Test
    public void testContainer_ExifRedactionRanges() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_jpg)) {
            ExifInterface exif = new ExifInterface(in);
            assertThat(exif.getAttributeRange(ExifInterface.TAG_XMP)[0]).isEqualTo(1809);

            // Confirm redact range within entire file
            // The XMP contents start at byte 1809. These are the file offsets.
            final long[] expectedRanges =
                    new long[]{2625, 2675, 2678, 2730, 2733, 2792, 2795, 2841};
            assertThat(XmpDataParser.getRedactionRanges(exif).toArray()).isEqualTo(expectedRanges);

            // Confirm redact range within local copy
            final XmpInterface xmp = XmpDataParser.createXmpInterface(exif);
            final String redactedXmp = new String(xmp.getRedactedXmp());
            assertThat(redactedXmp).doesNotContain("exif:GPSLatitude");
            assertThat(redactedXmp).doesNotContain("exif:GPSLongitude");
            assertThat(redactedXmp).contains("exif:ShutterSpeedValue");
        }
    }

    @Test
    public void testContainer_IsoRedactionRanges() throws Exception {
        final File file = stageMp4File(R.raw.test_video_xmp);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        // Confirm redact range within entire file
        // The XMP contents start at byte 30286. These are the file offsets.
        final long[] expectedRanges =
                new long[]{37299, 37349, 37352, 37404, 37407, 37466, 37469, 37515};
        assertThat(XmpDataParser.getRedactionRanges(mp4).toArray()).isEqualTo(expectedRanges);

        XmpInterface xmpInterface = XmpDataParser.createXmpInterface(mp4);
        // Confirm redact range within local copy
        final String redactedXmp = new String(xmpInterface.getRedactedXmp());
        assertThat(redactedXmp).doesNotContain("exif:GPSLatitude");
        assertThat(redactedXmp).doesNotContain("exif:GPSLongitude");
        assertThat(redactedXmp).contains("exif:ShutterSpeedValue");
    }

    @Test
    public void testContainer_IsoRedactionRanges_BadTagValue() throws Exception {
        // This file has some inner xml in the latitude tag. We should redact anyway.
        final File file = stageMp4File(R.raw.test_video_xmp_bad_tag);
        final IsoInterface mp4 = IsoInterface.fromFile(file);

        // The XMP contents start at byte 30286. These are the file offsets.
        final long[] expectedRanges =
                new long[]{37299, 37349, 37352, 37404, 37407, 37466, 37469, 37515};
        assertThat(XmpDataParser.getRedactionRanges(mp4).toArray()).isEqualTo(expectedRanges);
    }

    @Test
    public void testContainer_IsoRedactionRanges_MalformedXml() throws Exception {
        // This file has malformed XML in the latitude tag. XML parsing will fail
        final File file = stageMp4File(R.raw.test_video_xmp_malformed);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        assertThrows(IOException.class, () -> XmpDataParser.createXmpInterface(mp4));
    }

    @Test
    public void testContainer_ExifRedactionRanges_MalformedXml() throws Exception {
        // This file has malformed XML in the latitude tag. XML parsing will fail
        final File file = stageMp4File(R.raw.test_image_xmp_malformed);
        final ExifInterface image = new ExifInterface(file);
        assertThrows(IOException.class, () -> XmpDataParser.createXmpInterface(image));
    }

    @Test
    public void testRedaction_IsoRedactionRanges_MalformedXml() throws Exception {
        final File file = stageMp4File(R.raw.test_video_xmp_malformed);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        final long[] expectedRanges = new long[]{30286, 43483};
        final long[] actualRanges = XmpDataParser.getRedactionRanges(mp4).toArray();
        assertThat(actualRanges).isEqualTo(expectedRanges);
    }

    @Test
    public void testRedaction_ExifRedactionRanges_MalformedXml() throws Exception {
        final File file = stageMp4File(R.raw.test_image_xmp_malformed);
        ExifInterface exif = new ExifInterface(file);
        final long[] expectedRanges = new long[]{289, 626};
        final long[] actualRanges = XmpDataParser.getRedactionRanges(exif).toArray();
        assertThat(actualRanges).isEqualTo(expectedRanges);
    }

    @Test
    public void testStream_LineOffsets() throws Exception {
        final String xml =
                "<a:b xmlns:a='a' xmlns:c='c' c:d=''\n  c:f='g'>\n  <c:i>j</c:i>\n  </a:b>";
        final InputStream xmlStream = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8));
        final XmpDataParser.ByteCountingInputStream stream =
                new XmpDataParser.ByteCountingInputStream(xmlStream);

        final long[] expectedElementOffsets = new long[]{46, 54, 61, 70};
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());
        int type;
        int i = 0;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG || type == XmlPullParser.END_TAG) {
                assertThat(stream.getOffset(parser)).isEqualTo(expectedElementOffsets[i++]);
            }
        }
    }

    /**
     * Exercise some random methods for code coverage purposes.
     */
    @Test
    public void testStream_Misc() throws Exception {
        final InputStream xmlStream = new ByteArrayInputStream(
                "abcdefghijklmnoprstuvwxyz".getBytes(StandardCharsets.UTF_8));
        final XmpDataParser.ByteCountingInputStream stream =
                new XmpDataParser.ByteCountingInputStream(xmlStream);

        {
            final byte[] buf = new byte[4];
            stream.read(buf);
            assertThat(buf).isEqualTo("abcd".getBytes(StandardCharsets.UTF_8));
        }
        {
            final byte[] buf = new byte[4];
            stream.read(buf, 0, buf.length);
            assertThat(buf).isEqualTo("efgh".getBytes(StandardCharsets.UTF_8));
        }
        {
            assertThat(stream.skip(4)).isEqualTo(4);
            assertThat(stream.read()).isEqualTo('m');
        }

        assertThat(stream.toString()).isNotNull();
        stream.close();
    }
}
