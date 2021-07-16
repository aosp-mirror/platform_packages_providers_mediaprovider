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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout.LayoutParams;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Displays a selected items in one up view. Supports deselecting items.
 */
public class PreviewFragment extends Fragment {
    private static String TAG = "PreviewFragment";

    private PickerViewModel mPickerViewModel;
    private ViewPager2 mViewPager;
    private PreviewAdapter mAdapter;
    private ViewPager2.OnPageChangeCallback mOnPageChangeCallBack;

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
        // Hide the toolbar for now. TODO(185801129): Change the layout of the toolbar or add new
        // toolbar that can overlap with image/video preview if necessary
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();

        // Warning: The below code assumes that getSelectedItems will never return null.
        // We are creating a new ArrayList with selected items, this list used as data for the
        // adapter. If activity gets killed and recreated, we will lose items that were deselected.
        // TODO(b/185801129): Save the deselection state instead of making a copy of selected items.
        // TODO(b/185801129): Sort images/videos on based on date_taken
        final List<Item> selectedItemList = new ArrayList<>(
                mPickerViewModel.getSelectedItems().getValue().values());

        if (selectedItemList.size() > 1 && !mPickerViewModel.canSelectMultiple() ||
                selectedItemList.size() <= 0) {
            // TODO(b/185801129): This should never happen. Add appropriate log messages and
            // handle UI transitions correctly on this error condition.
            // We should also handle this situation in ViewModel
            return;
        }

        Button addButton = view.findViewById(R.id.preview_add_button);

        // On clicking add button we return the picker result to calling app.
        // This destroys PickerActivity and all fragments.
        addButton.setOnClickListener(v -> {
            ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
        });

        // TODO(b/169737802): Support Videos
        // Initialize adapter to hold selected items
        ImageLoader imageLoader = new ImageLoader(getContext());
        mAdapter = new PreviewAdapter(imageLoader);
        mAdapter.updateItemList(selectedItemList);

        // Initialize ViewPager2 to swipe between multiple pictures/videos in preview
        mViewPager = view.findViewById(R.id.preview_viewPager);
        mViewPager.setAdapter(mAdapter);

        Button selectButton = view.findViewById(R.id.preview_select_button);

        // Update the select icon and text according to the state of selection while swiping
        // between photos
        mOnPageChangeCallBack = new OnPageChangeCallBack(selectButton);
        mViewPager.registerOnPageChangeCallback(mOnPageChangeCallBack);

        // Adjust the layout based on Single/Multi select and add appropriate onClick listeners
        if (!mPickerViewModel.canSelectMultiple()) {
            // Adjust the select and add button layout for single select
            LayoutParams layoutParams
                    = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            addButton.setLayoutParams(layoutParams);
            selectButton.setVisibility(View.GONE);
        } else {
            // Update add button text to include number of items selected.
            mPickerViewModel.getSelectedItems().observe(this, selectedItems -> {
                addButton.setText(generateAddButtonString(getContext(), selectedItems.size()));
            });
            selectButton.setOnClickListener(v -> {
                onClickSelect(selectButton);
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // TODO(185801129): Change the layout of the toolbar or add new toolbar that can overlap
        // with image/video preview if necessary
        getActivity().setTitle("");

        // This is necessary to ensure we call ViewHolder#bind() onResume()
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOnPageChangeCallBack != null && mViewPager != null) {
            mViewPager.unregisterOnPageChangeCallback(mOnPageChangeCallBack);
        }
    }

    private void onClickSelect(@NonNull Button selectButton) {
        // isSelected tracks new state for select button, which is opposite of old state
        final boolean isSelected = !selectButton.isSelected();
        final Item currentItem = mAdapter.getItem(mViewPager.getCurrentItem());

        if (isSelected) {
            mPickerViewModel.addSelectedItem(currentItem);
        } else {
            mPickerViewModel.deleteSelectedItem(currentItem);
        }
        setSelected(selectButton, isSelected);
    }

    private class OnPageChangeCallBack extends ViewPager2.OnPageChangeCallback {
        private final Button mSelectButton;

        public OnPageChangeCallBack(@NonNull Button selectButton) {
            mSelectButton = selectButton;
        }

        @Override
        public void onPageSelected(int position) {
            // No action to take as we don't have deselect view here.
            if (!mPickerViewModel.canSelectMultiple()) return;

            // Set the appropriate select/deselect state for each item in each page based on the
            // selection list.
            setSelected(mSelectButton, mPickerViewModel.getSelectedItems().getValue().containsKey(
                    mAdapter.getItem(position).getContentUri()));
        }
    }

    private static void setSelected(@NonNull Button selectButton, boolean isSelected) {
        selectButton.setSelected(isSelected);
        selectButton.setText(isSelected ? R.string.deselect : R.string.select);
    }

    public static void show(FragmentManager fm) {
        if (fm.isStateSaved()) {
            Log.d(TAG, "Skip show preview fragment because state saved");
            return;
        }

        final PreviewFragment fragment = new PreviewFragment();
        fm.beginTransaction()
                .replace(R.id.fragment_container, fragment, TAG)
                .addToBackStack(TAG)
                .commitAllowingStateLoss();
    }

    /**
     * Get the fragment in the FragmentManager
     * @param fm The fragment manager
     */
    public static Fragment get(FragmentManager fm) {
        return fm.findFragmentByTag(TAG);
    }

    // TODO: There is a same method in TabFragment. To find a way to reuse it.
    private static String generateAddButtonString(Context context, int size) {
        final String sizeString = NumberFormat.getInstance(Locale.getDefault()).format(size);
        final String template = context.getString(R.string.picker_add_button_multi_select);
        return TextUtils.expandTemplate(template, sizeString).toString();
    }
}
