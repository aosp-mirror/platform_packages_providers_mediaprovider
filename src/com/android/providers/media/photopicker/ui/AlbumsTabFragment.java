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
import com.android.providers.media.photopicker.data.model.Category;

/**
 * Albums tab fragment for showing the albums
 */
public class AlbumsTabFragment extends TabFragment {

    private int mBottomBarGap;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBottomBarGap = getResources().getDimensionPixelSize(R.dimen.picker_album_bottom_bar_gap);

        final AlbumsTabAdapter adapter = new AlbumsTabAdapter(mImageLoader, this::onItemClick,
                mPickerViewModel.hasMimeTypeFilter());
        mPickerViewModel.getCategories().observe(this, categoryList -> {
            adapter.updateCategoryList(categoryList);
        });
        final GridLayoutManager layoutManager = new GridLayoutManager(getContext(), COLUMN_COUNT);
        final AlbumsTabItemDecoration itemDecoration = new AlbumsTabItemDecoration(
                view.getContext());

        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle("");
    }

    private void onItemClick(@NonNull View view) {
        final Category category = (Category) view.getTag();
        PhotosTabFragment.show(getActivity().getSupportFragmentManager(), category);
    }

    @Override
    protected int getBottomGapForRecyclerView(int bottomBarSize) {
        return bottomBarSize + mBottomBarGap;
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