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

package com.android.providers.media.photopicker;

import static com.android.providers.media.photopicker.data.PickerResult.getPickerResponseIntent;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.annotation.IntDef;
import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.ui.AlbumsTabFragment;
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.ui.PreviewFragment;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback;
import com.google.android.material.chip.Chip;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {

    private static final String TAG =  "PhotoPickerActivity";
    private static final String EXTRA_BOTTOM_SHEET_STATE = "bottom_sheet_state";
    private static final String EXTRA_TAB_CHIP_TYPE = "tab_chip_type";
    private static final int TAB_CHIP_TYPE_PHOTOS = 0;
    private static final int TAB_CHIP_TYPE_ALBUMS = 1;
    private int mToolbarHeight = 0;

    @IntDef(prefix = { "TAB_CHIP_TYPE" }, value = {
            TAB_CHIP_TYPE_PHOTOS,
            TAB_CHIP_TYPE_ALBUMS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TabChipType {}

    private PickerViewModel mPickerViewModel;
    private ViewGroup mTabChipContainer;
    private Chip mPhotosTabChip;
    private Chip mAlbumsTabChip;
    @TabChipType
    private int mSelectedTabChipType;
    private BottomSheetBehavior mBottomSheetBehavior;
    private View mBottomSheetView;
    private View mFragmentContainerView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_picker);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        final int[] attrs = new int[] {R.attr.actionBarSize};
        final TypedArray ta = obtainStyledAttributes(attrs);
        // Save toolbar height so that we can use it as padding for FragmentContainerView
        mToolbarHeight = ta.getDimensionPixelSize(0, -1);
        ta.recycle();

        mPickerViewModel = new ViewModelProvider(this).get(PickerViewModel.class);
        try {
            mPickerViewModel.parseValuesFromIntent(getIntent());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Finish activity due to: " + e);
            setCancelledResultAndFinishSelf();
        }

        mTabChipContainer = findViewById(R.id.chip_container);
        initTabChips();
        initBottomSheetBehavior();
        restoreState(savedInstanceState);

        // Save the fragment container layout so that we can adjust the padding based on preview or
        // non-preview mode.
        mFragmentContainerView = findViewById(R.id.fragment_container);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event){
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {

                Rect outRect = new Rect();
                mBottomSheetView.getGlobalVisibleRect(outRect);

                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        getSupportActionBar().setTitle(title);
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     *
     * @param state Bundle to save state
     */
    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(EXTRA_TAB_CHIP_TYPE, mSelectedTabChipType);
        state.putInt(EXTRA_BOTTOM_SHEET_STATE, mBottomSheetBehavior.getState());
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            final int tabChipType = savedInstanceState.getInt(EXTRA_TAB_CHIP_TYPE,
                    TAB_CHIP_TYPE_PHOTOS);
            mSelectedTabChipType = tabChipType;
            if (tabChipType == TAB_CHIP_TYPE_PHOTOS) {
                if (PreviewFragment.get(getSupportFragmentManager()) == null) {
                    onTabChipClick(mPhotosTabChip);
                } else {
                    // PreviewFragment is shown
                    mPhotosTabChip.setSelected(true);
                }
            } else { // CHIP_TYPE_ALBUMS
                if (PhotosTabFragment.get(getSupportFragmentManager()) == null) {
                    onTabChipClick(mAlbumsTabChip);
                } else {
                    // PreviewFragment or PhotosTabFragment with category is shown
                    mAlbumsTabChip.setSelected(true);
                }
            }
            restoreBottomSheetState(savedInstanceState.getInt(EXTRA_BOTTOM_SHEET_STATE));
        } else {
            // This is the first launch, set the default behavior. Hide the title, show the chips
            // and show the PhotosTabFragment
            updateCommonLayouts(MODE_PHOTOS_TAB, /* title */ "");
            onTabChipClick(mPhotosTabChip);
        }
    }

    private static Chip generateTabChip(LayoutInflater inflater, ViewGroup parent, String title) {
        final Chip chip = (Chip) inflater.inflate(R.layout.picker_chip_tab_header, parent, false);
        chip.setText(title);
        return chip;
    }

    private void initTabChips() {
        initPhotosTabChip();
        initAlbumsTabChip();
    }

    private void initBottomSheetBehavior() {
        mBottomSheetView = findViewById(R.id.bottom_sheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(mBottomSheetView);
        if (mPickerViewModel.canSelectMultiple()) {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            mBottomSheetBehavior.setSkipCollapsed(true);
        } else {
            //TODO(b/185800839): Compute this dynamically such that 2 photos rows is shown
            mBottomSheetBehavior.setPeekHeight(1200);
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        mBottomSheetBehavior.addBottomSheetCallback(new BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    finish();
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
            }
        });

        final float cornerRadius =
                getResources().getDimensionPixelSize(R.dimen.picker_top_corner_radius);
        final ViewOutlineProvider viewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        (int)(view.getHeight() + cornerRadius), cornerRadius);
            }
        };
        mBottomSheetView.setOutlineProvider(viewOutlineProvider);
    }

    private void restoreBottomSheetState(int savedState) {
        if (savedState == BottomSheetBehavior.STATE_COLLAPSED ||
                savedState == BottomSheetBehavior.STATE_EXPANDED) {
            mBottomSheetBehavior.setState(savedState);
        }
    }

    private void initPhotosTabChip() {
        if (mPhotosTabChip == null) {
            mPhotosTabChip = generateTabChip(getLayoutInflater(), mTabChipContainer,
                    getString(R.string.picker_photos));
            mTabChipContainer.addView(mPhotosTabChip);
            mPhotosTabChip.setOnClickListener(this::onTabChipClick);
            mPhotosTabChip.setTag(TAB_CHIP_TYPE_PHOTOS);
        }
    }

    private void initAlbumsTabChip() {
        if (mAlbumsTabChip == null) {
            mAlbumsTabChip = generateTabChip(getLayoutInflater(), mTabChipContainer,
                    getString(R.string.picker_albums));
            mTabChipContainer.addView(mAlbumsTabChip);
            mAlbumsTabChip.setOnClickListener(this::onTabChipClick);
            mAlbumsTabChip.setTag(TAB_CHIP_TYPE_ALBUMS);
        }
    }

    private void onTabChipClick(@NonNull View view) {
        final int chipType = (int) view.getTag();
        mSelectedTabChipType = chipType;

        // Check whether the tabChip is already selected or not. If it is selected, do nothing
        if (view.isSelected()) {
            return;
        }

        if (chipType == TAB_CHIP_TYPE_PHOTOS) {
            mPhotosTabChip.setSelected(true);
            mAlbumsTabChip.setSelected(false);
            PhotosTabFragment.show(getSupportFragmentManager(), Category.getDefaultCategory());
        } else { // CHIP_TYPE_ALBUMS
            mPhotosTabChip.setSelected(false);
            mAlbumsTabChip.setSelected(true);
            AlbumsTabFragment.show(getSupportFragmentManager());
        }
    }

    public void setResultAndFinishSelf() {
        final List<Item> selectedItemList = new ArrayList<>(
                mPickerViewModel.getSelectedItems().getValue().values());
        // "persist.sys.photopicker.usepickeruri" property is used to indicate if picker uris should
        // be returned for all intent actions.
        // TODO(b/168001592): Remove this system property when intent-filter for ACTION_GET_CONTENT
        // is removed or when we don't have to send redactedUris any more.
        final boolean usePickerUriByDefault =
                SystemProperties.getBoolean("persist.sys.photopicker.usepickeruri", false);
        final boolean shouldReturnPickerUris = usePickerUriByDefault ||
                MediaStore.ACTION_PICK_IMAGES.equals(getIntent().getAction());
        setResult(Activity.RESULT_OK, getPickerResponseIntent(this, selectedItemList,
                shouldReturnPickerUris));
        finish();
    }

    private void setCancelledResultAndFinishSelf() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    /**
     * Updates the common views such as Title, Toolbar, Navigation bar, status bar and bottom sheet
     * behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     * @param title the title to set for the Activity
     */
    public void updateCommonLayouts(LayoutModeUtils.Mode mode, String title) {
        updateTitle(title);
        updateToolbar(mode);
        updateStatusBarAndNavigationBar(mode);
        updateBottomSheetBehavior(mode);
        updateFragmentContainerViewPadding(mode);
    }

    private void updateTitle(String title) {
        setTitle(title);
    }

    /**
     * Updates the icons and show/hide the tab chips with {@code shouldShowTabChips}.
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateToolbar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final boolean shouldShowTabChips = mode.shouldShowTabChips;
        // 1. Set the tabChip visibility
        mTabChipContainer.setVisibility(shouldShowTabChips ? View.VISIBLE : View.GONE);

        // 2. Set the toolbar color
        final ColorDrawable toolbarColor;
        if (isPreview && !shouldShowTabChips) {
            // Preview defaults to black color irrespective of if it should show tab chips or not
            toolbarColor = new ColorDrawable(getColor(android.R.color.transparent));
        } else {
            toolbarColor = new ColorDrawable(getColor(R.color.picker_background_color));
        }
        getSupportActionBar().setBackgroundDrawable(toolbarColor);

        // 3. Set the toolbar icon.
        final Drawable icon;
        if (shouldShowTabChips) {
            icon = getDrawable(R.drawable.ic_close);
        } else {
            icon = getDrawable(R.drawable.ic_arrow_back);
            // Preview mode has dark background, hence icons will be WHITE in color
            icon.setTint(isPreview ? Color.WHITE : getColor(R.color.picker_toolbar_icon_color));
        }
        getSupportActionBar().setHomeAsUpIndicator(icon);
    }

    /**
     * Updates status bar and navigation bar
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateStatusBarAndNavigationBar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final int navigationBarColor = isPreview ? getColor(R.color.preview_default_black) :
                getColor(R.color.picker_background_color);
        getWindow().setNavigationBarColor(navigationBarColor);

        final int statusBarColor = isPreview ? getColor(R.color.preview_default_black) :
                getColor(android.R.color.transparent);
        getWindow().setStatusBarColor(statusBarColor);

        // Update the system bar appearance
        final int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        int appearance = 0;
        if (!isPreview) {
            final int uiModeNight =
                    getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

            if (uiModeNight == Configuration.UI_MODE_NIGHT_NO) {
                // If the system is not in Dark theme, set the system bars to light mode.
                appearance = mask;
            }
        }
        getWindow().getInsetsController().setSystemBarsAppearance(appearance, mask);
    }

    /**
     * Updates the bottom sheet behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateBottomSheetBehavior(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        if (mBottomSheetView != null) {
            mBottomSheetView.setClipToOutline(!isPreview);
            // TODO(b/185800839): downward swipe for bottomsheet should go back to photos grid
            mBottomSheetBehavior.setDraggable(!isPreview);
        }
        if (isPreview) {
            if (mBottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                // Sets bottom sheet behavior state to STATE_EXPANDED if it's not already expanded.
                // This is useful when user goes to Preview mode which is always Full screen.
                // TODO(b/185800839): Add animation preview to full screen and back transition to
                // partial screen
                mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    /**
     * Updates the FragmentContainerView padding.
     * <p>
     * For Preview mode, toolbar overlaps the Fragment content, hence the padding will be set to 0.
     * For Non-Preview mode, toolbar doesn't overlap the contents of the fragment, hence we set the
     * padding as the height of the toolbar.
     */
    private void updateFragmentContainerViewPadding(@NonNull LayoutModeUtils.Mode mode) {
        if (mFragmentContainerView == null) return;

        if (mode.isPreview) {
            mFragmentContainerView.setPadding(mFragmentContainerView.getPaddingLeft(), 0,
                    mFragmentContainerView.getPaddingRight(),
                    mFragmentContainerView.getPaddingBottom());
        } else {
            mFragmentContainerView.setPadding(mFragmentContainerView.getPaddingLeft(),
                    mToolbarHeight, mFragmentContainerView.getPaddingRight(),
                    mFragmentContainerView.getPaddingBottom());
        }
    }
}