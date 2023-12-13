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

import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_LOAD_NEXT_PAGE;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_REFRESH_ITEMS;
import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_VIEW_CREATED;
import static com.android.providers.media.photopicker.ui.TabAdapter.ITEM_TYPE_MEDIA_ITEM;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_ALBUM_PHOTOS_TAB;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.PaginationParameters;
import com.android.providers.media.photopicker.data.glide.PickerPreloadModelProvider;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;
import com.android.providers.media.util.StringUtils;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.util.ViewPreloadSizeProvider;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.NotNull;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * Photos tab fragment for showing the photos
 */
public class PhotosTabFragment extends TabFragment {
    private static final String TAG = PhotosTabFragment.class.getSimpleName();
    private static final int MINIMUM_SPAN_COUNT = 3;
    private static final int GRID_COLUMN_COUNT = 3;
    private static final String FRAGMENT_TAG = "PhotosTabFragment";

    private Category mCategory = Category.DEFAULT;

    private boolean mIsCurrentPageLoading = false;

    private boolean mAtLeastOnePageLoaded = false;

    private boolean mIsCloudMediaInPhotoPickerEnabled;

    private int mPageSize;
    private PickerPreloadModelProvider mPreloadModelProvider;

    @Nullable
    private RequestManager mGlideRequestManager = null;

    private ProgressBar mProgressBar;
    private TextView mLoadingTextView;
    private ObjectAnimator mObjectAnimator = new ObjectAnimator();
    private int mRecyclerViewTopPadding;
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private final Object mHideProgressBarToken = new Object();

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
        final Context context = requireContext();

        // Init is only required for album content tab fragments when the fragment is not being
        // recreated from a previous state.
        if (savedInstanceState == null && !mCategory.isDefault()) {
            mPickerViewModel.initPhotoPickerData(mCategory);
        }

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

        mIsCloudMediaInPhotoPickerEnabled =
                mPickerViewModel.getConfigStore().isCloudMediaInPhotoPickerEnabled();

        if (savedInstanceState == null) {
            initProgressBar(view);
        }
        mSelection.clearCheckedItemList();

        ViewPreloadSizeProvider viewSizeProvider = new ViewPreloadSizeProvider();

        final PhotosTabAdapter adapter =
                new PhotosTabAdapter(
                        showRecentSection,
                        mSelection,
                        mImageLoader,
                        mOnMediaItemClickListener,
                        this, /* lifecycleOwner */
                        mPickerViewModel.getCloudMediaProviderAppTitleLiveData(),
                        mPickerViewModel.getCloudMediaAccountNameLiveData(),
                        showChooseAppBanner,
                        showCloudMediaAvailableBanner,
                        showAccountUpdatedBanner,
                        showChooseAccountBanner,
                        mOnChooseAppBannerEventListener,
                        mOnCloudMediaAvailableBannerEventListener,
                        mOnAccountUpdatedBannerEventListener,
                        mOnChooseAccountBannerEventListener,
                        mOnMediaItemHoverListener,
                        viewSizeProvider);

        mPreloadModelProvider = new PickerPreloadModelProvider(getContext(), adapter);
        mGlideRequestManager = Glide.with(this);

        RecyclerViewPreloader<Item> preloader =
                new RecyclerViewPreloader<>(
                        Glide.with(getContext()),
                        mPreloadModelProvider,
                        viewSizeProvider,
                        /* maxPreload= */ 8);
        mRecyclerView.addOnScrollListener(preloader);


        // initialise pre-granted items is necessary.
        Intent activityIntent = requireActivity().getIntent();
        mPickerViewModel.initialisePreGrantsIfNecessary(mPickerViewModel.getSelection(),
                activityIntent.getExtras(), MimeFilterUtils.getMimeTypeFilters(activityIntent));

        if (mCategory.isDefault()) {
            mPageSize = mIsCloudMediaInPhotoPickerEnabled
                    ? PaginationParameters.PAGINATION_PAGE_SIZE_ITEMS : -1;
            setEmptyMessage(R.string.picker_photos_empty_message);
            // Set the pane title for A11y
            view.setAccessibilityPaneTitle(getString(R.string.picker_photos));
            // Get items with pagination parameters representing the first page.
            mPickerViewModel.getPaginatedItemsForAction(
                            ACTION_VIEW_CREATED,
                            new PaginationParameters(
                                    mPageSize,
                                    /* dateBeforeMs */ Long.MIN_VALUE,
                                    /* rowId */ -1))
                    .observe(this, itemListResult -> {
                        onChangeMediaItems(itemListResult, adapter);
                    });
        } else {
            mPageSize = mIsCloudMediaInPhotoPickerEnabled
                    ? PaginationParameters.PAGINATION_PAGE_SIZE_ALBUM_ITEMS : -1;
            setEmptyMessage(R.string.picker_album_media_empty_message);
            // Set the pane title for A11y
            view.setAccessibilityPaneTitle(mCategory.getDisplayName(context));
            // Get items with pagination parameters representing the first page.
            mPickerViewModel.getPaginatedCategoryItemsForAction(
                    mCategory,
                            ACTION_VIEW_CREATED,
                            new PaginationParameters(
                                     mPageSize,
                                    /* dateBeforeMs */ Long.MIN_VALUE,
                                    /* rowId */ -1))
                    .observe(this, itemListResult -> {
                        onChangeMediaItems(itemListResult, adapter);
                    });
        }

