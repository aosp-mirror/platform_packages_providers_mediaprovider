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

package com.android.providers.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.media.ExifInterface;
import android.util.ArraySet;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.tests.R;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.IsoInterface;
import com.android.providers.media.util.XmpInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class XmpInterfaceTest {
    @Test
    public void testContainer_Empty() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.test_image)) {
            final XmpInterface xmp = XmpInterface.fromContainer(in);
            assertNull(xmp.getFormat());
            assertNull(xmp.getDocumentId());
            assertNull(xmp.getInstanceId());
            assertNull(xmp.getOriginalDocumentId());
        }
    }

    @Test
    public void testContainer_ValidAttrs() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_jpg)) {
            final XmpInterface xmp = XmpInterface.fromContainer(in);
            assertEquals("image/dng", xmp.getFormat());
            assertEquals("xmp.did:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getDocumentId());
            assertEquals("xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getInstanceId());
            assertEquals("3F9DD7A46B26513A7C35272F0D623A06", xmp.getOriginalDocumentId());
        }
    }

    @Test
    public void testContainer_ValidTags() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_dng)) {
            final XmpInterface xmp = XmpInterface.fromContainer(in);
            assertEquals("image/dng", xmp.getFormat());
            assertEquals("xmp.did:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getDocumentId());
            assertEquals("xmp.iid:041dfd42-0b46-4302-918a-836fba5016ed", xmp.getInstanceId());
            assertEquals("3F9DD7A46B26513A7C35272F0D623A06", xmp.getOriginalDocumentId());
        }
    }

    @Test
    public void testContainer_ExifRedactionRanges() throws Exception {
        final Set<String> redactionTags = new ArraySet<>();
        redactionTags.add(ExifInterface.TAG_GPS_LATITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_LONGITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_TIMESTAMP);
        redactionTags.add(ExifInterface.TAG_GPS_VERSION_ID);

        // The XMP contents start at byte 1809. These are the file offsets.
        final long[] expectedRanges = new long[]{2625,2675,2678,2730,2733,2792,2795,2841};
        final Context context = InstrumentationRegistry.getContext();
        try (InputStream in = context.getResources().openRawResource(R.raw.lg_g4_iso_800_jpg)) {
            ExifInterface exif = new ExifInterface(in);
            assertEquals(1809, exif.getAttributeRange(ExifInterface.TAG_XMP)[0]);
            final XmpInterface xmp = XmpInterface.fromContainer(exif, redactionTags);
            assertArrayEquals(expectedRanges, xmp.getRedactionRanges().toArray());
        }
    }

    @Test
    public void testContainer_IsoRedactionRanges() throws Exception {
        final Set<String> redactionTags = new ArraySet<>();
        redactionTags.add(ExifInterface.TAG_GPS_LATITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_LONGITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_TIMESTAMP);
        redactionTags.add(ExifInterface.TAG_GPS_VERSION_ID);

        final File file = stageFile(R.raw.test_video_xmp);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        final XmpInterface xmp = XmpInterface.fromContainer(mp4, redactionTags);

        // The XMP contents start at byte 30286. These are the file offsets.
        final long[] expectedRanges = new long[]{37299,37349,37352,37404,37407,37466,37469,37515};
        assertArrayEquals(expectedRanges, xmp.getRedactionRanges().toArray());
    }

    @Test
    public void testContainer_IsoRedactionRanges_BadTagValue() throws Exception {
        final Set<String> redactionTags = new ArraySet<>();
        redactionTags.add(ExifInterface.TAG_GPS_LATITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_LONGITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_TIMESTAMP);
        redactionTags.add(ExifInterface.TAG_GPS_VERSION_ID);

        // This file has some inner xml in the latitude tag. We should redact anyway.
        final File file = stageFile(R.raw.test_video_xmp_bad_tag);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        final XmpInterface xmp = XmpInterface.fromContainer(mp4, redactionTags);

        // The XMP contents start at byte 30286. These are the file offsets.
        final long[] expectedRanges = new long[]{37299,37349,37352,37404,37407,37466,37469,37515};
        assertArrayEquals(expectedRanges, xmp.getRedactionRanges().toArray());
    }

    @Test
    public void testContainer_IsoRedactionRanges_MalformedXml() throws Exception {
        final Set<String> redactionTags = new ArraySet<>();
        redactionTags.add(ExifInterface.TAG_GPS_LATITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_LONGITUDE);
        redactionTags.add(ExifInterface.TAG_GPS_TIMESTAMP);
        redactionTags.add(ExifInterface.TAG_GPS_VERSION_ID);

        // This file has malformed XML in the latitude tag. XML parsing will fail
        final File file = stageFile(R.raw.test_video_xmp_malformed);
        final IsoInterface mp4 = IsoInterface.fromFile(file);
        try {
            final XmpInterface xmp = XmpInterface.fromContainer(mp4, redactionTags);
            fail("Should throw IOException");
        } catch (IOException e) {}
    }

    @Test
    public void testStream_LineOffsets() throws Exception {
        final String xml =
                "<a:b xmlns:a='a' xmlns:c='c' c:d=''\n  c:f='g'>\n  <c:i>j</c:i>\n  </a:b>";
        final InputStream xmlStream = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        final XmpInterface.ByteCountingInputStream stream =
                new XmpInterface.ByteCountingInputStream(xmlStream);

        final long[] expectedElementOffsets = new long[]{46,54,61,70};
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());
        int type;
        int i = 0;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG || type == XmlPullParser.END_TAG) {
                assertEquals(expectedElementOffsets[i++], stream.getOffset(parser));
            }
        }
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
