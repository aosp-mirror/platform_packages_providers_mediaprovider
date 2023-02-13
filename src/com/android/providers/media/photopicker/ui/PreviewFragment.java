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
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Displays a selected items in one up view. Supports deselecting items.
 */
public class PreviewFragment extends Fragment {
    private static String TAG = "PreviewFragment";

    private static final String PREVIEW_TYPE = "preview_type";
    private static final int PREVIEW_ON_LONG_PRESS = 1;
    private static final int PREVIEW_ON_VIEW_SELECTED = 2;

    private static final Bundle sPreviewOnLongPressArgs = new Bundle();
    static {
        sPreviewOnLongPressArgs.putInt(PREVIEW_TYPE, PREVIEW_ON_LONG_PRESS);
    }
    private static final Bundle sPreviewOnViewSelectedArgs = new Bundle();
    static {
        sPreviewOnViewSelectedArgs.putInt(PREVIEW_TYPE, PREVIEW_ON_VIEW_SELECTED);
    }

    private Selection mSelection;
    private PickerViewModel mPickerViewModel;
    private ViewPager2Wrapper mViewPager2Wrapper;
    private boolean mShouldShowGifBadge;
    private boolean mShouldShowMotionPhotoBadge;
    private MuteStatus mMuteStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register with the activity to inform the system that the app bar fragment is
        // participating in the population of the options menu
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.picker_preview_menu, menu);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // All logic to hide/show an item in the menu must be in this method
        final MenuItem gifItem = menu.findItem(R.id.preview_gif);
        final MenuItem motionPhotoItem = menu.findItem(R.id.preview_motion_photo);
        gifItem.setVisible(mShouldShowGifBadge);
        motionPhotoItem.setVisible(mShouldShowMotionPhotoBadge);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent,
            Bundle savedInstanceState) {
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        mSelection = mPickerViewModel.getSelection();
        mMuteStatus = mPickerViewModel.getMuteStatus();
        return inflater.inflate(R.layout.fragment_preview, parent, /* attachToRoot */ false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Set the pane title for A11y.
        view.setAccessibilityPaneTitle(getString(R.string.picker_preview));
        final List<Item> selectedItemsList = mSelection.getSelectedItemsForPreview();
        final int selectedItemsListSize = selectedItemsList.size();

        if (selectedItemsListSize <= 0) {
            // This can happen if we lost PickerViewModel to optimize memory.
            Log.e(TAG, "No items to preview. Returning back to photo grid");
            requireActivity().getSupportFragmentManager().popBackStack();
        } else if (selectedItemsListSize > 1 && !mSelection.canSelectMultiple()) {
            // This should never happen
            throw new IllegalStateException("Found more than one preview items in single select"
                    + " mode. Selected items count: " + selectedItemsListSize);
        }

        // Initialize ViewPager2 to swipe between multiple pictures/videos in preview
        final ViewPager2 viewPager = view.findViewById(R.id.preview_viewPager);
        if (viewPager == null) {
            throw new IllegalStateException("Expected to find ViewPager2 in " + view
                    + ", but found null");
        }
        mViewPager2Wrapper = new ViewPager2Wrapper(viewPager, selectedItemsList, mMuteStatus);

        setUpPreviewLayout(view, getArguments());
        setupScrimLayerAndBottomBar(view);
    }

    private void setupScrimLayerAndBottomBar(View fragmentView) {
        final boolean isLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        // Show the scrim layers in Landscape mode. The default visibility is GONE.
        if (isLandscape) {
            final View topScrim = fragmentView.findViewById(R.id.preview_top_scrim);
            topScrim.setVisibility(View.VISIBLE);

            final View bottomScrim = fragmentView.findViewById(R.id.preview_bottom_scrim);
            bottomScrim.setVisibility(View.VISIBLE);
        }

        // Set appropriate background color for the bottom bar
        final int bottomBarColor;
        if (isLandscape) {
            bottomBarColor = Color.TRANSPARENT;
        } else {
            bottomBarColor = getContext().getColor(R.color.preview_scrim_solid_color);
        }
        final View bottomBar = fragmentView.findViewById(R.id.preview_bottom_bar);
        bottomBar.setBackgroundColor(bottomBarColor);
    }

    private void setUpPreviewLayout(@NonNull View view, @Nullable Bundle args) {
        if (args == null) {
            // We are willing to crash PhotoPickerActivity because this error might only happen
            // during development.
            throw new IllegalArgumentException("Can't determine the type of the Preview, arguments"
                    + " is not set");
        }

        final int previewType = args.getInt(PREVIEW_TYPE, -1);
        if (previewType == PREVIEW_ON_LONG_PRESS) {
            setUpPreviewLayoutForLongPress(view);
        } else if (previewType == PREVIEW_ON_VIEW_SELECTED) {
            setUpPreviewLayoutForViewSelected(view);
        } else {
            // We are willing to crash PhotoPickerActivity because this error might only happen
            // during development.
            throw new IllegalArgumentException("No preview type specified");
        }
    }

    /**
     * Adjusts the select/add button layout for preview on LongPress
     */
    private void setUpPreviewLayoutForLongPress(@NonNull View view) {
        final Button addOrSelectButton = view.findViewById(R.id.preview_add_or_select_button);

        // Preview on Long Press will reuse AddOrSelect button as
        // * Add button - Button with text "Add" - for single select mode
        // * Select button - Button with text "Select"/"Deselect" based on the selection state of
        //                   the item - for multi select mode
        if (!mSelection.canSelectMultiple()) {
            // On clicking add button we return the picker result to calling app.
            // This destroys PickerActivity and all fragments.
            addOrSelectButton.setOnClickListener(v -> {
                ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
            });
        } else {
            // For preview on long press, we always preview only one item.
            // Selection#getSelectedItemsForPreview is guaranteed to return only one item. Hence,
            // we can always use position=0 as current position.
            updateSelectButtonText(addOrSelectButton,
                    mSelection.isItemSelected(mViewPager2Wrapper.getItemAt(/* position */ 0)));
            addOrSelectButton.setOnClickListener(v -> onClickSelectButton(addOrSelectButton));
        }

        // Set the appropriate special format icon based on the item in the preview
        updateSpecialFormatIcon(mViewPager2Wrapper.getItemAt(/* position */ 0));
    }

    /**
     * Adjusts the layout based on Multi select and adds appropriate onClick listeners
     */
    private void setUpPreviewLayoutForViewSelected(@NonNull View view) {
        // Hide addOrSelect button of long press, we have a separate add button for view selected
        final Button addOrSelectButton = view.findViewById(R.id.preview_add_or_select_button);
        addOrSelectButton.setVisibility(View.GONE);

        final Button viewSelectedAddButton = view.findViewById(R.id.preview_add_button);
        viewSelectedAddButton.setVisibility(View.VISIBLE);
        // On clicking add button we return the picker result to calling app.
        // This destroys PickerActivity and all fragments.
        viewSelectedAddButton.setOnClickListener(v -> {
            ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
        });

        final Button selectedCheckButton = view.findViewById(R.id.preview_selected_check_button);
        selectedCheckButton.setVisibility(View.VISIBLE);
        // Update the select icon and text according to the state of selection while swiping
        // between photos
        mViewPager2Wrapper.addOnPageChangeCallback(new OnPageChangeCallback(selectedCheckButton));

        // Update add button text to include number of items selected.
        mSelection
                .getSelectedItemCount()
                .observe(
                        this,
                        selectedItemCount -> {
                            viewSelectedAddButton.setText(
                                    generateAddButtonString(
                                            /* context= */ getContext(),
                                            /* size= */ selectedItemCount,
                                            /* isUserSelectForApp= */ mPickerViewModel
                                                    .isUserSelectForApp()));
                        });

        selectedCheckButton.setOnClickListener(
                v -> onClickSelectedCheckButton(selectedCheckButton));
    }

    @Override
    public void onResume() {
        super.onResume();

        ((PhotoPickerActivity) getActivity()).updateCommonLayouts(LayoutModeUtils.MODE_PREVIEW,
                /* title */"");
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mViewPager2Wrapper != null) {
            mViewPager2Wrapper.onStop();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mViewPager2Wrapper != null) {
            mViewPager2Wrapper.onStart();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mViewPager2Wrapper != null) {
            mViewPager2Wrapper.onDestroy();
        }
    }

    private void onClickSelectButton(@NonNull Button selectButton) {
        final boolean isSelectedNow = updateSelectionAndGetState();
        updateSelectButtonText(selectButton, isSelectedNow);
    }

    private void onClickSelectedCheckButton(@NonNull Button selectedCheckButton) {
        final boolean isSelectedNow = updateSelectionAndGetState();
        updateSelectedCheckButtonStateAndText(selectedCheckButton, isSelectedNow);
    }

    private boolean updateSelectionAndGetState() {
        final Item currentItem = mViewPager2Wrapper.getCurrentItem();
        final boolean wasSelectedBefore = mSelection.isItemSelected(currentItem);

        if (wasSelectedBefore) {
            // If the item is previously selected, current user action is to deselect the item
            mSelection.removeSelectedItem(currentItem);
        } else {
            // If the item is not previously selected, current user action is to select the item
            mSelection.addSelectedItem(currentItem);
        }

        // After the user has clicked the button, current state of the button should be opposite of
        // the previous state.
        // If the previous state was to "Select" the item, and user clicks "Select" button,
        // wasSelectedBefore = false. And item will be added to selected items. Now, user can only
        // deselect the item. Hence, isSelectedNow is opposite of previous state,
        // i.e., isSelectedNow = true.
        return !wasSelectedBefore;
    }

    private class OnPageChangeCallback extends ViewPager2.OnPageChangeCallback {
        private final Button mSelectedCheckButton;

        public OnPageChangeCallback(@NonNull Button selectedCheckButton) {
            mSelectedCheckButton = selectedCheckButton;
        }

        @Override
        public void onPageSelected(int position) {
            // No action to take as we don't have deselect view here.
            if (!mSelection.canSelectMultiple()) return;

            final Item item = mViewPager2Wrapper.getItemAt(position);
            // Set the appropriate select/deselect state for each item in each page based on the
            // selection list.
            updateSelectedCheckButtonStateAndText(mSelectedCheckButton,
                    mSelection.isItemSelected(item));

            // Set the appropriate special format icon based on the item in the preview
            updateSpecialFormatIcon(item);
        }
    }

    private static void updateSelectButtonText(@NonNull Button selectButton,
            boolean isSelected) {
        selectButton.setText(isSelected ? R.string.deselect : R.string.select);
    }

    private static void updateSelectedCheckButtonStateAndText(@NonNull Button selectedCheckButton,
            boolean isSelected) {
        selectedCheckButton.setText(isSelected ? R.string.selected : R.string.deselected);
        selectedCheckButton.setSelected(isSelected);
    }

    private void updateSpecialFormatIcon(Item item) {
        mShouldShowGifBadge = item.isGifOrAnimatedWebp();
        mShouldShowMotionPhotoBadge = item.isMotionPhoto();
        // Invalidating options menu calls onPrepareOptionsMenu() where the logic for
        // hiding/showing menu items is placed.
        requireActivity().invalidateOptionsMenu();
    }

    public static void show(@NonNull FragmentManager fm, @NonNull Bundle args) {
        if (fm.isStateSaved()) {
            Log.d(TAG, "Skip show preview fragment because state saved");
            return;
        }

        final PreviewFragment fragment = new PreviewFragment();
        fragment.setArguments(args);
        fm.beginTransaction()
                .replace(R.id.fragment_container, fragment, TAG)
                .addToBackStack(TAG)
                .commitAllowingStateLoss();
    }

    /**
     * Get the fragment in the FragmentManager
     * @param fm the fragment manager
     */
    public static Fragment get(@NonNull FragmentManager fm) {
        return fm.findFragmentByTag(TAG);
    }

    public static Bundle getArgsForPreviewOnLongPress() {
        return sPreviewOnLongPressArgs;
    }

    public static Bundle getArgsForPreviewOnViewSelected() {
        return sPreviewOnViewSelectedArgs;
    }

    // TODO: There is a same method in TabFragment. To find a way to reuse it.
    private static String generateAddButtonString(
            @NonNull Context context, int size, boolean isUserSelectForApp) {
        final String sizeString = NumberFormat.getInstance(Locale.getDefault()).format(size);
        final String template =
                isUserSelectForApp
                        ? context.getString(R.string.picker_add_button_multi_select_permissions)
                        : context.getString(R.string.picker_add_button_multi_select);
        return TextUtils.expandTemplate(template, sizeString).toString();
    }
}
