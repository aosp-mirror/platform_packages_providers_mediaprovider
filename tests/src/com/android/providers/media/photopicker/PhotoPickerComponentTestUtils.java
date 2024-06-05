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

package com.android.providers.media.photopicker;

import static android.provider.MediaStore.ACTION_PICK_IMAGES;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Utility methods for enabling / disabling the {@link PackageManager} components for tests.
 */
public class PhotoPickerComponentTestUtils {
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long POLLING_SLEEP_MILLIS = 100;

    public static final ComponentName PICKER_SETTINGS_ACTIVITY_COMPONENT = new ComponentName(
            getPhotoPickerPackageName(),
            "com.android.providers.media.photopicker.PhotoPickerSettingsActivity");

    /**
     * Returns the current state of the given component and enables it.
     */
    public static int getCurrentStateAndEnable(@NonNull ComponentName componentName)
            throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final PackageManager packageManager = inst.getContext().getPackageManager();
        final int currentState = packageManager.getComponentEnabledSetting(componentName);

        updateComponentEnabledSetting(packageManager, componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        return currentState;
    }

    /**
     * Sets state of the given component to the given state.
     */
    public static void setState(@NonNull ComponentName componentName, int oldState)
            throws Exception {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        updateComponentEnabledSetting(inst.getContext().getPackageManager(),
                componentName, oldState);
    }

    private static void updateComponentEnabledSetting(@NonNull PackageManager packageManager,
            @NonNull ComponentName componentName, int state) throws Exception {
        // Return if the expected state is already set
        if (isComponentEnabledSetAsExpected(packageManager, componentName, state)) {
            return;
        }

        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        inst.getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        try {
            packageManager.setComponentEnabledSetting(componentName, state,
                    PackageManager.DONT_KILL_APP);
        } finally {
            inst.getUiAutomation().dropShellPermissionIdentity();
        }

        waitForComponentToBeInExpectedState(packageManager, componentName, state);
    }

    private static void waitForComponentToBeInExpectedState(@NonNull PackageManager packageManager,
            @NonNull ComponentName componentName, int state) throws Exception {
        pollForCondition(
                () -> isComponentEnabledSetAsExpected(packageManager, componentName, state),
                /* errorMessage= */ "Timed out while waiting for component to be enabled");
    }

    private static void pollForCondition(@NonNull Supplier<Boolean> condition,
            @NonNull String errorMessage) throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isComponentEnabledSetAsExpected(@NonNull PackageManager packageManager,
            @NonNull ComponentName componentName, int state) {
        return packageManager.getComponentEnabledSetting(componentName) == state;
    }

    @NonNull
    private static String getPhotoPickerPackageName() {
        return getActivityPackageNameFromIntent(new Intent(ACTION_PICK_IMAGES));
    }

    @NonNull
    private static String getActivityPackageNameFromIntent(@NonNull Intent intent) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final ResolveInfo resolveInfo =
                instrumentation.getContext().getPackageManager().resolveActivity(intent, 0);
        return resolveInfo.activityInfo.packageName;
    }
}
