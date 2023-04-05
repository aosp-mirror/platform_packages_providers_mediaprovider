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

import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_ALBUM_PHOTOS_TAB;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.util.StringUtils;

import com.google.android.material.snackbar.Snackbar;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Photos tab fragment for showing the photos
 */
public class PhotosTabFragment extends TabFragment {
    private static final int MINIMUM_SPAN_COUNT = 3;
    private static final int GRID_COLUMN_COUNT = 3;
    private static final String FRAGMENT_TAG = "PhotosTabFragment";

    private Category mCategory = Category.DEFAULT;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // After the configuration is changed, if the fragment is now shown, onViewCreated will not
        // be triggered. We need to restore the savedInstanceState in onCreate.
        // E.g. Click the albums -> preview one item -> rotate the device
        if (savedInstanceState != null) {
            mCategory = Category.fromBundle(savedInstanceState);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Context context = getContext();

        // We only add the RECENT header on the PhotosTabFragment with CATEGORY_DEFAULT. In this
        // case, we call this method {loadItems} with null category. When the category is not
        // empty, we don't show the RECENT header.
        final boolean showRecentSection = mCategory.isDefault();

        // We only show the Banners on the PhotosTabFragment with CATEGORY_DEFAULT (Main grid).
        final boolean shouldShowBanners = mCategory.isDefault();
        final LiveData<Boolean> doNotShowBanner = new MutableLiveData<>(false);
        final LiveData<Boolean> showChooseAppBanner = shouldShowBanners
                ? mPickerViewModel.shouldShowChooseAppBannerLiveData() : doNotShowBanner;
        final LiveData<Boolean> showCloudMediaAvailableBanner = shouldShowBanners
                ? mPickerViewModel.shouldShowCloudMediaAvailableBannerLiveData() : doNotShowBanner;
        final LiveData<Boolean> showAccountUpdatedBanner = shouldShowBanners
                ? mPickerViewModel.shouldShowAccountUpdatedBannerLiveData() : doNotShowBanner;
        final LiveData<Boolean> showChooseAccountBanner = shouldShowBanners
                ? mPickerViewModel.shouldShowChooseAccountBannerLiveData() : doNotShowBanner;

        final PhotosTabAdapter adapter = new PhotosTabAdapter(showRecentSection, mSelection,
                mImageLoader, this::onItemClick, this::onItemLongClick, /* lifecycleOwner */ this,
                mPickerViewModel.getCloudMediaProviderAppTitleLiveData(),
                mPickerViewModel.getCloudMediaAccountNameLiveData(), showChooseAppBanner,
                showCloudMediaAvailableBanner, showAccountUpdatedBanner, showChooseAccountBanner,
                mOnChooseAppBannerEventListener, mOnCloudMediaAvailableBannerEventListener,
                mOnAccountUpdatedBannerEventListener, mOnChooseAccountBannerEventListener);

        if (mCategory.isDefault()) {
            setEmptyMessage(R.string.picker_photos_empty_message);
            // Set the pane title for A11y
            view.setAccessibilityPaneTitle(getString(R.string.picker_photos));
            mPickerViewModel.getItems()
                    .observe(this, itemList -> onChangeMediaItems(itemList, adapter));
        } else {
            setEmptyMessage(R.string.picker_album_media_empty_message);
            // Set the pane title for A11y
            view.setAccessibilityPaneTitle(mCategory.getDisplayName(context));
            mPickerViewModel.getCategoryItems(mCategory)
                    .observe(this, itemList -> onChangeMediaItems(itemList, adapter));
        }

        final PhotosTabItemDecoration itemDecoration = new PhotosTabItemDecoration(context);

        final int spacing = getResources().getDimensionPixelSize(R.dimen.picker_photo_item_spacing);
        final int photoSize = getResources().getDimensionPixelSize(R.dimen.picker_photo_size);
        mRecyclerView.setColumnWidth(photoSize + spacing);
        mRecyclerView.setMinimumSpanCount(MINIMUM_SPAN_COUNT);

        setLayoutManager(adapter, GRID_COLUMN_COUNT);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     *
     * @param state Bundle to save state
     */
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        mCategory.toBundle(state);
    }

