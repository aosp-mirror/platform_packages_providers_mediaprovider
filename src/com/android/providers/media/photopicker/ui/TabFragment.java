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
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PhotoPickerActivity;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;
import com.android.providers.media.util.ForegroundThread;

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

    private int mBottomBarSize;
    private ExtendedFloatingActionButton mProfileButton;
    private UserIdManager mUserIdManager;
    private boolean mHideProfileButton;

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
        mRecyclerView = view.findViewById(R.id.picker_tab_recyclerview);
        mRecyclerView.setHasFixedSize(true);
        mPickerViewModel = new ViewModelProvider(requireActivity()).get(PickerViewModel.class);
        mSelection = mPickerViewModel.getSelection();

        mProfileButton = view.findViewById(R.id.profile_button);
        mUserIdManager = mPickerViewModel.getUserIdManager();

        final boolean canSelectMultiple = mSelection.canSelectMultiple();
        if (canSelectMultiple) {
            final Button addButton = view.findViewById(R.id.button_add);
            addButton.setOnClickListener(v -> {
                ((PhotoPickerActivity) getActivity()).setResultAndFinishSelf();
            });

            final Button viewSelectedButton = view.findViewById(R.id.button_view_selected);
            // Transition to PreviewFragment on clicking "View Selected".
            viewSelectedButton.setOnClickListener(v -> {
                mSelection.prepareSelectedItemsForPreviewAll();
                PreviewFragment.show(getActivity().getSupportFragmentManager(),
                        PreviewFragment.getArgsForPreviewOnViewSelected());
            });
            mBottomBarSize = (int) getResources().getDimension(R.dimen.picker_bottom_bar_size);

            mSelection.getSelectedItemCount().observe(this, selectedItemListSize -> {
                final View bottomBar = view.findViewById(R.id.picker_bottom_bar);
                int dimen = 0;
                if (selectedItemListSize == 0) {
                    bottomBar.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(View.VISIBLE);
                    addButton.setText(generateAddButtonString(getContext(), selectedItemListSize));
                    dimen = getBottomGapForRecyclerView(mBottomBarSize);
                }
                mRecyclerView.setPadding(0, 0, 0, dimen);

                if (mUserIdManager.isMultiUserProfiles()) {
                    if (shouldShowProfileButton()) {
                        mProfileButton.show();
                    } else {
                        mProfileButton.hide();
                    }
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        updateProfileButtonAsync();
    }

    private void updateProfileButtonAsync() {
        ForegroundThread.getExecutor().execute(() -> {
            mUserIdManager.updateCrossProfileValues();

            getActivity().runOnUiThread(() -> setUpProfileButton());
        });
    }

    private void setUpProfileButton() {
        if (!mUserIdManager.isMultiUserProfiles()) {
            if (mProfileButton.getVisibility() == View.VISIBLE) {
                mProfileButton.setVisibility(View.GONE);
                mRecyclerView.clearOnScrollListeners();
            }
            return;
        }

        if (shouldShowProfileButton()) {
            mProfileButton.setVisibility(View.VISIBLE);

            // TODO(b/199473568): Set up listeners for profile button only once for a fragment or
            // when the value of isMultiUserProfile changes to true
            mProfileButton.setOnClickListener(v -> onClickProfileButton());
            mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (dy > 0) {
                        mProfileButton.hide();
                    } else {
                        if (shouldShowProfileButton()) {
                            mProfileButton.show();
                        }
                    }
                }
            });
        }

        updateProfileButtonContent(mUserIdManager.isManagedUserSelected());
        updateProfileButtonColor(/* isDisabled */ !mUserIdManager.isCrossProfileAllowed());
    }

    private boolean shouldShowProfileButton() {
        return !mHideProfileButton &&
                (!mSelection.canSelectMultiple() ||
                        mSelection.getSelectedItemCount().getValue() == 0);
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

        mPickerViewModel.updateItems();
        mPickerViewModel.updateCategories();
    }

    private void updateProfileButtonContent(boolean isManagedUserSelected) {
        final int iconResId;
        final int textResId;
        if (isManagedUserSelected) {
            iconResId = R.drawable.ic_personal_mode;
            textResId = R.string.picker_personal_profile;
        } else {
            iconResId = R.drawable.ic_work_outline;
            textResId = R.string.picker_work_profile;
        }
        mProfileButton.setIconResource(iconResId);
        mProfileButton.setText(textResId);
    }

    private void updateProfileButtonColor(boolean isDisabled) {
        final int textAndIconResId;
        final int backgroundTintResId;
        if (isDisabled) {
            textAndIconResId = R.color.picker_profile_disabled_button_content_color;
            backgroundTintResId = R.color.picker_profile_disabled_button_background_color;
        } else {
            textAndIconResId = R.color.picker_profile_button_content_color;
            backgroundTintResId = R.color.picker_profile_button_background_color;
        }
        mProfileButton.setTextColor(AppCompatResources.getColorStateList(getContext(),
                textAndIconResId));
        mProfileButton.setIconTintResource(textAndIconResId);
        mProfileButton.setBackgroundTintList(AppCompatResources.getColorStateList(getContext(),
                backgroundTintResId));
    }

    protected int getBottomGapForRecyclerView(int bottomBarSize) {
        return bottomBarSize;
    }

    protected void hideProfileButton(boolean hide) {
        mHideProfileButton = hide;
        if (hide) {
            mProfileButton.hide();
        } else if (mUserIdManager.isMultiUserProfiles() && shouldShowProfileButton()) {
            mProfileButton.show();
        }
    }

    private static String generateAddButtonString(Context context, int size) {
        final String sizeString = NumberFormat.getInstance(Locale.getDefault()).format(size);
        final String template = context.getString(R.string.picker_add_button_multi_select);
        return TextUtils.expandTemplate(template, sizeString).toString();
    }
}
