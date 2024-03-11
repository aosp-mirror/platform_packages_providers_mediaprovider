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

import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Drawables.Style.OUTLINE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Drawables.WORK_PROFILE_ICON;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.SWITCH_TO_PERSONAL_MESSAGE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.SWITCH_TO_WORK_MESSAGE;
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_BANNER;
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_SECTION;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserProperties;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.UserManagerState;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.util.AccentColorResources;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * The base abstract Tab fragment
 */
public abstract class TabFragment extends Fragment {
    private static final String TAG = TabFragment.class.getSimpleName();
    protected PickerViewModel mPickerViewModel;
    protected Selection mSelection;
    protected ImageLoader mImageLoader;
    protected AutoFitRecyclerView mRecyclerView;

    private ExtendedFloatingActionButton mProfileButton;
    private ExtendedFloatingActionButton mProfileMenuButton;
    private UserIdManager mUserIdManager;
    private UserManagerState mUserManagerState;
    private boolean mHideProfileButtonAndProfileMenuButton;
    private View mEmptyView;
    private TextView mEmptyTextView;
    private boolean mIsAccessibilityEnabled;

    private Button mAddButton;

    private MaterialButton mViewSelectedButton;
    private View mBottomBar;
    private Animation mSlideUpAnimation;
    private Animation mSlideDownAnimation;

    @ColorInt
    private int mButtonIconAndTextColor;

    @ColorInt
    private int mProfileMenuButtonIconAndTextColor;
    @ColorInt
    private int mButtonBackgroundColor;

    @ColorInt
    private int mButtonDisabledIconAndTextColor;

    @ColorInt
    private int mButtonDisabledBackgroundColor;

    private int mRecyclerViewBottomPadding;
    private boolean mIsProfileButtonVisible = false;
    private boolean mIsProfileMenuButtonVisible = false;
    private static PopupWindow sProfileMenuWindow = null;

    private RecyclerView.OnScrollListener mOnScrollListenerForMultiProfileButton;

    private final MutableLiveData<Boolean> mIsBottomBarVisible = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> mIsProfileButtonOrProfileMenuButtonVisible =
            new MutableLiveData<>(false);
    private ConfigStore mConfigStore;
    private boolean mIsCustomPickerColorSet = false;

    /**
     * In case of multiuser profile, it represents the number of profiles that are off
     * (In quiet mode) with {@link UserProperties.SHOW_IN_QUIET_MODE_HIDDEN}. Such profiles
     * in quiet mode will not appear in photopicker.
     */
    private int mHideProfileCount = 0;

    /**
     * This member variable is relevant to get the userId (other than current user) when only two
     * number of profiles those either unlocked/on or don't have
     * {@link UserProperties.SHOW_IN_QUIET_MODE_HIDDEN},  are available on the device.
     * we are using this variable to get label and icon of a userId to update the content
     * in {@link #mProfileButton}, and at the time when user will press {@link #mProfileButton}
     * to change the current profile.
     */
    private UserId mPotentialUserForProfileButton;

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

        final Context context = requireContext();
        final FragmentActivity activity = requireActivity();

        mImageLoader = new ImageLoader(context);
        mRecyclerView = view.findViewById(R.id.picker_tab_recyclerview);
        mRecyclerView.setHasFixedSize(true);
        final ViewModelProvider viewModelProvider = new ViewModelProvider(activity);
        mPickerViewModel = viewModelProvider.get(PickerViewModel.class);
        mIsCustomPickerColorSet =
                mPickerViewModel.getPickerAccentColorParameters().isCustomPickerColorSet();
        mConfigStore = mPickerViewModel.getConfigStore();
        mSelection = mPickerViewModel.getSelection();
        mRecyclerViewBottomPadding = getResources().getDimensionPixelSize(
                R.dimen.picker_recycler_view_bottom_padding);

        mIsBottomBarVisible.observe(this, val -> updateRecyclerViewBottomPadding());
        mIsProfileButtonOrProfileMenuButtonVisible.observe(
                this, val -> updateRecyclerViewBottomPadding());

        mEmptyView = view.findViewById(android.R.id.empty);
        mEmptyTextView = mEmptyView.findViewById(R.id.empty_text_view);

        final int[] attrsDisabled =
                new int[]{R.attr.pickerDisabledProfileButtonColor,
                        R.attr.pickerDisabledProfileButtonTextColor};
        final TypedArray taDisabled = context.obtainStyledAttributes(attrsDisabled);
        mButtonDisabledBackgroundColor = taDisabled.getColor(/* index */ 0, /* defValue */ -1);
        mButtonDisabledIconAndTextColor = taDisabled.getColor(/* index */ 1, /* defValue */ -1);
        taDisabled.recycle();

