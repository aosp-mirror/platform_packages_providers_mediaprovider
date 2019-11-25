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

package com.android.tests.fused.host;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Runs the FuseDaemon tests.
 */
@Ignore("b/137890172: waiting for ag/9733193")
@RunWith(DeviceJUnit4ClassRunner.class)
public class FuseDaemonHostTest extends FuseDaemonBaseHostTest {
    /**
     * Runs the given phase of FilePathAccessTest by calling into the device.
     * Throws an exception if the test phase fails.
     */
    private void runDeviceTest(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.fused",
                "com.android.tests.fused.FilePathAccessTest",
                phase));
    }

    @Before
    public void setup() throws Exception {
        executeShellCommand("mkdir /sdcard/Android/data/com.android.shell");
        executeShellCommand("mkdir /sdcard/Android/data/com.android.shell/files");
    }

    @After
    public void tearDown() throws Exception {
        executeShellCommand("rm -r /sdcard/Android/data/com.android.shell");
        executeShellCommand("rm -r /sdcard/Android/data/com.android.shell/files");
    }

    @Test
    public void testTypePathConformity() throws Exception {
        runDeviceTest("testTypePathConformity");
    }

    @Test
    public void testCreateFileInAppExternalDir() throws Exception {
        runDeviceTest("testCreateFileInAppExternalDir");
    }

    @Test
    public void testCreateFileInOtherAppExternalDir() throws Exception {
        runDeviceTest("testCreateFileInOtherAppExternalDir");
    }

    @Test
    public void testContributeMediaFile() throws Exception {
        runDeviceTest("testContributeMediaFile");
    }

    @Test
    public void testCreateAndDeleteEmptyDir() throws Exception {
        runDeviceTest("testCreateAndDeleteEmptyDir");
    }

    @Test
    public void testDeleteNonemptyDir() throws Exception {
        runDeviceTest("testDeleteNonemptyDir");
    }

    @Test
    public void testOpendirRestrictions() throws Exception {
        runDeviceTest("testOpendirRestrictions");
    }

    @Test
    public void testLowLevelFileIO() throws Exception {
        runDeviceTest("testLowLevelFileIO");
    }

    @Test
    public void testListDirectoriesWithMediaFiles() throws Exception {
        runDeviceTest("testListDirectoriesWithMediaFiles");
    }

    @Test
    public void testListDirectoriesWithNonMediaFiles() throws Exception {
        runDeviceTest("testListDirectoriesWithNonMediaFiles");
    }

    @Test
    public void testListFilesFromExternalFilesDirectory() throws Exception {
        runDeviceTest("testListFilesFromExternalFilesDirectory");
    }

    @Test
    public void testListFilesFromExternalMediaDirectory() throws Exception {
        runDeviceTest("testListFilesFromExternalMediaDirectory");
    }
}