        final PhotosTabItemDecoration itemDecoration = new PhotosTabItemDecoration(context);

        final int spacing = getResources().getDimensionPixelSize(R.dimen.picker_photo_item_spacing);
        final int photoSize = getResources().getDimensionPixelSize(R.dimen.picker_photo_size);
        mRecyclerView.setColumnWidth(photoSize + spacing);
        mRecyclerView.setMinimumSpanCount(MINIMUM_SPAN_COUNT);

        setLayoutManager(context, adapter, GRID_COLUMN_COUNT);
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.addItemDecoration(itemDecoration);

        mRecyclerView.addRecyclerListener(
                new RecyclerView.RecyclerListener() {
                    @Override
                    public void onViewRecycled(RecyclerView.ViewHolder holder) {
                        if (mGlideRequestManager != null
                                && holder.getItemViewType() == ITEM_TYPE_MEDIA_ITEM) {
                            // This cast is safe as we've already checked the view type is
                            MediaItemGridViewHolder vh = (MediaItemGridViewHolder) holder;
                            // Cancel pending glide load requests on recycling, to prevent a large
                            // backlog of requests building up in the event of large scrolls.
                            cancelGlideLoadForViewHolder(vh);
                            vh.release();
                        }
                    }
                });
        mRecyclerView.setItemViewCacheSize(10);

        if (mIsCloudMediaInPhotoPickerEnabled) {
            setOnScrollListenerForRecyclerView();
        }

