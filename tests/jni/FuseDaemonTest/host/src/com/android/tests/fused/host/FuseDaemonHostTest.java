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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs the FuseDaemon tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class FuseDaemonHostTest extends BaseHostJUnit4Test {
    /**
     * Runs the given phase of FilePathAccessTest by calling into the device.
     * Throws an exception if the test phase fails.
     */
    private void runDeviceTest(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.fused",
                "com.android.tests.fused.FilePathAccessTest",
                phase));
    }

    private String executeShellCommand(String cmd) throws Exception {
        return getDevice().executeShellCommand(cmd);
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

    @Test
    public void testListUnsupportedFileType() throws Exception {
        final ITestDevice device = getDevice();
        final boolean isAdbRoot = device.isAdbRoot() ? true : false;
        // Adb shell should run as 'root' for test to bypass some of FUSE & MediaProvider checks.
        if (!isAdbRoot) {
            device.enableAdbRoot();
        }
        runDeviceTest("testListUnsupportedFileType");
        if (!isAdbRoot) {
            device.disableAdbRoot();
        }
    }

    @Test
    public void testMetaDataRedaction() throws Exception {
        runDeviceTest("testMetaDataRedaction");
    }

    @Test
    public void testVfsCacheConsistency() throws Exception {
        runDeviceTest("testOpenFilePathFirstWriteContentResolver");
        runDeviceTest("testOpenContentResolverFirstWriteContentResolver");
        runDeviceTest("testOpenFilePathFirstWriteFilePath");
        runDeviceTest("testOpenContentResolverFirstWriteFilePath");
        runDeviceTest("testOpenContentResolverWriteOnly");
        runDeviceTest("testOpenContentResolverDup");
        runDeviceTest("testContentResolverDelete");
        runDeviceTest("testContentResolverUpdate");
    }

    @Test
    public void testRenameFile() throws Exception {
        runDeviceTest("testRenameFile");
    }

    @Test
    public void testRenameFileType() throws Exception {
        runDeviceTest("testRenameFileType");
    }

    @Test
    public void testRenameAndReplaceFile() throws Exception {
        runDeviceTest("testRenameAndReplaceFile");
    }

    @Test
    public void testRenameFileNotOwned() throws Exception {
        runDeviceTest("testRenameFileNotOwned");
    }

    @Test
    public void testRenameDirectory() throws Exception {
        runDeviceTest("testRenameDirectory");
    }

    @Test
    public void testRenameDirectoryNotOwned() throws Exception {
        runDeviceTest("testRenameDirectoryNotOwned");
    }

    @Test
    public void testRenameEmptyDirectory() throws Exception {
        runDeviceTest("testRenameEmptyDirectory");
    }

    @Test
    public void testManageExternalStorageBypassesMediaProviderRestrictions() throws Exception {
        runDeviceTest("testManageExternalStorageBypassesMediaProviderRestrictions");
    }
}
