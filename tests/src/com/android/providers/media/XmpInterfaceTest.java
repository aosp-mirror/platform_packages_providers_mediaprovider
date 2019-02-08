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
import static org.junit.Assert.assertNull;

import android.content.Context;

import com.android.providers.media.tests.R;
import com.android.providers.media.util.XmpInterface;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

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
}
