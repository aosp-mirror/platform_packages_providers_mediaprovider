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
import static org.junit.Assert.assertTrue;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class LoggingTest {
    private File mTarget;

    @Before
    public void setUp() {
        mTarget = InstrumentationRegistry.getTargetContext().getCacheDir();
        FileUtils.deleteContents(mTarget);
        Logging.initPersistent(mTarget);
    }

    /**
     * Verify that a logged message makes it round-trip.
     */
    @Test
    public void testSimple() throws Exception {
        final String nonce = String.valueOf(System.nanoTime());
        Logging.logPersistent(nonce);

        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final PrintWriter pw = new PrintWriter(os, true);
        Logging.dumpPersistent(pw);

        assertTrue(os.toString().contains(nonce));
    }

    @Test
    public void testRotation() throws Exception {
        final char[] raw = new char[1024];
        Arrays.fill(raw, '!');
        final String msg = new String(raw);

        assertEquals(0, mTarget.listFiles().length);

        Logging.logPersistent(msg);
        assertEquals(1, mTarget.listFiles().length);

        for (int i = 0; i < 32; i++) {
            Logging.logPersistent(msg);
        }
        assertEquals(2, mTarget.listFiles().length);

        for (int i = 0; i < 32; i++) {
            Logging.logPersistent(msg);
        }
        assertEquals(3, mTarget.listFiles().length);
    }
}
