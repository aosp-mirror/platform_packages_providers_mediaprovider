/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.media.scan;

import static org.junit.Assert.assertNotNull;

import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class NullMediaScannerTest {
    @Test
    public void testSimple() throws Exception {
        final NullMediaScanner scanner = new NullMediaScanner(
                InstrumentationRegistry.getTargetContext());
        assertNotNull(scanner.getContext());

        scanner.scanDirectory(new File("/dev/null"), MediaScanner.REASON_UNKNOWN);
        scanner.scanFile(new File("/dev/null"), MediaScanner.REASON_UNKNOWN);
        scanner.scanFile(new File("/dev/null"), MediaScanner.REASON_UNKNOWN,
                    InstrumentationRegistry.getContext().getPackageName());

        scanner.onDetachVolume(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
}
