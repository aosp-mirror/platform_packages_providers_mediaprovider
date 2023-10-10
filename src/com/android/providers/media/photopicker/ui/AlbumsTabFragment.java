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
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.util.LayoutModeUtils;

/**
 * Albums tab fragment for showing the albums
 */
public class AlbumsTabFragment extends TabFragment {

    private static final int MINIMUM_SPAN_COUNT = 2;
    private static final int GRID_COLUMN_COUNT = 2;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Context context = getContext();

        // Set the pane title for A11y.
        view.setAccessibilityPaneTitle(getString(R.string.picker_albums));

        setEmptyMessage(R.string.picker_albums_empty_message);

        final AlbumsTabAdapter adapter = new AlbumsTabAdapter(mImageLoader, mOnAlbumClickListener,
                mPickerViewModel.hasMimeTypeFilters(), /* lifecycleOwner */ this,
                mPickerViewModel.getCloudMediaProviderAppTitleLiveData(),
                mPickerViewModel.getCloudMediaAccountNameLiveData(),
                mPickerViewModel.shouldShowChooseAppBannerLiveData(),
                mPickerViewModel.shouldShowCloudMediaAvailableBannerLiveData(),
                mPickerViewModel.shouldShowAccountUpdatedBannerLiveData(),
                mPickerViewModel.shouldShowChooseAccountBannerLiveData(),
                mOnChooseAppBannerEventListener, mOnCloudMediaAvailableBannerEventListener,
                mOnAccountUpdatedBannerEventListener, mOnChooseAccountBannerEventListener);
        mPickerViewModel.getCategories().observe(this, categoryList -> {
            adapter.updateCategoryList(categoryList);
            // Handle emptyView's visibility
            updateVisibilityForEmptyView(/* shouldShowEmptyView */ categoryList.size() == 0);
        });

        final AlbumsTabItemDecoration itemDecoration = new AlbumsTabItemDecoration(context);

        final int spacing = getResources().getDimensionPixelSize(R.dimen.picker_album_item_spacing);
        final int albumSize = getResources().getDimensionPixelSize(R.dimen.picker_album_size);
        mRecyclerView.setColumnWidth(albumSize + spacing);
        mRecyclerView.setMinimumSpanCount(MINIMUM_SPAN_COUNT);

        setLayoutManager(adapter, GRID_COLUMN_COUNT);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPickerActivity().updateCommonLayouts(LayoutModeUtils.MODE_ALBUMS_TAB, /* title */ "");
    }

    private final AlbumsTabAdapter.OnAlbumClickListener mOnAlbumClickListener = category ->
        PhotosTabFragment.show(getActivity().getSupportFragmentManager(), category);

    /**
     * Create the albums tab fragment and add it into the FragmentManager
     *
     * @param fm The fragment manager
     */
    public static void show(FragmentManager fm) {
        final FragmentTransaction ft = fm.beginTransaction();
        final AlbumsTabFragment fragment = new AlbumsTabFragment();
        ft.replace(R.id.fragment_container, fragment);
        ft.commitAllowingStateLoss();
    }
}