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
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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

        final Context context = getContext();
        mImageLoader = new ImageLoader(context);
        mRecyclerView = view.findViewById(R.id.picker_tab_recyclerview);
        mRecyclerView.setHasFixedSize(true);
        final ViewModelProvider viewModelProvider = new ViewModelProvider(requireActivity());
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

        mProfileButton = getActivity().findViewById(R.id.profile_button);
        mUserIdManager = mPickerViewModel.getUserIdManager();

        final boolean canSelectMultiple = mSelection.canSelectMultiple();
        if (canSelectMultiple) {
            mAddButton = getActivity().findViewById(R.id.button_add);
            mAddButton.setOnClickListener(v -> {
                ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
            });

            final Button viewSelectedButton = getActivity().findViewById(R.id.button_view_selected);
            // Transition to PreviewFragment on clicking "View Selected".
            viewSelectedButton.setOnClickListener(v -> {
                mSelection.prepareSelectedItemsForPreviewAll();
                PreviewFragment.show(getActivity().getSupportFragmentManager(),
                        PreviewFragment.getArgsForPreviewOnViewSelected());
            });

            mBottomBar = getActivity().findViewById(R.id.picker_bottom_bar);
            mSlideUpAnimation = AnimationUtils.loadAnimation(getContext(), R.anim.slide_up);
            mSlideDownAnimation = AnimationUtils.loadAnimation(getContext(), R.anim.slide_down);

            mSelection.getSelectedItemCount().observe(this, selectedItemListSize -> {
                updateProfileButtonVisibility();
                updateVisibilityAndAnimateBottomBar(selectedItemListSize);
            });
        }

        // Initial setup
        setUpProfileButtonWithListeners(mUserIdManager.isMultiUserProfiles());

        // Observe for cross profile access changes.
        final LiveData<Boolean> crossProfileAllowed = mUserIdManager.getCrossProfileAllowed();
        if (crossProfileAllowed != null) {
            crossProfileAllowed.observe(this, isCrossProfileAllowed -> {
                setUpProfileButton();
            });
        }

        // Observe for multi-user changes.
        final LiveData<Boolean> isMultiUserProfiles = mUserIdManager.getIsMultiUserProfiles();
        if (isMultiUserProfiles != null) {
            isMultiUserProfiles.observe(this, this::setUpProfileButtonWithListeners);
        }

        final AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        mIsAccessibilityEnabled = accessibilityManager.isEnabled();
        accessibilityManager.addAccessibilityStateChangeListener(enabled -> {
            mIsAccessibilityEnabled = enabled;
            updateProfileButtonVisibility();
        });
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

    private void updateVisibilityAndAnimateBottomBar(int selectedItemListSize) {
        if (!mSelection.canSelectMultiple()) {
            return;
        }

        if (selectedItemListSize == 0) {
            if (mBottomBar.getVisibility() == View.VISIBLE) {
                mBottomBar.setVisibility(View.GONE);
                mBottomBar.startAnimation(mSlideDownAnimation);
            }
        } else {
            if (mBottomBar.getVisibility() == View.GONE) {
                mBottomBar.setVisibility(View.VISIBLE);
                mBottomBar.startAnimation(mSlideUpAnimation);
            }
            mAddButton.setText(generateAddButtonString(getContext(), selectedItemListSize));
        }
        mIsBottomBarVisible.setValue(selectedItemListSize > 0);
    }

    private void setUpListenersForProfileButton() {
        mProfileButton.setOnClickListener(v -> onClickProfileButton());
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
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
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mRecyclerView != null) {
            mRecyclerView.clearOnScrollListeners();
        }
    }

    private void setUpProfileButtonWithListeners(boolean isMultiUserProfile) {
        if (isMultiUserProfile) {
            setUpListenersForProfileButton();
        } else {
            mRecyclerView.clearOnScrollListeners();
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
        if (!mUserIdManager.isCrossProfileAllowed()) {
            ProfileDialogFragment.show(getActivity().getSupportFragmentManager());
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

        mPickerViewModel.onUserSwitchedProfile();
    }

    private void updateProfileButtonContent(boolean isManagedUserSelected) {
        final Drawable icon;
        final String text;
        if (isManagedUserSelected) {
            icon = getContext().getDrawable(R.drawable.ic_personal_mode);
            text = getSwitchToPersonalMessage();
        } else {
            icon = getWorkProfileIcon();
            text = getSwitchToWorkMessage();
        }
        mProfileButton.setIcon(icon);
        mProfileButton.setText(text);
    }

    private String getSwitchToPersonalMessage() {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedEnterpriseString(
                    SWITCH_TO_PERSONAL_MESSAGE, R.string.picker_personal_profile);
        } else {
            return getContext().getString(R.string.picker_personal_profile);
        }
    }

    private String getSwitchToWorkMessage() {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedEnterpriseString(
                    SWITCH_TO_WORK_MESSAGE, R.string.picker_work_profile);
        } else {
            return getContext().getString(R.string.picker_work_profile);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private String getUpdatedEnterpriseString(String updatableStringId, int defaultStringId) {
        final DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(updatableStringId, () -> getString(defaultStringId));
    }

    private Drawable getWorkProfileIcon() {
        if (SdkLevel.isAtLeastT()) {
            return getUpdatedWorkProfileIcon();
        } else {
            return getContext().getDrawable(R.drawable.ic_work_outline);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private Drawable getUpdatedWorkProfileIcon() {
        DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getDrawable(WORK_PROFILE_ICON, OUTLINE, () ->
                getContext().getDrawable(R.drawable.ic_work_outline));
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

    protected final PhotoPickerActivity getPickerActivity() {
        return (PhotoPickerActivity) getActivity();
    }

    protected final void setLayoutManager(@NonNull TabAdapter adapter, int spanCount) {
        final GridLayoutManager layoutManager =
                new GridLayoutManager(getContext(), spanCount);
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
            dismissBanner();
            getPickerActivity().startSettingsActivity();
        }

        @Override
        public void onDismissButtonClick() {
            dismissBanner();
        }

        @Override
        public void onBannerAdded() {
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
            };
}
