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

package com.android.providers.media.photopicker.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Adapter for {@link TabContainerFragment}'s ViewPager2 to show {@link PhotosTabFragment} and
 * {@link AlbumsTabFragment}.
 */
public class TabContainerAdapter extends FragmentStateAdapter {
    private final static int TAB_COUNT = 2;

    public TabContainerAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }

    @NonNull
    @Override
    public Fragment createFragment(int pos) {
        if (pos == 0) {
            return new PhotosTabFragment();
        }
        return new AlbumsTabFragment();
    }
}
