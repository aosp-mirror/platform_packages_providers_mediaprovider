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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.TestConfigStore;
import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.flags.Flags;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class CategoriesStateTest {

    private Context mContext;

    @ClassRule
    public static final SetFlagsRule.ClassRule mSetFlagsClassRule = new SetFlagsRule.ClassRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = mSetFlagsClassRule.createSetFlagsRule();

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES})
    public void testAreMediaCategoriesEnabledWithValidAuthority() {
        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsModernPickerEnabled(true);

        final CategoriesState categoriesState = new CategoriesState(configStore);
        assertTrue(categoriesState.areCategoriesEnabled(mContext, SearchProvider.AUTHORITY));
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES})
    public void testAreMediaCategoriesEnabledWithVInvalidAuthority() {
        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsModernPickerEnabled(true);

        final CategoriesState categoriesState = new CategoriesState(configStore);
        assertFalse(categoriesState.areCategoriesEnabled(mContext, "invalid"));
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES})
    public void testAreMediaCategoriesEnabledWithModernPickerDisabled() {
        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsModernPickerEnabled(false);

        final CategoriesState categoriesState = new CategoriesState(configStore);
        assertFalse(categoriesState.areCategoriesEnabled(mContext, SearchProvider.AUTHORITY));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    public void testAreMediaCategoriesEnabledWithPhotopickerSearchFlagDisabled() {
        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsModernPickerEnabled(true);

        final CategoriesState categoriesState = new CategoriesState(configStore);
        assertFalse(categoriesState.areCategoriesEnabled(mContext, "invalid"));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    @DisableFlags(Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES)
    public void testAreMediaCategoriesEnabledWithCapabilitiesFlagDisabled() {
        final TestConfigStore configStore = new TestConfigStore();
        configStore.setIsModernPickerEnabled(true);

        final CategoriesState categoriesState = new CategoriesState(configStore);
        assertFalse(categoriesState.areCategoriesEnabled(mContext, "invalid"));
    }
}
