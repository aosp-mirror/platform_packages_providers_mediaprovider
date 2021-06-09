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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

/**
 * Photos tab fragment for showing the photos
 */
public class PhotosTabFragment extends Fragment {

    private PickerViewModel mPickerViewModel;
    private ImageLoader mImageLoader;

    @Override
    @NonNull
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_photos_tab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mImageLoader = new ImageLoader(getContext());
        RecyclerView photosList = view.findViewById(R.id.photo_list);
        photosList.setHasFixedSize(true);
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        final boolean canSelectMultiple = mPickerViewModel.canSelectMultiple();
        if (canSelectMultiple) {
            final Button addButton = view.findViewById(R.id.button_add);
            addButton.setOnClickListener(v -> {
                ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
            });

            final Button viewSelectedButton = view.findViewById(R.id.button_view_selected);
            // Transition to PreviewFragment on clicking "View Selected".
            viewSelectedButton.setOnClickListener(this::launchPreview);
            final int bottomBarSize = (int) getResources().getDimension(
                    R.dimen.picker_bottom_bar_size);

            mPickerViewModel.getSelectedItems().observe(this, selectedItemList -> {
                final View bottomBar = view.findViewById(R.id.picker_bottom_bar);
                final int size = selectedItemList.size();
                int dimen = 0;
                if (size == 0) {
                    bottomBar.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(View.VISIBLE);
                    addButton.setText(getString(R.string.add) + " (" + size + ")" );
                    dimen = bottomBarSize;
                }
                photosList.setPadding(0, 0, 0, dimen);
            });
        }

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
        photosList.setLayoutManager(layoutManager);
        photosList.setAdapter(adapter);
    }

    private void onItemClick(@NonNull View view) {
        final boolean isSelectedBefore = view.isSelected();

        if (isSelectedBefore) {
            mPickerViewModel.deleteSelectedItem((Item) view.getTag());
        } else {
            mPickerViewModel.addSelectedItem((Item) view.getTag());
        }

        if (mPickerViewModel.canSelectMultiple()) {
            view.setSelected(!isSelectedBefore);
        } else {
            // Transition to PreviewFragment.
            launchPreview(view);
        }
    }

    private void launchPreview(View view) {
        getActivity().getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container, PreviewFragment.class, null)
                .commitNow();
    }
}