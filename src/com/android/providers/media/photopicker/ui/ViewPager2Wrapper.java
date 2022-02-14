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

package com.android.providers.media.photopicker.ui;

import android.content.Context;
import android.view.View;

import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * A wrapper class to assist in initializing {@link ViewPager2} and {@link PreviewAdapter}. This
 * class also supports some of {@link ViewPager2} and {@link PreviewAdapter} methods to avoid
 * exposing these objects outside this class.
 * The class also supports registering {@link ViewPager2.OnPageChangeCallback} and unregister the
 * same onDestroy().
 */
class ViewPager2Wrapper {
    private final ViewPager2 mViewPager;
    private final PreviewAdapter mAdapter;
    private final List<ViewPager2.OnPageChangeCallback> mOnPageChangeCallbacks = new ArrayList<>();

    ViewPager2Wrapper(ViewPager2 viewPager, List<Item> selectedItems, MuteStatus muteStatus) {
        mViewPager = viewPager;

        final Context context = mViewPager.getContext();

        mAdapter = new PreviewAdapter(context, muteStatus);
        mAdapter.updateItemList(selectedItems);
        mViewPager.setAdapter(mAdapter);

        CompositePageTransformer compositePageTransformer = new CompositePageTransformer();
        compositePageTransformer.addTransformer(new MarginPageTransformer(
                context.getResources().getDimensionPixelSize(R.dimen.preview_viewpager_margin)));
        compositePageTransformer.addTransformer(new PlayerPageTransformer());
        mViewPager.setPageTransformer(compositePageTransformer);
    }

    /**
     * Registers given {@link ViewPager2.OnPageChangeCallback} to the {@link ViewPager2}. This class
     * also takes care of unregistering the callback onDestroy()
     */
    public void addOnPageChangeCallback(ViewPager2.OnPageChangeCallback onPageChangeCallback) {
        mOnPageChangeCallbacks.add(onPageChangeCallback);
        mViewPager.registerOnPageChangeCallback(onPageChangeCallback);
    }

    public Item getItemAt(int position) {
        return getItemAtInternal(position);
    }

    public Item getCurrentItem() {
        return getItemAtInternal(mViewPager.getCurrentItem());
    }

    private Item getItemAtInternal(int position) {
        return mAdapter.getItem(position);
    }

    public void onStop() {
        mAdapter.onStop();
    }

    public void onStart() {
        // TODO(b/197083539): Restore the playback state here.
        // This forces PageTransformer#transformPage call and assists in ExoPlayer initialization.
        mViewPager.requestTransform();
    }

    public void onDestroy() {
        for (ViewPager2.OnPageChangeCallback callback : mOnPageChangeCallbacks) {
            mViewPager.unregisterOnPageChangeCallback(callback);
        }
        mOnPageChangeCallbacks.clear();
        mAdapter.onDestroy();
    }

    private class PlayerPageTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(View view, float position) {
            // We are only interested in position == 0.0. Only position=0.0 indicates that the page
            // is selected.
            if (position != 0) return;

            mAdapter.onHandlePageSelected(view);
        }
    }
}