    @Override
    public void onResume() {
        super.onResume();

        final String title;
        final LayoutModeUtils.Mode layoutMode;
        final boolean shouldHideProfileButton;
        if (mCategory.isDefault()) {
            title = "";
            layoutMode = MODE_PHOTOS_TAB;
            shouldHideProfileButton = false;
        } else {
            title = mCategory.getDisplayName(getContext());
            layoutMode = MODE_ALBUM_PHOTOS_TAB;
            shouldHideProfileButton = true;
        }

        getPickerActivity().updateCommonLayouts(layoutMode, title);
        hideProfileButton(shouldHideProfileButton);
    }

    private void onChangeMediaItems(@NonNull List<Item> itemList,
            @NonNull PhotosTabAdapter adapter) {
        adapter.setMediaItems(itemList);
        // Handle emptyView's visibility
        updateVisibilityForEmptyView(/* shouldShowEmptyView */ itemList.size() == 0);
    }

    private void onItemClick(@NonNull View view) {
        if (mSelection.canSelectMultiple()) {
            final boolean isSelectedBefore = view.isSelected();

            if (isSelectedBefore) {
                mSelection.removeSelectedItem((Item) view.getTag());
            } else {
                if (!mSelection.isSelectionAllowed()) {
                    final int maxCount = mSelection.getMaxSelectionLimit();
                    final CharSequence quantityText =
                        StringUtils.getICUFormatString(
                            getResources(), maxCount, R.string.select_up_to);
                    final String itemCountString = NumberFormat.getInstance(Locale.getDefault())
                        .format(maxCount);
                    final CharSequence message = TextUtils.expandTemplate(quantityText,
                        itemCountString);
                    Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show();
                    return;
                } else {
                    final Item item = (Item) view.getTag();
                    mSelection.addSelectedItem(item);
                }
            }
            view.setSelected(!isSelectedBefore);
            // There is an issue b/223695510 about not selected in Accessibility mode. It only says
            // selected state, but it doesn't say not selected state. Add the not selected only to
            // avoid that it says selected twice.
            view.setStateDescription(isSelectedBefore ? getString(R.string.not_selected) : null);
        } else {
            final Item item = (Item) view.getTag();
            mSelection.setSelectedItem(item);
            getPickerActivity().setResultAndFinishSelf();
        }
    }

    private boolean onItemLongClick(@NonNull View view) {
        final Item item = (Item) view.getTag();
        if (!mSelection.canSelectMultiple()) {
            // In single select mode, if the item is previewed, we set it as selected item. This is
            // will assist in "Add" button click to return all selected items.
            // For multi select, long click only previews the item, and until user selects the item,
            // it doesn't get added to selected items. Also, there is no "Add" button in the preview
            // layout that can return selected items.
            mSelection.setSelectedItem(item);
        }
        mSelection.prepareItemForPreviewOnLongPress(item);
        // Transition to PreviewFragment.
        PreviewFragment.show(getActivity().getSupportFragmentManager(),
                PreviewFragment.getArgsForPreviewOnLongPress());
        return true;
    }

    /**
     * Create the fragment with the category and add it into the FragmentManager
     *
     * @param fm the fragment manager
     * @param category the category
     */
    public static void show(FragmentManager fm, Category category) {
        final FragmentTransaction ft = fm.beginTransaction();
        final PhotosTabFragment fragment = new PhotosTabFragment();
        fragment.mCategory = category;
        ft.replace(R.id.fragment_container, fragment, FRAGMENT_TAG);
        if (!fragment.mCategory.isDefault()) {
            ft.addToBackStack(FRAGMENT_TAG);
        }
        ft.commitAllowingStateLoss();
    }

    /**
     * Get the fragment in the FragmentManager
     *
     * @param fm The fragment manager
     */
    public static Fragment get(FragmentManager fm) {
        return fm.findFragmentByTag(FRAGMENT_TAG);
    }
}
