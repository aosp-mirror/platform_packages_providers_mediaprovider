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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

/**
 * Photos tab fragment for showing the photos
 */
public class PhotosTabFragment extends Fragment {

    private PickerViewModel mPickerViewModel;
    private IconHelper mIconHelper;

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
        mIconHelper = new IconHelper(getContext());
        RecyclerView photosList = view.findViewById(R.id.photo_list);
        PhotosTabAdapter adapter = new PhotosTabAdapter(mIconHelper, this::onItemClick);
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);

        mPickerViewModel.getItems().observe(this, itemList -> {
            adapter.updateItemList(itemList);
        });

        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), COLUMN_COUNT);
        photosList.setLayoutManager(layoutManager);
        photosList.setAdapter(adapter);
    }

    private void onItemClick(@NonNull View view) {
        mPickerViewModel.addSelectedItem((Item) view.getTag());
        // TODO: Support Multi-select
        // Transition to PreviewFragment.
        getActivity().getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container, PreviewFragment.class, null)
                .commitNow();
    }
}