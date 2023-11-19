/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.espresso;

import static com.android.providers.media.photopicker.espresso.PhotoPickerBaseTest.MANAGED_SELECTION_ENABLED_EXTRA;

import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;

import androidx.annotation.NonNull;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.metrics.PhotoPickerUiEventLogger;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

public class PhotoPickerTestActivity extends PhotoPickerActivity {
    private final TestConfigStore mConfigStore = new TestConfigStore();
    private final UiEventLogger mLogger = mock(UiEventLogger.class, RETURNS_SMART_NULLS);
    private InstanceId mInstanceId;

    @Override
    @NonNull
    protected PickerViewModel getOrCreateViewModel() {
        final PickerViewModel pickerViewModel = super.getOrCreateViewModel();
        if (getIntent().getExtras() != null && getIntent().getExtras().getBoolean(
                MANAGED_SELECTION_ENABLED_EXTRA)) {
            mConfigStore.enablePickerChoiceManagedSelectionEnabled();
        }
        pickerViewModel.setConfigStore(mConfigStore);
        pickerViewModel.setItemsProvider(new ItemsProvider(
                PhotoPickerBaseTest.getIsolatedContext()));
        pickerViewModel.setUserIdManager(PhotoPickerBaseTest.getMockUserIdManager());
        pickerViewModel.setLogger(new PhotoPickerUiEventLogger(mLogger));
        mInstanceId = pickerViewModel.getInstanceId();
        return pickerViewModel;
    }

    TestConfigStore getConfigStore() {
        return mConfigStore;
    }

    UiEventLogger getLogger() {
        return mLogger;
    }

    InstanceId getInstanceId() {
        return mInstanceId;
    }
}