        // uncheck the unavailable items at UI those are no longer available in the selection list
        requirePickerActivity().isItemPhotoGridViewChanged()
                .observe(this, isItemViewChanged -> {
                    if (isItemViewChanged) {
                        // To re-bind the view just to uncheck the unavailable media items at UI
                        // Size of mCheckItems is going to be constant ( Iterating over mCheckItems
                        // is not a heavy operation)
                        for (Integer index : mSelection.getCheckedItemsIndexes()) {
                            adapter.notifyItemChanged(index);
                        }
                    }
                });
    }

    private void initProgressBar(@NonNull View view) {
        // Check feature flag for cloud media and if it is not true then hide progress bar and
        // loading text.
        if (mIsCloudMediaInPhotoPickerEnabled) {
            mLoadingTextView = view.findViewById(R.id.loading_text_view);
            mProgressBar = view.findViewById(R.id.progress_bar);
            mRecyclerViewTopPadding = getResources().getDimensionPixelSize(
                    R.dimen.picker_recycler_view_top_padding);
            if (mCategory == Category.DEFAULT) {
                mPickerViewModel.isSyncInProgress().observe(this, inProgress -> {
                    if (inProgress) {
                        bringProgressBarAndLoadingTextInView();
                    }
                });
            } else {
                bringProgressBarAndLoadingTextInView();
            }
        }
    }
    private void setOnScrollListenerForRecyclerView() {
        mRecyclerView.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(@NonNull @NotNull RecyclerView recyclerView, int dx,
                            int dy) {
                        super.onScrolled(recyclerView, dx, dy);

                        // check to ensure that the current page is not still loading and the last
                        // page has not been loaded.
                        if (!mIsCurrentPageLoading) {
                            LinearLayoutManager layoutManager =
                                    (LinearLayoutManager) mRecyclerView.getLayoutManager();

                            assert layoutManager != null;
                            // Total items visible at the screen at any current time.
                            int visibleItemCount = layoutManager.getChildCount();
                            // Total items in the layout.
                            int totalItemCount = layoutManager.getItemCount();
                            // The position of the first visible view
                            int firstVisibleItemPosition =
                                    layoutManager.findFirstVisibleItemPosition();

                            // If the number of items have exceeded the threshold, a call will be
                            // triggered to load the next page.
                            int thresholdNumberOfItems = totalItemCount - mPageSize;
                            if (visibleItemCount + firstVisibleItemPosition
                                    >= thresholdNumberOfItems
                                    && firstVisibleItemPosition >= 0
                            ) {

                                Log.d(FRAGMENT_TAG, "Scrolled beyond page threshold, sending a"
                                        + " call to load the next page.");

                                // setting this to true ensures that only one call is sent on
                                // crossing the threshold and only required number of pages are
                                // loaded.
                                mIsCurrentPageLoading = true;
                                if (mCategory.isDefault()) {
                                    mPickerViewModel.getPaginatedItemsForAction(
                                            ACTION_LOAD_NEXT_PAGE,
                                            null);
                                } else {
                                    mPickerViewModel.getPaginatedCategoryItemsForAction(
                                            mCategory,
                                            ACTION_LOAD_NEXT_PAGE,
                                            null);
                                }
                            }
                        }

                    }
                });

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
            title = mCategory.getDisplayName(requireContext());
            layoutMode = MODE_ALBUM_PHOTOS_TAB;
            shouldHideProfileButton = true;
        }
        requirePickerActivity().updateCommonLayouts(layoutMode, title);

        hideProfileButton(shouldHideProfileButton);

        if (mIsCloudMediaInPhotoPickerEnabled
                && mCategory == Category.DEFAULT
                && mAtLeastOnePageLoaded) {
            // mAtLeastOnePageLoaded is checked to avoid calling this method while the view is
            // being created
            LinearLayoutManager layoutManager =
                    (LinearLayoutManager) mRecyclerView.getLayoutManager();

            if (layoutManager != null) {
                int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();
                mPickerViewModel.getPaginatedItemsForAction(
                        ACTION_REFRESH_ITEMS,
                        new PaginationParameters(firstVisibleItemPosition
                                + PaginationParameters.PAGINATION_PAGE_SIZE_ITEMS,
                                /*dateBeforeMs*/ Long.MIN_VALUE, -1));
            }
        }
    }

    private void onChangeMediaItems(@NonNull PickerViewModel.PaginatedItemsResult itemList,
            @NonNull PhotosTabAdapter adapter) {
        Objects.requireNonNull(itemList);
        if (isClearGridAction(itemList)) {
            adapter.setMediaItems(new ArrayList<>(), itemList.getAction());
            updateVisibilityForEmptyView(false);
        } else {
            adapter.setMediaItems(itemList.getItems(), itemList.getAction());
            // Handle emptyView's visibility
            boolean shouldShowEmptyView = (itemList.getItems().size() == 0);
            updateVisibilityForEmptyView(shouldShowEmptyView);
            if (shouldShowEmptyView) {
                mPickerViewModel.setEmptyPageDisplayed(true);
            }
        }
        mIsCurrentPageLoading = false;
        mAtLeastOnePageLoaded = true;
        hideProgressBarAndLoadingText();
    }

    private boolean isClearGridAction(@NonNull PickerViewModel.PaginatedItemsResult itemList) {
        return itemList.getItems() != null
                && itemList.getItems().size() == 1
                && itemList.getItems().get(0).getId().equals("EMPTY_VIEW");
    }

    private final PhotosTabAdapter.OnMediaItemClickListener mOnMediaItemClickListener =
            new PhotosTabAdapter.OnMediaItemClickListener() {
                @Override
                public void onItemClick(
                        @NonNull View view, int position, MediaItemGridViewHolder viewHolder) {

                    if (mSelection.canSelectMultiple()) {
                        final boolean isSelectedBefore =
                                mSelection.isItemSelected((Item) view.getTag())
                                        && view.isSelected();

                        Item item = (Item) view.getTag();
                        if (isSelectedBefore) {
                            if (mSelection.isSelectionOrdered()) {
                                viewHolder.setSelectionOrder(null);
                            }
                            mSelection.removeSelectedItem((Item) view.getTag());
                            mSelection.removeCheckedItemIndex((Item) view.getTag());
                        } else {
                            mSelection.addCheckedItemIndex((Item) view.getTag(), position);
                            if (!mSelection.isSelectionAllowed()) {
                                final int maxCount = mSelection.getMaxSelectionLimit();
                                final CharSequence quantityText =
                                        StringUtils.getICUFormatString(
                                                getResources(), maxCount, R.string.select_up_to);
                                final String itemCountString =
                                        NumberFormat.getInstance(Locale.getDefault())
                                                .format(maxCount);
                                final CharSequence message =
                                        TextUtils.expandTemplate(quantityText, itemCountString);
                                Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show();
                                return;
                            } else {
                                mSelection.addSelectedItem(item);
                                if (mSelection.isSelectionOrdered()) {
                                    viewHolder.setSelectionOrder(
                                            mSelection.getSelectedItemOrder(item));
                                }
                                mPickerViewModel.logMediaItemSelected(item, mCategory, position);
                            }
                        }
                        view.setSelected(!isSelectedBefore);

                        // There is an issue b/223695510 about not selected in Accessibility mode.
                        // It only says selected state, but it doesn't say not selected state.
                        // Add the not selected only to avoid that it says selected twice.
                        view.setStateDescription(
                                isSelectedBefore ? getString(R.string.not_selected) : null);
                    } else {
                        final Item item = (Item) view.getTag();
                        mSelection.setSelectedItem(item);
                        mPickerViewModel.logMediaItemSelected(item, mCategory, position);
                        try {
                            requirePickerActivity().setResultAndFinishSelf();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                        }
                    }
                }

                @Override
                public boolean onItemLongClick(@NonNull View view, int position) {
                    final Item item = (Item) view.getTag();
                    if (!mSelection.canSelectMultiple()) {
                        // In single select mode, if the item is previewed, we set it as selected
                        // item. This assists in "Add" button click to return all selected items.
                        // For multi select, long click only previews the item, and until user
                        // selects the item, it doesn't get added to selected items. Also, there is
                        // no "Add" button in the preview layout that can return selected items.
                        mSelection.setSelectedItem(item);
                    }
                    mSelection.prepareItemForPreviewOnLongPress(item);
                    mPickerViewModel.logMediaItemPreviewed(item, mCategory, position);

                    try {
                        // Transition to PreviewFragment.
                        PreviewFragment.show(
                                requireActivity().getSupportFragmentManager(),
                                PreviewFragment.getArgsForPreviewOnLongPress());
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Fragment is likely not attached to an activity. ", e);
                    }

                    // Consume the long click so that it doesn't propagate in the View hierarchy.
                    return true;
                }
            };

    public View.OnHoverListener mOnMediaItemHoverListener = (v, event) -> {
        // When a cursor is hovered over an item the item should appear selected and when the
        // cursor moves out of the bounds of the view, it should go back to being unselected.
        if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            v.setSelected(true);
        } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
            if (!mSelection.isItemSelected((Item) v.getTag())) {
                v.setSelected(false);
            }
        }
        return true;
    };

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

    /**
     * Hides progress bar and the loading photos message.
     * <p>This is executed with a delay of 0.6ms.
     * This is done so that for the cases where the loading happens very quickly the user will not
     * see the progressBar flicker.</p>
     *
     * <p>This results in progressBar and loadingText to remain in view for loadingTime + 0.6ms.</p>
     */
    private synchronized void hideProgressBarAndLoadingText() {
        if (mProgressBar != null
                && mLoadingTextView != null
                && mProgressBar.getVisibility() == View.VISIBLE
                && mLoadingTextView.getVisibility() == View.VISIBLE) {
            // clear previous calls, extra caution.
            mMainThreadHandler.removeCallbacksAndMessages(mHideProgressBarToken);
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    if (mProgressBar != null
                            && mLoadingTextView != null
                            && mProgressBar.getVisibility() == View.VISIBLE
                            && mLoadingTextView.getVisibility() == View.VISIBLE) {
                        mProgressBar.setVisibility(View.GONE);
                        mLoadingTextView.setVisibility(View.GONE);
                        // Move recyclerView up to cover up the space taken up by progressBar and
                        // loadingTest.
                        if (mRecyclerView != null
                                && mRecyclerView.getVisibility() == View.VISIBLE) {
                            mObjectAnimator.ofFloat(
                                            mRecyclerView,
                                            /* property name */ "y",
                                            /* final position */0f)
                                    .setDuration(300).start();
                        }
                    }
                }
            };
            // With this runnable the hiding of progress bar is delayed by 600ms.
            mMainThreadHandler.postDelayed(runnable, mHideProgressBarToken, /* delay duration */
                    600);
        }
    }

    private void bringProgressBarAndLoadingTextInView() {
        if (mIsCloudMediaInPhotoPickerEnabled) {
            if (mObjectAnimator != null) {
                // stop any pending/ongoing animations.
                mObjectAnimator.cancel();
            }
            if (mRecyclerView.getVisibility() == View.VISIBLE) {
                // move recycler view down to make space for progress bar and loading text.
                mObjectAnimator.ofFloat(
                                mRecyclerView,
                                /* property name */ "y",
                                /* final position */mRecyclerViewTopPadding)
                        .setDuration(1).start();
            }
            // bring progressBar and Loading text in view.
            mLoadingTextView.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Attempts to cancel any outstanding Glide requests for the given ViewHolder.
     *
     * @param holder The View holder in the RecyclerView to cancel requests for.
     */
    private void cancelGlideLoadForViewHolder(MediaItemGridViewHolder vh) {
        // Attempt to clear the potential pending load out of glide's request
        // manager.
        mGlideRequestManager.clear(vh.getThumbnailImageView());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMainThreadHandler.removeCallbacksAndMessages(mHideProgressBarToken);
        mGlideRequestManager = null;
    }
}
