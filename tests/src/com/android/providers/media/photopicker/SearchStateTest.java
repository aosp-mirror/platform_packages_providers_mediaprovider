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

import static com.android.providers.media.photopicker.PickerSearchUtils.isHardwareSupportedForSearch;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

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

import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

// SetFlagsRule.ClassRule is not available in lower Android versions and Search feature will only
// be enabled for Android T+ devices.
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class SearchStateTest {
    @Mock
    private PickerSyncController mMockSyncController;
    private Context mContext;
    private TestConfigStore mConfigStore;


    @ClassRule
    public static final SetFlagsRule.ClassRule mSetFlagsClassRule = new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = mSetFlagsClassRule.createSetFlagsRule();

    @Before
    public void setup() {
        initMocks(this);

        Assume.assumeTrue(isHardwareSupportedForSearch());
        PickerSyncController.setInstance(mMockSyncController);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mConfigStore = new TestConfigStore();
        mConfigStore.setIsModernPickerEnabled(true);
    }

    @EnableFlags({
            Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES
    })
    @DisableFlags(Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH)
    @Test
    public void testSearchAPIFlagIsDisabled() {
        doReturn(SearchProvider.AUTHORITY)
                .when(mMockSyncController).getCloudProviderOrDefault(any());

        final SearchState searchState = new SearchState(mConfigStore);
        final boolean isCloudSearchEnabled = searchState.isCloudSearchEnabled(mContext);

        assertThat(isCloudSearchEnabled).isFalse();
    }

    @EnableFlags({
            Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES
    })
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    @Test
    public void testSearchFeatureFlagIsDisabled() {
        doReturn(SearchProvider.AUTHORITY)
                .when(mMockSyncController).getCloudProviderOrDefault(any());

        final SearchState searchState = new SearchState(mConfigStore);
        final boolean isCloudSearchEnabled = searchState.isCloudSearchEnabled(mContext);

        assertThat(isCloudSearchEnabled).isFalse();
    }

    @EnableFlags({
            Flags.FLAG_CLOUD_MEDIA_PROVIDER_SEARCH,
            Flags.FLAG_ENABLE_CLOUD_MEDIA_PROVIDER_CAPABILITIES,
            Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH
    })
    @Test
    public void testProviderCloudSearchIsEnabled() {
        doReturn(SearchProvider.AUTHORITY)
                .when(mMockSyncController).getCloudProviderOrDefault(any());

        final SearchState searchState = new SearchState(mConfigStore);
        assertThat(searchState.isCloudSearchEnabled(mContext)).isTrue();
        assertThat(searchState.isCloudSearchEnabled(mContext, SearchProvider.AUTHORITY)).isTrue();
        assertThat(searchState.isCloudSearchEnabled(mContext, "random")).isFalse();
    }
}
