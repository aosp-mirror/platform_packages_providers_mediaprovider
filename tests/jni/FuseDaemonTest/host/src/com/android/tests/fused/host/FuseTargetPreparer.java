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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.targetprep.BaseTargetPreparer;

public class FuseTargetPreparer extends BaseTargetPreparer {
    private static final String PROP_FUSE = "persist.sys.fuse";
    private static final String PROP_SETTINGS_FUSE = "persist.sys.fflag.override.settings_fuse";
    private static boolean sFuseInitialState = false;

    private static boolean isFuseEnabled(ITestDevice device) throws DeviceNotAvailableException {
        String enabled = device.getProperty(PROP_FUSE);
        if (enabled == null) {
            return false; // current default value of PROP_FUSE
        }
        return enabled.contains("true") || enabled.contains("1");
    }

    private static void toggleFuse(boolean enable, ITestDevice device)
            throws DeviceNotAvailableException {
        final String strEnable = Boolean.toString(enable);
        if (strEnable.equals(device.getProperty(PROP_SETTINGS_FUSE))) {
            return;
        }
        // root settings will be reset once device has rebooted
        device.enableAdbRoot();
        device.setProperty(PROP_SETTINGS_FUSE, strEnable);
        device.reboot();
    }

    private static void enableFuseAndRebootDeviceIfNecessary(ITestDevice device)
            throws DeviceNotAvailableException {
        sFuseInitialState = isFuseEnabled(device);
        if (sFuseInitialState) {
            // Nothing to do if it's enabled
            return;
        }
        toggleFuse(true, device);
    }

    public static void restoreFuseSettings(ITestDevice device) throws DeviceNotAvailableException {
        toggleFuse(sFuseInitialState, device);
    }

    @Override
    public void setUp(TestInformation testInformation) throws DeviceNotAvailableException {
        enableFuseAndRebootDeviceIfNecessary(testInformation.getDevice());
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        restoreFuseSettings(testInformation.getDevice());
    }
}
