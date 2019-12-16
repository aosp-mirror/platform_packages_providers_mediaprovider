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
import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs the legacy file path access tests.
 */
@Ignore("b/137890172: waiting for ag/9733193")
@RunWith(DeviceJUnit4ClassRunner.class)
public class LegacyAccessHostTest extends FuseDaemonBaseHostTest {

    public static final String SHELL_FILE = "/sdcard/LegacyAccessHostTest_shell";

    /**
     * Runs the given phase of LegacyFileAccessTest by calling into the device.
     * Throws an exception if the test phase fails.
     */
    private void runDeviceTest(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.fused.legacy",
                "com.android.tests.fused.legacy.LegacyFileAccessTest",
                phase));
    }

    /**
     * <p> Keep in mind that granting WRITE_EXTERNAL_STORAGE also grants READ_EXTERNAL_STORAGE,
     * so in order to test a case where the reader has only WRITE, we must explicitly revoke READ.
     */
    private void grantPermissions(String... perms) throws Exception {
        for (String perm : perms) {
            executeShellCommand("pm grant com.android.tests.fused.legacy " + perm);
        }
    }

    private void revokePermissions(String... perms) throws Exception {
        for (String perm : perms) {
            executeShellCommand("pm revoke com.android.tests.fused.legacy " + perm);
        }
    }

    private void createFile(String filePath) throws Exception {
        executeShellCommand("touch " + filePath);
        assertThat(getDevice().doesFileExist(filePath)).isTrue();
    }

    @Before
    public void setup() throws Exception {
        // Granting WRITE automatically grants READ as well, so we grant them both explicitly by
        // default in order to avoid confusion. Test cases that don't want any of those permissions
        // have to revoke the unwanted permissions.
        grantPermissions("android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE");
    }

    @After
    public void tearDown() throws Exception {
        revokePermissions("android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE");
    }

    @Test
    public void testCreateFilesInRandomPlaces_hasW() throws Exception {
        revokePermissions("android.permission.READ_EXTERNAL_STORAGE");
        runDeviceTest("testCreateFilesInRandomPlaces_hasW");
    }

    @Test
    public void testMkdirInRandomPlaces_hasW() throws Exception {
        revokePermissions("android.permission.READ_EXTERNAL_STORAGE");
        runDeviceTest("testMkdirInRandomPlaces_hasW");
    }

    @Test
    public void testReadOnlyExternalStorage_hasR() throws Exception {
        revokePermissions("android.permission.WRITE_EXTERNAL_STORAGE");
        createFile(SHELL_FILE);
        try {
            runDeviceTest("testReadOnlyExternalStorage_hasR");
        } finally {
            executeShellCommand("rm " + SHELL_FILE);
        }
    }

    @Test
    public void testCantAccessExternalStorage() throws Exception {
        revokePermissions("android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE");
        createFile(SHELL_FILE);
        try {
            runDeviceTest("testCantAccessExternalStorage");
        } finally {
            executeShellCommand("rm " + SHELL_FILE);
        }
    }
}
