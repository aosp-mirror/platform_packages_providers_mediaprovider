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

import static com.android.providers.media.photopicker.ui.AlbumsTabAdapter.COLUMN_COUNT;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.GridLayoutManager;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.util.LayoutModeUtils;

/**
 * Albums tab fragment for showing the albums
 */
public class AlbumsTabFragment extends TabFragment {

    private static final int MINIMUM_SPAN_COUNT = 2;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Set the pane title for A11y.
        view.setAccessibilityPaneTitle(getString(R.string.picker_albums));

        setEmptyMessage(R.string.picker_albums_empty_message);

        final AlbumsTabAdapter adapter = new AlbumsTabAdapter(mImageLoader, this::onItemClick,
                mPickerViewModel.hasMimeTypeFilters());
        mPickerViewModel.getCategories().observe(this, categoryList -> {
            adapter.updateCategoryList(categoryList);
            // Handle emptyView's visibility
            updateVisibilityForEmptyView(/* shouldShowEmptyView */ categoryList.size() == 0);
        });
        final GridLayoutManager layoutManager = new GridLayoutManager(getContext(), COLUMN_COUNT);
        final AlbumsTabItemDecoration itemDecoration = new AlbumsTabItemDecoration(
                view.getContext());

        final int spacing = getResources().getDimensionPixelSize(R.dimen.picker_album_item_spacing);
        final int albumSize = getResources().getDimensionPixelSize(R.dimen.picker_album_size);
        mRecyclerView.setColumnWidth(albumSize + spacing);
        mRecyclerView.setMinimumSpanCount(MINIMUM_SPAN_COUNT);

        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);
    }

    @Override
    public void onResume() {
        super.onResume();
        ((PhotoPickerActivity) getActivity()).updateCommonLayouts(LayoutModeUtils.MODE_ALBUMS_TAB,
                /* title */ "");
    }

    private void onItemClick(@NonNull View view) {
        final Category category = (Category) view.getTag();
        PhotosTabFragment.show(getActivity().getSupportFragmentManager(), category);
    }

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