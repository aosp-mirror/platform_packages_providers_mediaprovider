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

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.tests.R;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.IsoInterface;
import com.android.providers.media.util.XmpInterface;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

@RunWith(AndroidJUnit4.class)
public class IsoInterfaceTest {
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
