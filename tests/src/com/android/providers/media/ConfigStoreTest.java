/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Verifies ConfigStore default values.
 */
@RunWith(AndroidJUnit4.class)
public class ConfigStoreTest {
    ConfigStore mConfigStore = new ConfigStore() {
        @NonNull
        @Override
        public List<String> getTranscodeCompatManifest() {
            return null;
        }

        @NonNull
        @Override
        public List<String> getTranscodeCompatStale() {
            return null;
        }

        @Override
        public void addOnChangeListener(@NonNull Executor executor,
                @NonNull Runnable listener) {
        }
    };

    @Test
    public void test_defaultValueConfigStore_allCorrect() {
        assertTrue(mConfigStore.getAllowedCloudProviderPackages().isEmpty());
        assertNull(mConfigStore.getDefaultCloudProviderPackage());
        assertEquals(60000, mConfigStore.getTranscodeMaxDurationMs());
        assertTrue(mConfigStore.isCloudMediaInPhotoPickerEnabled());
        assertFalse(mConfigStore.isGetContentTakeOverEnabled());
        assertTrue(mConfigStore.isPickerChoiceManagedSelectionEnabled());
        assertFalse(mConfigStore.isStableUrisForExternalVolumeEnabled());
        assertFalse(mConfigStore.isStableUrisForInternalVolumeEnabled());
        assertTrue(mConfigStore.isTranscodeEnabled());
        assertTrue(mConfigStore.isUserSelectForAppEnabled());
        assertTrue(mConfigStore.shouldEnforceCloudProviderAllowlist());
        assertTrue(mConfigStore.shouldPickerPreloadForGetContent());
        assertTrue(mConfigStore.shouldPickerPreloadForPickImages());
        assertFalse(mConfigStore.shouldPickerRespectPreloadArgumentForPickImages());
        assertFalse(mConfigStore.shouldTranscodeDefault());
    }
}
