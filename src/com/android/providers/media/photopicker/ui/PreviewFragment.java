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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays a selected items in one up view. Supports deselecting items.
 */
public class PreviewFragment extends Fragment {
    private PickerViewModel mPickerViewModel;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent,
            Bundle savedInstanceState) {
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        // TODO(b/185801129): Add handler for back button to go back to previous fragment/activity
        // instead of exiting the activity.
        return inflater.inflate(R.layout.fragment_preview, parent, /* attachToRoot */ false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Warning: The below code assumes that getSelectedItems will never return null.
        final List<Item> selectedItemList = new ArrayList<>(
                mPickerViewModel.getSelectedItems().getValue().values());

        if (selectedItemList.size() > 1 && !mPickerViewModel.canSelectMultiple() ||
                selectedItemList.size() <= 0) {
            // TODO(b/185801129): This should never happen. Add appropriate log messages and
            // handle UI transitions correctly on this error condition.
            // We should also handle this situation in ViewModel
            return;
        }

        // TODO(b/185801129): Support Deselect and Add button to show the size

        // On clicking add button we return the picker result to calling app.
        // This destroys PickerActivity and all fragments.
        Button addButton = view.findViewById(R.id.preview_add_button);
        addButton.setOnClickListener(v -> {
                    ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
        });

        // TODO(b/169737802): Support Videos
        ImageLoader mImageLoader = new ImageLoader(getContext());
        PreviewAdapter adapter = new PreviewAdapter(mImageLoader);
        adapter.updateItemList(selectedItemList);

        ViewPager2 viewPager = view.findViewById(R.id.preview_viewPager);
        viewPager.setAdapter(adapter);
        // TODO(b/185801129) We should set the last saved position instead of zero
        viewPager.setCurrentItem(0);
    }
}
