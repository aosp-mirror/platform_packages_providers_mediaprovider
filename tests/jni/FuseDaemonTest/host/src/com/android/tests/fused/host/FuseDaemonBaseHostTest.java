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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Base class for host tests that run FuseDaemon tests.
 * The main utility of this class is that it toggles FUSE and restarts the device if it's not
 * already on, then restores the original settings after the test has finished.
 *
 * @see {@link #enableFuseIfNecessary()}
 * @see {@link #restoreFuseSettings()}
 */
@RunWith(DeviceJUnit4ClassRunner.class)
abstract public class FuseDaemonBaseHostTest extends BaseHostJUnit4Test {
    private static final String PROP_FUSE_SNAPSHOT = "sys.fuse_snapshot";
    private static final String PROP_FUSE = "persist.sys.fflag.override.settings_fuse";
    private static ITestDevice sDevice = null;
    // Flag to determine whether FUSE was initially enabled, so we know what state to restore the
    // device to when we're done. 0 means we haven't checked yet, 1 is enabled and -1 is disabled.
    private static int sFuseInitialState = 0;

    private boolean isFuseEnabled() throws Exception {
        String enabled = getDevice().getProperty(PROP_FUSE_SNAPSHOT);
        return enabled.contains("true") || enabled.contains("1");
    }

    /* {@link #sDevice} must be initialized before calling this method */
    private static void toggleFuse(boolean enable) throws Exception {
        final String strEnable = enable ? "true" : "false";
        if (strEnable.equals(sDevice.getProperty(PROP_FUSE))) {
            return;
        }
        // root settings will be reset once device has rebooted
        sDevice.enableAdbRoot();
        sDevice.setProperty(PROP_FUSE, (enable ? "true" : "false"));
        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        sDevice.reboot();
    }

    /**
     * Snapshots {@link #sFuseInitialState} only once - if called while {@link #sFuseInitialState}
     * is not {@code 0}, it will be a no-op.
     */
    private static void snapshotFuseInitialState(boolean enabled) {
        if (sFuseInitialState != 0) {
            return;
        }
        sFuseInitialState = enabled ? 1 : -1;
    }

    private static boolean getFuseInitialState() {
        return sFuseInitialState == 1;
    }

    private void enableFuseAndRebootDeviceIfNecessary() throws Exception {
        if (isFuseEnabled()) {
            snapshotFuseInitialState(true);
            return;
        }
        snapshotFuseInitialState(false);
        sDevice = getDevice();
        toggleFuse(true);
    }

    @Before
    public void enableFuseIfNecessary() throws Exception {
        enableFuseAndRebootDeviceIfNecessary();
    }

    @AfterClass
    public static void restoreFuseSettings() throws Exception {
        if (sDevice != null) {
            toggleFuse(getFuseInitialState());
        }
    }

    protected String executeShellCommand(String cmd) throws Exception {
        return getDevice().executeShellCommand(cmd);
    }
}