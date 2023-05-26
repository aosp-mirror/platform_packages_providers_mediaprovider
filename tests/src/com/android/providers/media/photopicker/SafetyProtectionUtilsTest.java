/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.Resources;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.util.SafetyProtectionUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SafetyProtectionUtilsTest {
    private final Context mContext = InstrumentationRegistry.getTargetContext();
    private static final String SAFETY_PROTECTION_RESOURCES_ENABLED = "safety_protection_enabled";
    private static final String TRUE_STRING = "true";
    private static final String FALSE_STRING = "false";
    private String mOriginalSafetyProtectionResourcesFlagStatus = "false";

    @Before
    public void setUp() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            mOriginalSafetyProtectionResourcesFlagStatus = DeviceConfig.getProperty(
                    DeviceConfig.NAMESPACE_PRIVACY, SAFETY_PROTECTION_RESOURCES_ENABLED);
        });
    }

    @After
    public void tearDown() {
        SystemUtil.runWithShellPermissionIdentity(() -> {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_PRIVACY,
                    SAFETY_PROTECTION_RESOURCES_ENABLED,
                    mOriginalSafetyProtectionResourcesFlagStatus, false);
        });
    }

    @Test
    public void testShouldNotUseSafetyProtectionResourcesWhenSOrBelow() {
        assumeFalse(SdkLevel.isAtLeastT());
        SystemUtil.runWithShellPermissionIdentity(() -> {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_PRIVACY,
                    SAFETY_PROTECTION_RESOURCES_ENABLED, TRUE_STRING, false);
            assertThat(SafetyProtectionUtils.shouldShowSafetyProtectionResources(mContext))
                    .isFalse();
        });
    }

    @Ignore("Enable once b/269874157 is fixed")
    @Test
    public void testWhetherShouldUseSafetyProtectionResourcesWhenTOrAboveAndFeatureFlagOn() {
        assumeTrue(SdkLevel.isAtLeastT());
        SystemUtil.runWithShellPermissionIdentity(() -> {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_PRIVACY,
                    SAFETY_PROTECTION_RESOURCES_ENABLED, TRUE_STRING, false);
            boolean resourceExists = false;
            try {
                resourceExists = mContext.getDrawable(
                        android.R.drawable.ic_safety_protection) != null;
            } catch (Resources.NotFoundException e) { }
            boolean shouldShowSafetyProtection = resourceExists
                    && isSafetyProtectionConfigEnabled();
            assertThat(SafetyProtectionUtils.shouldShowSafetyProtectionResources(mContext))
                    .isEqualTo(shouldShowSafetyProtection);
        });
    }

    @Test
    public void testWhetherShouldUseSafetyProtectionResourcesWhenTOrAboveAndFeatureFlagOff() {
        assumeTrue(SdkLevel.isAtLeastT());
        SystemUtil.runWithShellPermissionIdentity(() -> {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_PRIVACY,
                    SAFETY_PROTECTION_RESOURCES_ENABLED, FALSE_STRING, false);
            assertThat(SafetyProtectionUtils.shouldShowSafetyProtectionResources(mContext))
                    .isFalse();
        });
    }

    protected boolean isSafetyProtectionConfigEnabled() {
        try {
            return mContext.getResources().getBoolean(
                    Resources.getSystem()
                            .getIdentifier("config_safetyProtectionEnabled", "bool",
                            "android"));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }
}
