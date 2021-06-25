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

import static com.android.providers.media.photopicker.ui.PhotosTabAdapter.COLUMN_COUNT;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * Photos tab fragment for showing the photos
 */
public class PhotosTabFragment extends TabFragment {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final PhotosTabAdapter adapter = new PhotosTabAdapter(mPickerViewModel, mImageLoader,
                this::onItemClick);

        mPickerViewModel.getItems().observe(this, itemList -> {
            adapter.updateItemList(itemList);
        });

        final GridLayoutManager layoutManager = new GridLayoutManager(getContext(), COLUMN_COUNT);
        final GridLayoutManager.SpanSizeLookup lookup = adapter.createSpanSizeLookup();
        if (lookup != null) {
            layoutManager.setSpanSizeLookup(lookup);
        }
        final PhotosTabItemDecoration itemDecoration = new PhotosTabItemDecoration(
                view.getContext());

        mRecyclerView.setLayoutManager(layoutManager);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);
    }

    private void onItemClick(@NonNull View view) {
        if (mPickerViewModel.canSelectMultiple()) {
            final boolean isSelectedBefore = view.isSelected();

            if (isSelectedBefore) {
                mPickerViewModel.deleteSelectedItem((Item) view.getTag());
            } else {
                mPickerViewModel.addSelectedItem((Item) view.getTag());
            }
            view.setSelected(!isSelectedBefore);
        } else {
            mPickerViewModel.clearSelectedItems();
            mPickerViewModel.addSelectedItem((Item) view.getTag());
            // Transition to PreviewFragment.
            PreviewFragment.show(getActivity().getSupportFragmentManager());
        }
    }
}