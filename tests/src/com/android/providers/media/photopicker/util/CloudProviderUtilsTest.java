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

package com.android.providers.media.photopicker.util;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.InstrumentationRegistry;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.cloudproviders.CloudProviderPrimary;
import com.android.providers.media.cloudproviders.CloudProviderSecondary;
import com.android.providers.media.cloudproviders.FlakyCloudProvider;
import com.android.providers.media.photopicker.data.CloudProviderInfo;

import org.junit.Test;

import java.util.List;
import java.util.Set;


public class CloudProviderUtilsTest {

    @Test
    public void getAllAvailableCloudProvidersTest() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext =
                new IsolatedContext(context, "CloudProviderUtilsTest", /*asFuseThread*/ false);
        final Set<String> testCloudProviders = Set.of(
                FlakyCloudProvider.AUTHORITY,
                CloudProviderPrimary.AUTHORITY,
                CloudProviderSecondary.AUTHORITY);
        final TestConfigStore configStore = new TestConfigStore();
        configStore.enableCloudMediaFeatureAndSetAllowedCloudProviderPackages(
                testCloudProviders.toArray(new String[0]));

        List<CloudProviderInfo> availableProviders =
                CloudProviderUtils.getAllAvailableCloudProviders(isolatedContext, configStore);

        assertThat(availableProviders.size()).isEqualTo(testCloudProviders.size());
        for (CloudProviderInfo info : availableProviders) {
            assertThat(testCloudProviders.contains(info.authority)).isTrue();
        }
    }
}