        final int[] attrs =
                new int[]{R.attr.pickerProfileButtonColor, R.attr.pickerProfileButtonTextColor,
                        android.R.attr.textColorPrimary};
        final TypedArray ta = context.obtainStyledAttributes(attrs);
        mButtonBackgroundColor = ta.getColor(/* index */ 0, /* defValue */ -1);
        mButtonIconAndTextColor = ta.getColor(/* index */ 1, /* defValue */ -1);
        mProfileMenuButtonIconAndTextColor = ta.getColor(/* index */ 2, /* defValue */ -1);
        ta.recycle();

        mProfileButton = activity.findViewById(R.id.profile_button);
        mProfileMenuButton = activity.findViewById(R.id.profile_menu_button);
        mUserManagerState = mPickerViewModel.getUserManagerState();
        mUserIdManager = mPickerViewModel.getUserIdManager();

        final boolean canSelectMultiple = mSelection.canSelectMultiple();
        if (canSelectMultiple) {
            mAddButton = activity.findViewById(R.id.button_add);

            mViewSelectedButton = activity.findViewById(R.id.button_view_selected);

            if (mIsCustomPickerColorSet) {
                setCustomPickerButtonColors(
                        mPickerViewModel.getPickerAccentColorParameters().getPickerAccentColor());
            }
            mAddButton.setOnClickListener(v -> {
                try {
                    requirePickerActivity().setResultAndFinishSelf();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            });
            // Transition to PreviewFragment on clicking "View Selected".
            mViewSelectedButton.setOnClickListener(v -> {
                // Load items for preview that are pre granted but not yet loaded for UI. This is an
                // async call. Until the items are loaded, we can still preview already available
                // items
                mPickerViewModel.getRemainingPreGrantedItems();
                mSelection.prepareSelectedItemsForPreviewAll();

                int selectedItemCount = mSelection.getSelectedItemCount().getValue();
                mPickerViewModel.logPreviewAllSelected(selectedItemCount);

                try {
                    PreviewFragment.show(requireActivity().getSupportFragmentManager(),
                            PreviewFragment.getArgsForPreviewOnViewSelected());
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            });

            mBottomBar = activity.findViewById(R.id.picker_bottom_bar);

            if (mIsCustomPickerColorSet) {
                mBottomBar.setBackgroundColor(
                        mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                                AccentColorResources.SURFACE_CONTAINER_COLOR_LIGHT,
                                AccentColorResources.SURFACE_CONTAINER_COLOR_DARK
                ));
            }
            // consume the event so that it doesn't get passed through to the next view b/287661737
            mBottomBar.setOnClickListener(v -> {});
            mSlideUpAnimation = AnimationUtils.loadAnimation(context, R.anim.slide_up);
            mSlideDownAnimation = AnimationUtils.loadAnimation(context, R.anim.slide_down);

            mSelection.getSelectedItemCount().observe(this, selectedItemListSize -> {
                // Fetch activity or context again instead of capturing existing variable in lambdas
                // to avoid memory leaks.
                try {
                    if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled()
                            && SdkLevel.isAtLeastS()) {
                        updateProfileButtonAndProfileMenuButtonVisibility();
                    } else {
                        updateProfileButtonVisibility();
                    }
                    updateVisibilityAndAnimateBottomBar(requireContext(), selectedItemListSize);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            });
        }

        if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
            setUpObserverForCrossProfileAndMultiUserChangeGeneric();

            // Initial setup
            setUpProfileButtonAndProfileMenuButtonWithListeners(
                    mUserManagerState.isMultiUserProfiles());

        } else {
            setupObserverForCrossProfileAccess();

            // Initial setup
            setUpProfileButtonWithListeners(mUserIdManager.isMultiUserProfiles());
        }


