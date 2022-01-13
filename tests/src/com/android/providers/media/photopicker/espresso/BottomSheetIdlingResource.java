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

import android.view.View;

import androidx.annotation.NonNull;
import androidx.test.espresso.IdlingResource;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class BottomSheetIdlingResource implements IdlingResource {
    private final BottomSheetBehavior<View> mBottomSheetBehavior;
    private ResourceCallback mResourceCallback;

    public BottomSheetIdlingResource(View bottomSheetView) {
        mBottomSheetBehavior = BottomSheetBehavior.from(bottomSheetView);
        mBottomSheetBehavior.addBottomSheetCallback(new IdleListener());
    }

    @Override
    public String getName() {
        return BottomSheetIdlingResource.class.getName();
    }

    @Override
    public boolean isIdleNow() {
        int state = mBottomSheetBehavior.getState();
        return state != BottomSheetBehavior.STATE_DRAGGING
                && state != BottomSheetBehavior.STATE_SETTLING;
    }

    @Override
    public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
        mResourceCallback = resourceCallback;
    }

    private final class IdleListener extends BottomSheetBehavior.BottomSheetCallback {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            if (mResourceCallback != null && isIdleNow()) {
                mResourceCallback.onTransitionToIdle();
            }
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
    }
}
