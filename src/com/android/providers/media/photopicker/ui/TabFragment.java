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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The base abstract Tab fragment
 */
public abstract class TabFragment extends Fragment {

    protected PickerViewModel mPickerViewModel;
    protected ImageLoader mImageLoader;
    protected RecyclerView mRecyclerView;
    private int mBottomBarSize;

    @Override
    @NonNull
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_picker_tab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mImageLoader = new ImageLoader(getContext());
        mRecyclerView = view.findViewById(R.id.photo_list);
        mRecyclerView.setHasFixedSize(true);
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        final boolean canSelectMultiple = mPickerViewModel.canSelectMultiple();
        if (canSelectMultiple) {
            final Button addButton = view.findViewById(R.id.button_add);
            addButton.setOnClickListener(v -> {
                ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
            });

            final Button viewSelectedButton = view.findViewById(R.id.button_view_selected);
            // Transition to PreviewFragment on clicking "View Selected".
            viewSelectedButton.setOnClickListener(v -> {
                PreviewFragment.show(getActivity().getSupportFragmentManager());
            });
            mBottomBarSize = (int) getResources().getDimension(R.dimen.picker_bottom_bar_size);

            mPickerViewModel.getSelectedItems().observe(this, selectedItemList -> {
                final View bottomBar = view.findViewById(R.id.picker_bottom_bar);
                final int size = selectedItemList.size();
                int dimen = 0;
                if (size == 0) {
                    bottomBar.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(View.VISIBLE);
                    addButton.setText(generateAddButtonString(getContext(), size));
                    dimen = getBottomGapForRecyclerView(mBottomBarSize);
                }
                mRecyclerView.setPadding(0, 0, 0, dimen);
            });
        }
    }

    protected int getBottomGapForRecyclerView(int bottomBarSize) {
        return bottomBarSize;
    }

    private static String generateAddButtonString(Context context, int size) {
        final String sizeString = NumberFormat.getInstance(Locale.getDefault()).format(size);
        final String template = context.getString(R.string.picker_add_button_multi_select);
        return TextUtils.expandTemplate(template, sizeString).toString();
    }
}