        final AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        mIsAccessibilityEnabled = accessibilityManager.isEnabled();
        accessibilityManager.addAccessibilityStateChangeListener(enabled -> {
            mIsAccessibilityEnabled = enabled;
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
                setUpProfileButtonAndProfileMenuButtonWithListeners(
                        mUserManagerState.isMultiUserProfiles());
            } else {
                setUpProfileButtonWithListeners(mUserIdManager.isMultiUserProfiles());
            }
        });

    }

    private void setupObserverForCrossProfileAccess() {
        // Observe for cross profile access changes.
        final LiveData<Boolean> crossProfileAllowed = mUserIdManager.getCrossProfileAllowed();
        if (crossProfileAllowed != null) {
            crossProfileAllowed.observe(this, isCrossProfileAllowed -> {
                setUpProfileButton();
                if (Boolean.TRUE.equals(
                        mIsProfileButtonOrProfileMenuButtonVisible.getValue())) {
                    if (isCrossProfileAllowed) {
                        mPickerViewModel.logProfileSwitchButtonEnabled();
                    } else {
                        mPickerViewModel.logProfileSwitchButtonDisabled();
                    }
                }
            });
        }

        // Observe for multi-user changes.
        final LiveData<Boolean> isMultiUserProfiles = mUserIdManager.getIsMultiUserProfiles();
        if (isMultiUserProfiles != null) {
            isMultiUserProfiles.observe(this, this::setUpProfileButtonWithListeners);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setUpObserverForCrossProfileAndMultiUserChangeGeneric() {
        // Observe for cross profile access changes.
        final LiveData<Map<UserId, Boolean>> crossProfileAllowed =
                mUserManagerState.getCrossProfileAllowed();
        if (crossProfileAllowed != null) {
            crossProfileAllowed.observe(this , crossProfileAllowedStatus -> {
                setUpProfileButtonAndProfileMenuButton();
                // Todo(b/318339948): need to put log metrics like present above;
            });
        }

        // Observe for multi-user changes.
        final LiveData<Boolean> isMultiUserProfiles =
                mUserManagerState.getIsMultiUserProfiles();
        if (isMultiUserProfiles != null) {
            isMultiUserProfiles.observe(this, isMultiUserProfilesAvailable -> {
                setUpProfileButtonAndProfileMenuButtonWithListeners(isMultiUserProfilesAvailable);
            });
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void updateUserForProfileButtonAndHideProfileCount() {
        mHideProfileCount = 0;
        mPotentialUserForProfileButton = null;
        for (UserId userId : mUserManagerState.getAllUserProfileIds()) {
            if (isProfileHideInQuietMode(userId)) {
                mHideProfileCount += 1;
            } else if (!userId.equals(UserId.CURRENT_USER)) {
                mPotentialUserForProfileButton = userId;
            }
        }

        // we will use {@link #mPotentialUserForProfileButton} only to show profile button and
        // profile button will only be visible when two profiles are available on the device
        if (mUserManagerState.getProfileCount() - mHideProfileCount != 2) {
            mPotentialUserForProfileButton = null;
        }
    }

    private boolean isProfileHideInQuietMode(UserId userId) {
        if (!SdkLevel.isAtLeastV()) {
            return false;
        }
        /*
         * Any profile with {@link UserProperties.SHOW_IN_QUIET_MODE_HIDDEN}  will not appear in
         * quiet mode in Photopicker.
         */
        return mUserManagerState.isProfileOff(userId)
                && mUserManagerState.getShowInQuietMode(userId)
                == UserProperties.SHOW_IN_QUIET_MODE_HIDDEN;
    }

    private void setCustomPickerButtonColors(int accentColor) {
        String addButtonTextColor =
                mPickerViewModel.getPickerAccentColorParameters().isAccentColorBright()
                        ? AccentColorResources.DARK_TEXT_COLOR
                        : AccentColorResources.LIGHT_TEXT_COLOR;
        mAddButton.setBackgroundColor(accentColor);
        mAddButton.setTextColor(Color.parseColor(addButtonTextColor));
        mViewSelectedButton.setTextColor(accentColor);
        mViewSelectedButton.setIconTint(ColorStateList.valueOf(accentColor));

    }

    private void updateRecyclerViewBottomPadding() {
        final int recyclerViewBottomPadding;
        if (mIsProfileButtonOrProfileMenuButtonVisible.getValue()
                || mIsBottomBarVisible.getValue()) {
            recyclerViewBottomPadding = mRecyclerViewBottomPadding;
        } else {
            recyclerViewBottomPadding = 0;
        }

        mRecyclerView.setPadding(0, 0, 0, recyclerViewBottomPadding);
    }

    private void updateVisibilityAndAnimateBottomBar(@NonNull Context context,
            int selectedItemListSize) {
        if (!mSelection.canSelectMultiple()) {
            return;
        }

        if (mPickerViewModel.isManagedSelectionEnabled()) {
            animateAndShowBottomBar(context, selectedItemListSize);
            if (selectedItemListSize == 0) {
                mViewSelectedButton.setVisibility(View.GONE);
                // Update the add button to show "Allow none".
                mAddButton.setText(R.string.picker_add_button_allow_none_option);
            }
        } else {
            if (selectedItemListSize == 0) {
                animateAndHideBottomBar();
            } else {
                animateAndShowBottomBar(context, selectedItemListSize);
            }
        }
        mIsBottomBarVisible.setValue(
                mPickerViewModel.isManagedSelectionEnabled() || selectedItemListSize > 0);
    }

    private void animateAndShowBottomBar(Context context, int selectedItemListSize) {
        if (mBottomBar.getVisibility() == View.GONE) {
            mBottomBar.setVisibility(View.VISIBLE);
            mBottomBar.startAnimation(mSlideUpAnimation);
        }
        mViewSelectedButton.setVisibility(View.VISIBLE);
        mAddButton.setText(generateAddButtonString(context, selectedItemListSize));
    }

    private void animateAndHideBottomBar() {
        if (mBottomBar.getVisibility() == View.VISIBLE) {
            mBottomBar.setVisibility(View.GONE);
            mBottomBar.startAnimation(mSlideDownAnimation);
        }
    }

    private void setUpListenersForProfileButton() {
        mProfileButton.setOnClickListener(v -> onClickProfileButton());
        mOnScrollListenerForMultiProfileButton = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // Do not change profile button visibility on scroll if Accessibility mode is
                // enabled. This is done to enhance button visibility in Accessibility mode.
                if (mIsAccessibilityEnabled) {
                    return;
                }

                if (dy > 0) {
                    mProfileButton.hide();
                } else {
                    updateProfileButtonVisibility();
                }
            }
        };
        mRecyclerView.addOnScrollListener(mOnScrollListenerForMultiProfileButton);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setUpListenersForProfileButtonAndProfileMenuButton() {
        mProfileButton.setOnClickListener(v -> onClickProfileButtonGeneric());
        mProfileMenuButton.setOnClickListener(v -> onClickProfileMenuButton(v));
        mOnScrollListenerForMultiProfileButton = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // Do not change profile button visibility on scroll if Accessibility mode is
                // enabled. This is done to enhance button visibility in Accessibility mode.
                if (mIsAccessibilityEnabled) {
                    return;
                }

                if (dy > 0) {
                    mProfileButton.hide();
                    mProfileMenuButton.hide();
                } else {
                    updateProfileButtonAndProfileMenuButtonVisibility();
                }
            }
        };
        mRecyclerView.addOnScrollListener(mOnScrollListenerForMultiProfileButton);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void onClickProfileMenuButton(View view) {
        initialiseProfileMenuWindow();
        View profileMenuView = LayoutInflater.from(requireContext()).inflate(
                R.layout.profile_menu_layout, null);
        sProfileMenuWindow.setContentView(profileMenuView);
        LinearLayout profileMenuContainer = profileMenuView.findViewById(
                R.id.profile_menu_container);

        Map<UserId, Drawable> profileBadges = mUserManagerState.getProfileBadgeForAll();
        Map<UserId, String> profileLabels = mUserManagerState.getProfileLabelsForAll();

        // Add profile menu items to profile menu.
        for (UserId userId : mUserManagerState.getAllUserProfileIds()) {
            if (!isProfileHideInQuietMode(userId)) {
                View profileMenuItemView = LayoutInflater.from(requireContext()).inflate(
                        R.layout.profile_menu_item, profileMenuContainer, false);

                // Set label and icon in profile menu item
                TextView profileMenuItem = profileMenuItemView.findViewById(R.id.profile_label);
                String label = profileLabels.get(userId);
                Drawable icon = profileBadges.get(userId);
                boolean isSwitchingAllowed = canSwitchToUser(userId);
                final int textAndIconColor = isSwitchingAllowed
                        ? mProfileMenuButtonIconAndTextColor : mButtonDisabledIconAndTextColor;
                DrawableCompat.setTintList(icon, ColorStateList.valueOf(textAndIconColor));
                profileMenuItem.setTextColor(ColorStateList.valueOf(textAndIconColor));
                profileMenuItem.setText(label);
                profileMenuItem.setCompoundDrawablesWithIntrinsicBounds(
                        icon, null, null, null);
                // Set padding between icon anf label in profile menu button
                int paddingDp = getResources().getDimensionPixelSize(
                        R.dimen.popup_window_title_icon_padding);
                int paddingPixels = (int) (paddingDp * getResources().getDisplayMetrics().density);
                profileMenuItem.setCompoundDrawablePadding(paddingPixels);

                // Add click listener
                profileMenuItemView.setOnClickListener(v -> onClickProfileMenuItem(userId));
                profileMenuContainer.addView(profileMenuItemView);
            }
        }

        /*
         * we need estimated dimensions of {@link #sProfileMenuWindow} to open dropdown just above
         * the {@link #mProfileMenuButton}
         */
        sProfileMenuWindow.showAsDropDown(
                view, view.getWidth() / 2 - getProfileMenuWindowDimensions().first / 2,
                -(getProfileMenuWindowDimensions().second + view.getHeight()));
    }

    private void initialiseProfileMenuWindow() {
        if (sProfileMenuWindow != null) {
            sProfileMenuWindow.dismiss();
        }
        sProfileMenuWindow = new PopupWindow(requireContext());
        sProfileMenuWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        sProfileMenuWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        sProfileMenuWindow.setFocusable(true);
        sProfileMenuWindow.setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.profile_menu_background));
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private boolean canSwitchToUser(UserId userId) {
        return mUserManagerState.getCrossProfileAllowedStatusForAll().get(userId);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void onClickProfileMenuItem(UserId userId) {
        // Check if current user profileId is not same as given userId, where user want to switch
        if (!userId.equals(mUserManagerState.getCurrentUserProfileId())) {
            if (canSwitchToUser(userId)) {
                changeProfileGeneric(userId);
            } else {
                try {
                    ProfileDialogFragment.show(
                            requireActivity().getSupportFragmentManager(), userId);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            }
        }
        sProfileMenuWindow.dismiss();
    }

    /**
     * To get estimated dimensions of {@link #sProfileMenuWindow};
     * @return a pair of two Integers, first represents width and second represents height
     */
    private Pair<Integer, Integer> getProfileMenuWindowDimensions() {
        int width = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        sProfileMenuWindow.getContentView().measure(width, height);

        return new Pair<>(sProfileMenuWindow.getContentView().getMeasuredWidth(),
                sProfileMenuWindow.getContentView().getMeasuredHeight());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mRecyclerView != null) {
            mRecyclerView.clearOnScrollListeners();
        }
    }

    private void setUpProfileButtonWithListeners(boolean isMultiUserProfile) {
        if (mOnScrollListenerForMultiProfileButton != null) {
            mRecyclerView.removeOnScrollListener(mOnScrollListenerForMultiProfileButton);
        }
        if (isMultiUserProfile) {
            setUpListenersForProfileButton();
        }
        setUpProfileButton();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setUpProfileButtonAndProfileMenuButtonWithListeners(boolean isMultiUserProfile) {
        if (mOnScrollListenerForMultiProfileButton != null) {
            mRecyclerView.removeOnScrollListener(mOnScrollListenerForMultiProfileButton);
        }
        if (isMultiUserProfile) {
            setUpListenersForProfileButtonAndProfileMenuButton();
        }
        setUpProfileButtonAndProfileMenuButton();

    }

    private void setUpProfileButton() {
        updateProfileButtonVisibility();
        if (!mUserIdManager.isMultiUserProfiles()) {
            return;
        }

        updateProfileButtonContent(mUserIdManager.isManagedUserSelected());
        updateProfileButtonColor(/* isDisabled */ !mUserIdManager.isCrossProfileAllowed());
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setUpProfileButtonAndProfileMenuButton() {
        // Dismiss profile menu if user remove/lock any profile in the background while profile
        // menu window was opened.
        if (sProfileMenuWindow != null) {
            sProfileMenuWindow.dismiss();
        }
        updateUserForProfileButtonAndHideProfileCount();
        updateProfileButtonAndProfileMenuButtonVisibility();
        if (!mUserManagerState.isMultiUserProfiles()) {
            return;
        }


        updateProfileButtonAndProfileMenuButtonContent();
        updateProfileButtonAndProfileMenuButtonColor();
    }



    private boolean shouldShowProfileButton() {
        return mUserIdManager.isMultiUserProfiles()
                && !mHideProfileButtonAndProfileMenuButton
                && !mPickerViewModel.isUserSelectForApp()
                && (!mSelection.canSelectMultiple()
                        || mSelection.getSelectedItemCount().getValue() == 0);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private boolean shouldShowProfileButtonOrProfileMenuButton() {
        return mUserManagerState.isMultiUserProfiles()
                && (mUserManagerState.getProfileCount() - mHideProfileCount) > 1
                && !mHideProfileButtonAndProfileMenuButton
                && !mPickerViewModel.isUserSelectForApp()
                && (!mSelection.canSelectMultiple()
                || mSelection.getSelectedItemCount().getValue() == 0);
    }

    private void onClickProfileButton() {
        mPickerViewModel.logProfileSwitchButtonClick();

        if (!mUserIdManager.isCrossProfileAllowed()) {
            try {
                ProfileDialogFragment.show(requireActivity().getSupportFragmentManager(),
                        (UserId) null);
            } catch (RuntimeException e) {
                Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
            }
        } else {
            changeProfile();
        }
    }


    /**
     * This method is relevant to get the userId (other than current user profile) when only two
     * number of profiles those either unlocked/on or don't have
     * {@link UserProperties.SHOW_IN_QUIET_MODE_HIDDEN},  are available on the device.
     * we are using this method to get label and icon of a userId to update the content
     * in {@link #mProfileButton}, and at the time when user will press {@link #mProfileButton}
     * to change the current profile.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private UserId getUserToSwitchFromProfileButton() {
        if (mPotentialUserForProfileButton != null
                && mPotentialUserForProfileButton.equals(
                        mUserManagerState.getCurrentUserProfileId())) {
            return UserId.CURRENT_USER;
        }
        return mPotentialUserForProfileButton;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void onClickProfileButtonGeneric() {
        // todo add logs like above

        UserId userIdToSwitch = getUserToSwitchFromProfileButton();
        if (userIdToSwitch != null) {
            if (canSwitchToUser(userIdToSwitch)) {
                changeProfileGeneric(userIdToSwitch);
            } else {
                try {
                    ProfileDialogFragment.show(
                            requireActivity().getSupportFragmentManager(), userIdToSwitch);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            }
        }
    }

    private void changeProfile() {
        if (mUserIdManager.isManagedUserSelected()) {
            // TODO(b/190024747): Add caching for performance before switching data to and fro
            // work profile
            mUserIdManager.setPersonalAsCurrentUserProfile();

        } else {
            // TODO(b/190024747): Add caching for performance before switching data to and fro
            // work profile
            mUserIdManager.setManagedAsCurrentUserProfile();
        }

        updateProfileButtonContent(mUserIdManager.isManagedUserSelected());

        mPickerViewModel.onSwitchedProfile();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void changeProfileGeneric(UserId userIdSwitchTo) {
        mUserManagerState.setUserAsCurrentUserProfile(userIdSwitchTo);
        updateProfileButtonAndProfileMenuButtonContent();

        mPickerViewModel.onSwitchedProfile();
    }

    private void updateProfileButtonContent(boolean isManagedUserSelected) {
        final Drawable icon;
        final String text;
        final Context context;
        try {
            context = requireContext();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not update profile button content because the fragment is not"
                    + " attached.");
            return;
        }

        if (isManagedUserSelected) {
            icon = context.getDrawable(R.drawable.ic_personal_mode);
            text = getSwitchToPersonalMessage(context);
        } else {
            icon = getWorkProfileIcon(context);
            text = getSwitchToWorkMessage(context);
        }
        mProfileButton.setIcon(icon);
        mProfileButton.setText(text);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void updateProfileButtonAndProfileMenuButtonContent() {
        final Context context;
        final Drawable profileButtonIcon, profileMenuButtonIcon;
        final String profileButtonText, profileMenuButtonText;
        final UserId currentUserProfileId = mUserManagerState.getCurrentUserProfileId();
        try {
            context = requireContext();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not update profile button content because the fragment is not"
                    + " attached.");
            return;
        }

        if (mIsProfileMenuButtonVisible) {
            profileMenuButtonIcon =
                    mUserManagerState.getProfileBadgeForAll().get(currentUserProfileId);
            profileMenuButtonText =
                    mUserManagerState.getProfileLabelsForAll().get(currentUserProfileId);
            mProfileMenuButton.setIcon(profileMenuButtonIcon);
            mProfileMenuButton.setText(profileMenuButtonText);
        }

        if (mIsProfileButtonVisible) {
            UserId userIdToSwitch = getUserToSwitchFromProfileButton();
            if (userIdToSwitch != null) {
                if (SdkLevel.isAtLeastV()) {
                    profileButtonIcon =
                            mUserManagerState.getProfileBadgeForAll().get(userIdToSwitch);
                    profileButtonText  = context.getString(R.string.picker_profile_switch_message,
                            mUserManagerState.getProfileLabelsForAll().get(userIdToSwitch));
                } else {
                    if (mUserManagerState.isManagedUserProfile(currentUserProfileId)) {
                        profileButtonIcon = context.getDrawable(R.drawable.ic_personal_mode);
                        profileButtonText = getSwitchToPersonalMessage(context);
                    } else {
                        profileButtonIcon = getWorkProfileIcon(context);
                        profileButtonText = getSwitchToWorkMessage(context);
                    }
                }
                mProfileButton.setIcon(profileButtonIcon);
                mProfileButton.setText(profileButtonText);
            }
        }
    }


    private String getSwitchToPersonalMessage(@NonNull Context context) {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedEnterpriseString(
                    context, SWITCH_TO_PERSONAL_MESSAGE, R.string.picker_personal_profile);
        } else {
            return context.getString(R.string.picker_personal_profile);
        }
    }

    private String getSwitchToWorkMessage(@NonNull Context context) {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedEnterpriseString(
                    context, SWITCH_TO_WORK_MESSAGE, R.string.picker_work_profile);
        } else {
            return context.getString(R.string.picker_work_profile);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private String getUpdatedEnterpriseString(@NonNull Context context,
            @NonNull String updatableStringId,
            int defaultStringId) {
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(updatableStringId, () -> getString(defaultStringId));
    }

    private Drawable getWorkProfileIcon(@NonNull Context context) {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedWorkProfileIcon(context);
        } else {
            return context.getDrawable(R.drawable.ic_work_outline);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private Drawable getUpdatedWorkProfileIcon(@NonNull Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getDrawable(WORK_PROFILE_ICON, OUTLINE, () -> {
            // Fetch activity or context again instead of capturing existing variable in
            // lambdas to avoid memory leaks.
            try {
                return requireContext().getDrawable(R.drawable.ic_work_outline);
            } catch (RuntimeException e) {
                Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                return null;
            }
        });
    }

    private void updateProfileButtonColor(boolean isDisabled) {
        int textAndIconColor =
                isDisabled ? mButtonDisabledIconAndTextColor : mButtonIconAndTextColor;
        int backgroundTintColor =
                isDisabled ? mButtonDisabledBackgroundColor : mButtonBackgroundColor;

        if (mIsCustomPickerColorSet) {
            textAndIconColor = mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                    AccentColorResources.ON_SURFACE_VARIANT_LIGHT,
                    AccentColorResources.ON_SURFACE_VARIANT_DARK
            );
            backgroundTintColor =
                    mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                            AccentColorResources.SURFACE_CONTAINER_LOW_LIGHT,
                            AccentColorResources.SURFACE_CONTAINER_LOW_DARK
                    );
        }

        mProfileButton.setTextColor(ColorStateList.valueOf(textAndIconColor));
        mProfileButton.setIconTint(ColorStateList.valueOf(textAndIconColor));
        mProfileButton.setBackgroundTintList(ColorStateList.valueOf(backgroundTintColor));
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void updateProfileButtonAndProfileMenuButtonColor() {
        if (mIsProfileButtonVisible) {
            boolean isDisabled = true;
            UserId userIdToSwitch = getUserToSwitchFromProfileButton();
            if (userIdToSwitch != null) {
                isDisabled = !canSwitchToUser(userIdToSwitch);
            }

            final int textAndIconColor =
                    isDisabled ? mButtonDisabledIconAndTextColor : mButtonIconAndTextColor;
            final int backgroundTintColor =
                    isDisabled ? mButtonDisabledBackgroundColor : mButtonBackgroundColor;

            mProfileButton.setTextColor(ColorStateList.valueOf(textAndIconColor));
            mProfileButton.setIconTint(ColorStateList.valueOf(textAndIconColor));
            mProfileButton.setBackgroundTintList(ColorStateList.valueOf(backgroundTintColor));
        }

        if (mIsProfileMenuButtonVisible) {
            mProfileMenuButton.setIconTint(ColorStateList.valueOf(mButtonIconAndTextColor));
        }
    }


    protected void hideProfileButton(boolean hide) {
        mHideProfileButtonAndProfileMenuButton = hide;
        updateProfileButtonVisibility();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    protected void hideProfileButtonAndProfileMenuButton(boolean hide) {
        mHideProfileButtonAndProfileMenuButton = hide;
        updateProfileButtonAndProfileMenuButtonVisibility();
    }


    private void updateProfileButtonVisibility() {
        final boolean shouldShowProfileButton = shouldShowProfileButton();
        if (shouldShowProfileButton) {
            mIsProfileButtonVisible = true;
            mProfileButton.show();
        } else {
            mIsProfileButtonVisible = false;
            mProfileButton.hide();
        }
        mIsProfileButtonOrProfileMenuButtonVisible.setValue(shouldShowProfileButton);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void updateProfileButtonAndProfileMenuButtonVisibility() {
        // The button could be either profile button or profile menu button.
        final boolean shouldShowButton = shouldShowProfileButtonOrProfileMenuButton();
        setProfileButtonVisibility(shouldShowButton);
        setProfileMenuButtonVisibility(shouldShowButton);
        mIsProfileButtonOrProfileMenuButtonVisible.setValue(
                shouldShowButton);
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setProfileMenuButtonVisibility(boolean shouldShowProfileMenuButton) {
        mIsProfileMenuButtonVisible = false;
        /*
         *  Check if the total number of profiles that will appear separately in PhotoPicker
         *  is less than three. If more than two such profiles are available, we will show a
         *  dropdown menu with {@link #mProfileMenuButton} representing all available visible
         *  profiles instead of showing a {@link #mProfileMenuButton}
         */
        if (shouldShowProfileMenuButton
                && (mUserManagerState.getProfileCount() - mHideProfileCount) >= 3) {
            mIsProfileMenuButtonVisible = true;
            mProfileMenuButton.show();
        }

        if (!mIsProfileMenuButtonVisible) {
            mProfileMenuButton.hide();
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void setProfileButtonVisibility(boolean shouldShowProfileButton) {
        mIsProfileButtonVisible = false;
        /*
         *  Check if the total number of profiles that will appear separately in PhotoPicker
         *  is less than three. If more than two such profiles are available, we will show a
         *  dropdown menu with {@link #mProfileMenuButton} representing all available visible
         *  profiles instead of showing a {@link #mProfileMenuButton}
         */
        if (shouldShowProfileButton
                && (mUserManagerState.getProfileCount() - mHideProfileCount) < 3) {
            mIsProfileButtonVisible = true;
            mProfileButton.show();
        }
        if (!mIsProfileButtonVisible) {
            mProfileButton.hide();
        }
    }

    protected void setEmptyMessage(int resId) {
        mEmptyTextView.setText(resId);
    }

    /**
     * If we show the {@link #mEmptyView}, hide the {@link #mRecyclerView}. If we don't hide the
     * {@link #mEmptyView}, show the {@link #mRecyclerView}
     * when user switches the profile ,till the time when updated profile data is loading,
     * on the UI we hide {@link #mEmptyView} and show Empty {@link #mRecyclerView}
     */
    protected void updateVisibilityForEmptyView(boolean shouldShowEmptyView) {
        mEmptyView.setVisibility(shouldShowEmptyView ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(shouldShowEmptyView ? View.GONE : View.VISIBLE);
    }

    /**
     * Generates the Button Label for the {@link TabFragment#mAddButton}.
     *
     * @param context The current application context.
     * @param size The current size of the selection.
     * @return Localized, formatted string.
     */
    private String generateAddButtonString(Context context, int size) {
        final String sizeString = NumberFormat.getInstance(Locale.getDefault()).format(size);
        final String template =
                mPickerViewModel.isUserSelectForApp()
                        ? context.getString(R.string.picker_add_button_multi_select_permissions)
                        : context.getString(R.string.picker_add_button_multi_select);

        return TextUtils.expandTemplate(template, sizeString).toString();
    }

    /**
     * Returns {@link PhotoPickerActivity} if the fragment is attached to one. Otherwise, throws an
     * {@link IllegalStateException}.
     */
    protected final PhotoPickerActivity requirePickerActivity() throws IllegalStateException {
        return (PhotoPickerActivity) requireActivity();
    }

    protected final void setLayoutManager(@NonNull Context context,
            @NonNull TabAdapter adapter, int spanCount) {
        final GridLayoutManager layoutManager =
                new GridLayoutManager(context, spanCount);
        final GridLayoutManager.SpanSizeLookup lookup = new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int itemViewType = adapter.getItemViewType(position);
                // For the item view types ITEM_TYPE_BANNER and ITEM_TYPE_SECTION, it is full
                // span, return the span count of the layoutManager.
                if (itemViewType == ITEM_TYPE_BANNER || itemViewType == ITEM_TYPE_SECTION) {
                    return layoutManager.getSpanCount();
                } else {
                    return 1;
                }
            }
        };
        layoutManager.setSpanSizeLookup(lookup);
        mRecyclerView.setLayoutManager(layoutManager);
    }

    private abstract class OnBannerEventListener implements TabAdapter.OnBannerEventListener {
        @Override
        public void onActionButtonClick() {
            mPickerViewModel.logBannerActionButtonClicked();
            dismissBanner();
            launchCloudProviderSettings();
        }

        @Override
        public void onDismissButtonClick() {
            mPickerViewModel.logBannerDismissed();
            dismissBanner();
        }

        @Override
        public void onBannerClick() {
            mPickerViewModel.logBannerClicked();
            dismissBanner();
            launchCloudProviderSettings();
        }

        @Override
        public void onBannerAdded(@NonNull String name) {
            mPickerViewModel.logBannerAdded(name);

            // Should scroll to the banner only if the first completely visible item is the one
            // just below it. The possible adapter item positions of such an item are 0 and 1.
            // During onViewCreated, before restoring the state, the first visible item position
            // is -1, and we should not scroll to position 0 in such cases, else the previously
            // saved recycler view position may get overridden.
            int firstItemPosition = -1;

            final RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                firstItemPosition = ((GridLayoutManager) layoutManager)
                        .findFirstCompletelyVisibleItemPosition();
            }

            if (firstItemPosition == 0 || firstItemPosition == 1) {
                mRecyclerView.scrollToPosition(/* position */ 0);
            }
        }

        abstract void dismissBanner();

        private void launchCloudProviderSettings() {
            final Intent accountChangeIntent =
                    mPickerViewModel.getChooseCloudMediaAccountActivityIntent();

            try {
                if (accountChangeIntent != null) {
                    requirePickerActivity().startActivity(accountChangeIntent);
                } else {
                    requirePickerActivity().startSettingsActivity();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
            }
        }
    }

    protected final OnBannerEventListener mOnChooseAppBannerEventListener =
            new OnBannerEventListener() {
                @Override
                void dismissBanner() {
                    mPickerViewModel.onUserDismissedChooseAppBanner();
                }
            };

    protected final OnBannerEventListener mOnCloudMediaAvailableBannerEventListener =
            new OnBannerEventListener() {
                @Override
                void dismissBanner() {
                    mPickerViewModel.onUserDismissedCloudMediaAvailableBanner();
                }

                @Override
                public boolean shouldShowActionButton() {
                    return mPickerViewModel.getChooseCloudMediaAccountActivityIntent() != null;
                }
            };

    protected final OnBannerEventListener mOnAccountUpdatedBannerEventListener =
            new OnBannerEventListener() {
                @Override
                void dismissBanner() {
                    mPickerViewModel.onUserDismissedAccountUpdatedBanner();
                }
            };

    protected final OnBannerEventListener mOnChooseAccountBannerEventListener =
            new OnBannerEventListener() {
                @Override
                void dismissBanner() {
                    mPickerViewModel.onUserDismissedChooseAccountBanner();
                }

                @Override
                public boolean shouldShowActionButton() {
                    return mPickerViewModel.getChooseCloudMediaAccountActivityIntent() != null;
                }
            };
}
