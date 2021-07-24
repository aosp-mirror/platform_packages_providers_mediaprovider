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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        updateToolbar(TextUtils.isEmpty(title), /* isLightBackgroundMode= */ true);
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
     * Update the icons and show/hide the tab chips with {@code shouldShowTabChips}.
     *
     * When the tab chips are shown, picker is always in light background mode.
     * When the tab chips are not shown, whether picker is in light background mode or dark
     * background mode depends on {@code isLightBackgroundMode}.
     *
     * @param shouldShowTabChips {@code true}, show the tab chips and show close icon. Otherwise,
     *                           hide the tab chips and show back icon
     * @param isLightBackgroundMode {@code true}, show light background and dark icon.
     *                              Otherwise, show dark background and light icon.
     *
     */
    public void updateToolbar(boolean shouldShowTabChips, boolean isLightBackgroundMode) {
        if (shouldShowTabChips) {
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
            getSupportActionBar().setBackgroundDrawable(
                    new ColorDrawable(getColor(R.color.picker_background_color)));
            mTabChipContainer.setVisibility(View.VISIBLE);
            // In show tab chips case, picker is always in lightBackground mode in light theme.
            updateStatusBarAndNavBar(/* isLightBackgroundMode= */ true);
        } else {
            final Drawable icon = getDrawable(R.drawable.ic_arrow_back);
            if (isLightBackgroundMode) {
                icon.setTint(getColor(R.color.picker_toolbar_icon_color));
                getSupportActionBar().setBackgroundDrawable(
                        new ColorDrawable(getColor(R.color.picker_background_color)));
            } else {
                icon.setTint(Color.WHITE);
                getSupportActionBar().setBackgroundDrawable(
                        new ColorDrawable(getColor(R.color.preview_default_black)));
            }
            updateStatusBarAndNavBar(isLightBackgroundMode);
            getSupportActionBar().setHomeAsUpIndicator(icon);
            mTabChipContainer.setVisibility(View.GONE);
        }
    }

    private void updateStatusBarAndNavBar(boolean isLightBackgroundMode) {
        final int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
        final int backgroundColor;
        if (isLightBackgroundMode) {
            backgroundColor = getColor(R.color.picker_background_color);

            final int uiModeNight =
                    getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

            // if the system is not in Dark theme, set the system bars to light mode.
            if (uiModeNight == Configuration.UI_MODE_NIGHT_NO) {
                getWindow().getInsetsController().setSystemBarsAppearance(mask, mask);
            }
        } else {
            backgroundColor = getColor(R.color.preview_default_black);
            getWindow().getInsetsController().setSystemBarsAppearance(/* appearance= */ 0, mask);
        }
        getWindow().setStatusBarColor(backgroundColor);
        getWindow().setNavigationBarColor(backgroundColor);
    }
}