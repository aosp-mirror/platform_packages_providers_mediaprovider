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

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.PickerResult;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

/**
 * Displays a selected items in one up view. Supports deselecting items.
 */
public class PreviewFragment extends Fragment {
    private PickerViewModel mPickerModel;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent,
            Bundle savedInstanceState) {
        mPickerModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        // TODO(b/185801129): Add handler for back button to go back to previous fragment/activity
        // instead of exiting the activity.
        return inflater.inflate(R.layout.fragment_preview_single_select, parent,
                /* attachToRoot */ false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // TODO(b/185801129): This should never happen. Add appropriate log messages and
        // handle UI transitions correctly on this error condition.
        if (mPickerModel.getSelectedItems().getValue() == null) return;

        final Item selectedItem = mPickerModel.getSelectedItems().getValue().get(0);
        // TODO(b/169737802): Support Videos
        // TODO(b/185801129): Support preview of multiple items
        previewImage(view, selectedItem);
        Button addButton = view.findViewById(R.id.preview_add_button);
        addButton.setOnClickListener(v -> {
            // TODO(b/185801129): Support Multi-select
            getActivity().setResult(Activity.RESULT_OK,
                    PickerResult.getPickerResponseIntent(getActivity().getApplicationContext(),
                            selectedItem));
            getActivity().finish();
        });
    }

    private static void previewImage(View view, Item item) {
        // TODO(b/185801129): Use a ViewHolder
        // TODO(b/185801129): Use Glide for image loading
        // TODO(b/185801129): Load image in background thread. Loading the image blocks loading the
        //  layout now.
        final ImageView imageView = view.findViewById(R.id.preview_imageView);
        if (imageView != null) {
            imageView.setContentDescription(item.getDisplayName());
            imageView.setImageURI(item.getContentUri());
        }
    }
}
