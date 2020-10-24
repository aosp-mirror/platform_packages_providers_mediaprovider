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

package com.android.providers.media.transcode;

import static org.junit.Assert.assertEquals;

import android.os.Environment;
import android.system.Os;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class TranscodeTest {
    private static final File EXTERNAL_STORAGE_DIRECTORY
            = Environment.getExternalStorageDirectory();
    private static final File DIR_CAMERA
            = new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_DCIM + "/Camera");

    static final String NONCE = String.valueOf(System.nanoTime());
    private static final String HEVC_FILE_NAME = "TranscodeTestHEVC_" + NONCE + ".mp4";

    @Before
    public void setUp() throws Exception {
        TranscodeTestUtils.pollForExternalStorageState();
        TranscodeTestUtils.enableSeamlessTranscoding();
    }

    @After
    public void tearDown() throws Exception {
        TranscodeTestUtils.disableSeamlessTranscoding();
    }

    /**
     * Tests that we return FD of transcoded file for legacy apps
     * @throws Exception
     */
    @Test
    public void testTranscoded() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            long size = modernFile.length();

            FileInputStream fisOriginal = new FileInputStream(modernFile);

            TranscodeTestUtils.setLegacy(Os.getuid());

            FileInputStream fisTranscoded = new FileInputStream(modernFile);

            assertFileContent(fisOriginal, fisTranscoded, size, false);

            fisOriginal.close();
            fisTranscoded.close();
        } finally {
            TranscodeTestUtils.unsetLegacy();
            modernFile.delete();
        }
    }

    /**
     * Tests that same transcoded file is used for multiple open() from same app
     * @throws Exception
     */
    @Test
    public void testSameTranscodedFile() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            long size = modernFile.length();

            TranscodeTestUtils.setLegacy(Os.getuid());

            FileInputStream fisTranscoded1 = new FileInputStream(modernFile);
            FileInputStream fisTranscoded2 = new FileInputStream(modernFile);

            assertFileContent(fisTranscoded1, fisTranscoded2, size, true);

            fisTranscoded1.close();
            fisTranscoded2.close();
        } finally {
            TranscodeTestUtils.unsetLegacy();
            modernFile.delete();
        }
    }

    private void assertFileContent(FileInputStream fisOriginal, FileInputStream fisTranscoded,
            long fileSize, boolean assertSame) throws IOException {
        final int readBytesLen = 10;
        byte[] original = new byte[readBytesLen];
        byte[] transcoded = new byte[readBytesLen];
        long ind = 0;
        final int seekLen = 1024;
        boolean isSame = true;

        while (isSame && ind < fileSize) {
            assertEquals(readBytesLen, fisOriginal.read(original));
            assertEquals(readBytesLen, fisTranscoded.read(transcoded));

            isSame = Arrays.equals(original, transcoded);

            ind += seekLen;
            fisOriginal.skip(seekLen);
            fisTranscoded.skip(seekLen);
        }
        assertEquals(assertSame, isSame);
    }
}
