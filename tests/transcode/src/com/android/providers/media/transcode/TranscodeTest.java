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

import static com.android.providers.media.transcode.TranscodeTestUtils.assertFileContent;
import static com.android.providers.media.transcode.TranscodeTestUtils.assertTranscode;
import static com.android.providers.media.transcode.TranscodeTestUtils.open;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

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
        TranscodeTestUtils.grantPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
        TranscodeTestUtils.pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, true);
        TranscodeTestUtils.enableSeamlessTranscoding();
        TranscodeTestUtils.disableTranscodingForAllUids();
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

            ParcelFileDescriptor pfdOriginal = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());
            ParcelFileDescriptor pfdTranscoded = open(modernFile, false);

            assertFileContent(pfdOriginal, pfdTranscoded, false);
        } finally {
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

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());
            ParcelFileDescriptor pfdTranscoded1 = open(modernFile, false);
            ParcelFileDescriptor pfdTranscoded2 = open(modernFile, false);

            assertFileContent(pfdTranscoded1, pfdTranscoded2, true);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that deletes are visible across legacy and modern apps
     * @throws Exception
     */
    @Test
    public void testDeleteTranscodedFile() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTrue(modernFile.delete());
            assertFalse(modernFile.exists());

            TranscodeTestUtils.disableTranscodingForAllUids();

            assertFalse(modernFile.exists());
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that renames are visible across legacy and modern apps
     * @throws Exception
     */
    @Test
    public void testRenameTranscodedFile() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        File destFile = new File(DIR_CAMERA, "renamed_" + HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTrue(modernFile.renameTo(destFile));
            assertTrue(destFile.exists());
            assertFalse(modernFile.exists());

            TranscodeTestUtils.disableTranscodingForAllUids();

            assertTrue(destFile.exists());
            assertFalse(modernFile.exists());
        } finally {
            modernFile.delete();
            destFile.delete();
        }
    }

    /**
     * Tests that transcode doesn't start until read(2)
     * @throws Exception
     */
    @Test
    public void testLazyTranscodedFile() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            assertTranscode(modernFile, false);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after rename
     * @throws Exception
     */
    @Test
    public void testTranscodedCacheReuseAfterRename() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        File destFile = new File(DIR_CAMERA, "renamed_" + HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);

            assertTrue(modernFile.renameTo(destFile));

            assertTranscode(destFile, false);
        } finally {
            modernFile.delete();
            destFile.delete();
        }
    }
}
