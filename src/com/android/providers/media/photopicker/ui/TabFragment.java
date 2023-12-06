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
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.text.NumberFormat;
import java.util.Locale;

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
    private UserIdManager mUserIdManager;
    private boolean mHideProfileButton;
    private View mEmptyView;
    private TextView mEmptyTextView;
    private boolean mIsAccessibilityEnabled;

    private Button mAddButton;

    private Button mViewSelectedButton;
    private View mBottomBar;
    private Animation mSlideUpAnimation;
    private Animation mSlideDownAnimation;

    @ColorInt
    private int mButtonIconAndTextColor;

    @ColorInt
    private int mButtonBackgroundColor;

    @ColorInt
    private int mButtonDisabledIconAndTextColor;

    @ColorInt
    private int mButtonDisabledBackgroundColor;

    private int mRecyclerViewBottomPadding;

    private RecyclerView.OnScrollListener mOnScrollListenerForMultiProfileButton;

    private final MutableLiveData<Boolean> mIsBottomBarVisible = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> mIsProfileButtonVisible = new MutableLiveData<>(false);

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
        mSelection = mPickerViewModel.getSelection();
        mRecyclerViewBottomPadding = getResources().getDimensionPixelSize(
                R.dimen.picker_recycler_view_bottom_padding);

        mIsBottomBarVisible.observe(this, val -> updateRecyclerViewBottomPadding());
        mIsProfileButtonVisible.observe(this, val -> updateRecyclerViewBottomPadding());

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
                new int[]{R.attr.pickerProfileButtonColor, R.attr.pickerProfileButtonTextColor};
        final TypedArray ta = context.obtainStyledAttributes(attrs);
        mButtonBackgroundColor = ta.getColor(/* index */ 0, /* defValue */ -1);
        mButtonIconAndTextColor = ta.getColor(/* index */ 1, /* defValue */ -1);
        ta.recycle();

        mProfileButton = activity.findViewById(R.id.profile_button);
        mUserIdManager = mPickerViewModel.getUserIdManager();

        final boolean canSelectMultiple = mSelection.canSelectMultiple();
        if (canSelectMultiple) {
            mAddButton = activity.findViewById(R.id.button_add);
            mViewSelectedButton = activity.findViewById(R.id.button_view_selected);
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
            // consume the event so that it doesn't get passed through to the next view b/287661737
            mBottomBar.setOnClickListener(v -> {});
            mSlideUpAnimation = AnimationUtils.loadAnimation(context, R.anim.slide_up);
            mSlideDownAnimation = AnimationUtils.loadAnimation(context, R.anim.slide_down);

            mSelection.getSelectedItemCount().observe(this, selectedItemListSize -> {
                // Fetch activity or context again instead of capturing existing variable in lambdas
                // to avoid memory leaks.
                try {
                    updateProfileButtonVisibility();
                    updateVisibilityAndAnimateBottomBar(requireContext(), selectedItemListSize);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                }
            });
        }

        // Observe for cross profile access changes.
        final LiveData<Boolean> crossProfileAllowed = mUserIdManager.getCrossProfileAllowed();
        if (crossProfileAllowed != null) {
            crossProfileAllowed.observe(this, isCrossProfileAllowed -> {
                setUpProfileButton();
                if (Boolean.TRUE.equals(mIsProfileButtonVisible.getValue())) {
                    if (isCrossProfileAllowed) {
                        mPickerViewModel.logProfileSwitchButtonEnabled();
                    } else {
                        mPickerViewModel.logProfileSwitchButtonDisabled();
                    }
                }
            });
        }


        final AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        mIsAccessibilityEnabled = accessibilityManager.isEnabled();
        accessibilityManager.addAccessibilityStateChangeListener(enabled -> {
            mIsAccessibilityEnabled = enabled;
            setUpProfileButtonWithListeners(mUserIdManager.isMultiUserProfiles());
        });

        // Observe for multi-user changes.
        final LiveData<Boolean> isMultiUserProfiles = mUserIdManager.getIsMultiUserProfiles();
        if (isMultiUserProfiles != null) {
            isMultiUserProfiles.observe(this, this::setUpProfileButtonWithListeners);
        }

        // Initial setup
        setUpProfileButtonWithListeners(mUserIdManager.isMultiUserProfiles());
    }

    private void updateRecyclerViewBottomPadding() {
        final int recyclerViewBottomPadding;
        if (mIsProfileButtonVisible.getValue() || mIsBottomBarVisible.getValue()) {
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

    private void setUpProfileButton() {
        updateProfileButtonVisibility();
        if (!mUserIdManager.isMultiUserProfiles()) {
            return;
        }

        updateProfileButtonContent(mUserIdManager.isManagedUserSelected());
        updateProfileButtonColor(/* isDisabled */ !mUserIdManager.isCrossProfileAllowed());
    }

    private boolean shouldShowProfileButton() {
        return mUserIdManager.isMultiUserProfiles()
                && !mHideProfileButton
                && !mPickerViewModel.isUserSelectForApp()
                && (!mSelection.canSelectMultiple()
                        || mSelection.getSelectedItemCount().getValue() == 0);
    }

    private void onClickProfileButton() {
        mPickerViewModel.logProfileSwitchButtonClick();

        if (!mUserIdManager.isCrossProfileAllowed()) {
            try {
                ProfileDialogFragment.show(requireActivity().getSupportFragmentManager());
            } catch (RuntimeException e) {
                Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
            }
        } else {
            changeProfile();
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
        final int textAndIconColor =
                isDisabled ? mButtonDisabledIconAndTextColor : mButtonIconAndTextColor;
        final int backgroundTintColor =
                isDisabled ? mButtonDisabledBackgroundColor : mButtonBackgroundColor;

        mProfileButton.setTextColor(ColorStateList.valueOf(textAndIconColor));
        mProfileButton.setIconTint(ColorStateList.valueOf(textAndIconColor));
        mProfileButton.setBackgroundTintList(ColorStateList.valueOf(backgroundTintColor));
    }

    protected void hideProfileButton(boolean hide) {
        mHideProfileButton = hide;
        updateProfileButtonVisibility();
    }

    private void updateProfileButtonVisibility() {
        final boolean shouldShowProfileButton = shouldShowProfileButton();
        if (shouldShowProfileButton) {
            mProfileButton.show();
        } else {
            mProfileButton.hide();
        }
        mIsProfileButtonVisible.setValue(shouldShowProfileButton);
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
