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

import android.content.Context;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.sync.PickerSearchProviderClient;

/*
 Checks whether the CMP has implemented the categories feature or not. Exposes a function to check
 the same.
 */
public class CategoriesState {
    private final ConfigStore mConfigStore;

    public CategoriesState(ConfigStore configStore) {
        mConfigStore = configStore;
    }

    /**
     * Checks whether or not the given provider has MediaCategories enabled
     * @param context The app context
     * @param authority The provider authority
     * @return True if the provider has enabled media categories
     */
    public boolean areCategoriesEnabled(Context context, String authority) {
        PickerSearchProviderClient client = PickerSearchProviderClient.create(
                context, authority);

        return SdkLevel.isAtLeastT()
                && mConfigStore.isModernPickerEnabled()
                && Flags.enablePhotopickerSearch()
                && Flags.enableCloudMediaProviderCapabilities()
                && client.fetchCapabilities().isMediaCategoriesEnabled();
    }
}
