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

package com.android.providers.media.photopicker.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Banner {@link ViewModel} to store and handle banner data for {@link PhotoPickerActivity}.
 */
public class BannerViewModel extends AndroidViewModel {
    private static final String TAG = "BannerViewModel";

    // Boolean Choose App Banner visibility
    private MutableLiveData<Boolean> mShowChooseAppBanner;

    public BannerViewModel(@NonNull Application application) {
        super(application);
    }

    /**
     * @return the {@link LiveData} of Choose App banner visibility {@link #mShowChooseAppBanner}.
     */
    @NonNull
    public LiveData<Boolean> shouldShowChooseAppBannerLiveData() {
        if (mShowChooseAppBanner == null) {
            // TODO(b/195009152): Update to hold and track the actual value.
            mShowChooseAppBanner = new MutableLiveData<>(false);
        }
        return mShowChooseAppBanner;
    }

    /**
     * Dismiss (hide) the 'Choose App' banner
     *
     * Set the {@link LiveData} value of Choose App banner visibility {@link #mShowChooseAppBanner}
     * as {@code false}.
     */
    public void onUserDismissedChooseAppBanner() {
        if (mShowChooseAppBanner == null) {
            Log.wtf(TAG, "Choose app banner live data is null on dismiss");
            return;
        }
        mShowChooseAppBanner.setValue(false);
    }
}
