/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.test.uiautomator.UiDevice;

import com.android.providers.media.GetResultActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

import java.io.IOException;

public class UiBaseTest {
    private static final String TAG = UiBaseTest.class.getSimpleName();

    protected static final String sTargetPackageName =
            getInstrumentation().getTargetContext().getPackageName();
    protected static final UiDevice sDevice = UiDevice.getInstance(getInstrumentation());

    protected GetResultActivity mActivity;

    // Do not use org.junit.BeforeClass (b/260380362) or
    // com.android.bedstead.harrier.annotations.BeforeClass (b/246986339#comment18)
    // when using DeviceState. Some subclasses of UiBaseTest may use DeviceState so avoid
    // adding either @BeforeClass methods here.

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(isHardwareSupported());

        disableDeviceConfigSync();

        final Context context = getInstrumentation().getContext();
        final Intent intent = new Intent(context, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        // Wake up the device and dismiss the keyguard before the test starts
        sDevice.executeShellCommand(/* cmd= */ "input keyevent KEYCODE_WAKEUP");
        sDevice.executeShellCommand(/* cmd= */ "wm dismiss-keyguard");

        mActivity = (GetResultActivity) getInstrumentation().startActivitySync(intent);
        // Wait for the UI Thread to become idle.
        getInstrumentation().waitForIdleSync();
        mActivity.clearResult();
        sDevice.waitForIdle();
    }

    @After
    public void tearDown() throws Exception {
        if (mActivity != null) {
            mActivity.finish();
        }
    }

    private static boolean isHardwareSupported() {
        // These UI tests are not optimised for Watches, TVs, Auto;
        // IoT devices do not have a UI to run these UI tests
        final PackageManager pm = getInstrumentation().getContext().getPackageManager();
        return !pm.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)
                && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && !pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Device config is reset from the server periodically. When tests override device config, it is
     * not a sticky change. The device config may be reset to server values at any point - even
     * while a test is running. In order to prevent unwanted device config resets, this method
     * disables device config syncs until device reboot.
     */
    private static void disableDeviceConfigSync() {
        try {
            sDevice.executeShellCommand(
                    /* cmd= */ "cmd device_config set_sync_disabled_for_tests until_reboot");
        } catch (IOException e) {
            Log.e(TAG, "Could not disable device_config sync. "
                    + "Device config may reset to server values at any point during test runs.", e);
        }
    }
}
