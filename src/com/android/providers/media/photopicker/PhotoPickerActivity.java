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

import android.annotation.IntDef;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.AlbumsTabFragment;
import com.android.providers.media.photopicker.ui.PhotosTabFragment;
import com.android.providers.media.photopicker.ui.PreviewFragment;
import com.android.providers.media.photopicker.util.CrossProfileUtils;
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
    private static final String EXTRA_TAB_CHIP_TYPE = "tab_chip_type";
    private static final int TAB_CHIP_TYPE_PHOTOS = 0;
    private static final int TAB_CHIP_TYPE_ALBUMS = 1;

    @IntDef(prefix = { "TAB_CHIP_TYPE" }, value = {
            TAB_CHIP_TYPE_PHOTOS,
            TAB_CHIP_TYPE_ALBUMS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TabChipType {}

    private PickerViewModel mPickerViewModel;
    private UserIdManager mUserIdManager;
    private ViewGroup mTabChipContainer;
    private Chip mPhotosTabChip;
    private Chip mAlbumsTabChip;
    @TabChipType
    private int mSelectedTabChipType;
    private BottomSheetBehavior mBottomSheetBehavior;
    private View mBottomSheetView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_picker);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mPickerViewModel = new ViewModelProvider(this).get(PickerViewModel.class);
        try {
            mPickerViewModel.parseValuesFromIntent(getIntent());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Finish activity due to: " + e);
            setCancelledResultAndFinishSelf();
        }

        mTabChipContainer = findViewById(R.id.chip_container);
        initTabChips();
        restoreState(savedInstanceState);

        mUserIdManager = mPickerViewModel.getUserIdManager();
        final Switch profileSwitch = findViewById(R.id.workprofile);
        if (mUserIdManager.isMultiUserProfiles()) {
            profileSwitch.setVisibility(View.VISIBLE);
            setUpWorkProfileToggleSwitch(profileSwitch);
        }
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

        final float cornerRadiusDP = getResources().getDimension(R.dimen.picker_top_corner_radius);
        final float cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
                cornerRadiusDP, getResources().getDisplayMetrics());
        final ViewOutlineProvider viewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        (int)(view.getHeight() + cornerRadius), cornerRadius);
            }
        };
        mBottomSheetView.setOutlineProvider(viewOutlineProvider);
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
        updateCommonLayouts(/* shouldShowTabChips */ TextUtils.isEmpty(title),
                /* isPreview */ false);
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
        } else {
            // This is the first launch, set the default behavior. Hide the title, show the chips
            // and show the PhotosTabFragment
            setTitle("");
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

    private void setUpWorkProfileToggleSwitch(Switch profileSwitch) {
        if (mUserIdManager.isManagedUserId()) {
            profileSwitch.setChecked(true);
        }

        final Context context = this;
        profileSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to work profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setManagedAsCurrentUserProfile();

                } else {
                    Toast.makeText(PhotoPickerActivity.this, "Switching to personal profile",
                            Toast.LENGTH_SHORT).show();
                    // TODO(b/190024747): Add caching for performance before switching data to and
                    //  fro work profile
                    mUserIdManager.setPersonalAsCurrentUserProfile();
                }

                // Cross user checks
                if (!mUserIdManager.isCurrentUserSelected()) {
                    final PackageManager packageManager = context.getPackageManager();
                    // 1. Check if PICK_IMAGES intent is allowed by admin to show cross user content
                    if (!CrossProfileUtils.isPickImagesIntentAllowedCrossProfileAccess(
                            packageManager)) {
                        Log.i(TAG, "Device admin restricts PhotoPicker to show cross profile "
                                + "content for current user: " + UserId.CURRENT_USER);
                        // TODO (b/190727775): Show informative error message to the user in UI.
                        return;
                    }

                    // 2. Check if work profile is off
                    if (mUserIdManager.isManagedUserSelected()) {
                        final UserId currentUserProfileId =
                                mUserIdManager.getCurrentUserProfileId();
                        if (!CrossProfileUtils.isMediaProviderAvailable(currentUserProfileId,
                                    context)) {
                            Log.i(TAG, "Work Profile is off, please turn work profile on to "
                                    + "access work profile content");
                            // TODO (b/190727775): Show work profile turned off, please turn on.
                            return;
                        }
                    }
                }
                mPickerViewModel.updateItems();
            }
        });
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
     * Updates the common views such as Toolbar, Navigation bar, status bar and bottom sheet
     * behavior
     *
     * @param shouldShowTabChips {@code true} if tab chips for Photos/Albums tab should be shown,
     *                                       {@code false} otherwise
     * @param isPreview {@code true} if common views should be customized for Preview mode,
     *                              {@code false} otherwise
     */
    public void updateCommonLayouts(boolean shouldShowTabChips, boolean isPreview) {
        updateToolbar(shouldShowTabChips, isPreview);
        updateStatusBarAndNavigationBar(isPreview);
        updateBottomSheetBehavior(isPreview);
    }

    /**
     * Updates the icons and show/hide the tab chips with {@code shouldShowTabChips}.
     *
     * @param shouldShowTabChips {@code true}, show the tab chips and show close icon. Otherwise,
     *                                       hide the tab chips and show back icon.
     * @param isPreview {@code true} sets the toolbar based on Preview Mode, {@code false} sets
     *                              the toolbar based on value of {@code shouldShowTabChips}
     */
    private void updateToolbar(boolean shouldShowTabChips, boolean isPreview) {
        // 1. Set the tabChip visibility
        mTabChipContainer.setVisibility(shouldShowTabChips ? View.VISIBLE : View.GONE);

        // 2. Set the toolbar color
        final ColorDrawable toolbarColor;
        if (isPreview && !shouldShowTabChips) {
            // Preview defaults to black color irrespective of if it should show tab chips or not
            toolbarColor = new ColorDrawable(getColor(R.color.preview_default_black));
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
     * @param isPreview {@code true} to set the status bar and navigation bar according to preview
     *                              mode, {@code false} to set status bar and navigation bar
     *                              according to Photos or Category mode.
     */
    private void updateStatusBarAndNavigationBar(boolean isPreview) {
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
     * @param isPreview {@code true} sets the bottom sheet behavior for preview mode, {@code false}
     *                              sets the bottom sheet behavior for non-preview mode.
     */
    private void updateBottomSheetBehavior(boolean isPreview) {
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
}