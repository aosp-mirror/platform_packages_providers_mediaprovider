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

import androidx.test.espresso.IdlingResource;
import androidx.viewpager2.widget.ViewPager2;

/**
 * An {@link IdlingResource} waiting for the {@link ViewPager2} swipe to enter
 * {@link ViewPager2#SCROLL_STATE_IDLE} state.
 */
public class ViewPager2IdlingResource implements IdlingResource {
    private final ViewPager2 mViewPager;
    private ResourceCallback mResourceCallback;

    public ViewPager2IdlingResource(ViewPager2 viewPager) {
        mViewPager = viewPager;
        viewPager.registerOnPageChangeCallback(new IdleStateListener());
    }

    @Override
    public String getName() {
        return ViewPager2IdlingResource.class.getSimpleName();
    }

    @Override
    public boolean isIdleNow() {
        return mViewPager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE;
    }

    @Override
    public void registerIdleTransitionCallback(ResourceCallback callback) {
        mResourceCallback = callback;
    }

    private final class IdleStateListener extends ViewPager2.OnPageChangeCallback {
        @Override
        public void onPageScrollStateChanged(int state) {
            if (state == ViewPager2.SCROLL_STATE_IDLE && mResourceCallback != null) {
                mResourceCallback.onTransitionToIdle();
            }
        }
    }
}