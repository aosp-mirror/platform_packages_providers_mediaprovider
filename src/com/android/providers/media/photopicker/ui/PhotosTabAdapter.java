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

import static com.android.providers.media.photopicker.ui.ItemsAction.ACTION_CLEAR_AND_UPDATE_LIST;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.util.DateTimeUtils;

import com.bumptech.glide.util.ViewPreloadSizeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
public class PhotosTabAdapter extends TabAdapter {

    private static final int RECENT_MINIMUM_COUNT = 12;
    private final LifecycleOwner mLifecycleOwner;
    private final boolean mShowRecentSection;
    private final OnMediaItemClickListener mOnMediaItemClickListener;
    private final Selection mSelection;
    private final ViewPreloadSizeProvider mPreloadSizeProvider;

    private final View.OnHoverListener mOnMediaItemHoverListener;

    PhotosTabAdapter(boolean showRecentSection,
            @NonNull Selection selection,
            @NonNull ImageLoader imageLoader,
            @NonNull OnMediaItemClickListener onMediaItemClickListener,
            @NonNull LifecycleOwner lifecycleOwner,
            @NonNull LiveData<String> cloudMediaProviderAppTitle,
            @NonNull LiveData<String> cloudMediaAccountName,
            @NonNull LiveData<Boolean> shouldShowChooseAppBanner,
            @NonNull LiveData<Boolean> shouldShowCloudMediaAvailableBanner,
            @NonNull LiveData<Boolean> shouldShowAccountUpdatedBanner,
            @NonNull LiveData<Boolean> shouldShowChooseAccountBanner,
            @NonNull OnBannerEventListener onChooseAppBannerEventListener,
            @NonNull OnBannerEventListener onCloudMediaAvailableBannerEventListener,
            @NonNull OnBannerEventListener onAccountUpdatedBannerEventListener,
            @NonNull OnBannerEventListener onChooseAccountBannerEventListener,
            @NonNull View.OnHoverListener onMediaItemHoverListener,
            @NonNull ViewPreloadSizeProvider preloadSizeProvider) {
        super(imageLoader, lifecycleOwner, cloudMediaProviderAppTitle, cloudMediaAccountName,
                shouldShowChooseAppBanner, shouldShowCloudMediaAvailableBanner,
                shouldShowAccountUpdatedBanner, shouldShowChooseAccountBanner,
                onChooseAppBannerEventListener, onCloudMediaAvailableBannerEventListener,
                onAccountUpdatedBannerEventListener, onChooseAccountBannerEventListener);
        mLifecycleOwner = lifecycleOwner;
        mShowRecentSection = showRecentSection;
        mSelection = selection;
        mOnMediaItemClickListener = onMediaItemClickListener;
        mOnMediaItemHoverListener = onMediaItemHoverListener;
        mPreloadSizeProvider = preloadSizeProvider;
    }

    @NonNull
    @Override
    RecyclerView.ViewHolder createSectionViewHolder(@NonNull ViewGroup viewGroup) {
        final View view = getView(viewGroup, R.layout.item_date_header);
        return new DateHeaderViewHolder(view);
    }

    @NonNull
    @Override
    RecyclerView.ViewHolder createMediaItemViewHolder(@NonNull ViewGroup viewGroup) {
        final View view = getView(viewGroup, R.layout.item_photo_grid);
        final MediaItemGridViewHolder viewHolder =
                new MediaItemGridViewHolder(
                        mLifecycleOwner,
                        view,
                        mImageLoader,
                        mOnMediaItemClickListener,
                        mOnMediaItemHoverListener,
                        mSelection.canSelectMultiple(),
                        mSelection.isSelectionOrdered());
        mPreloadSizeProvider.setView(viewHolder.getThumbnailImageView());
        return viewHolder;
    }

    @Override
    void onBindSectionViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        final DateHeader dateHeader = (DateHeader) getAdapterItem(position);
        final DateHeaderViewHolder dateHeaderVH = (DateHeaderViewHolder) viewHolder;

        dateHeaderVH.bind(dateHeader);
    }

    @Override
    void onBindMediaItemViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        final Item item = (Item) getAdapterItem(position);
        final MediaItemGridViewHolder mediaItemVH = (MediaItemGridViewHolder) viewHolder;

        final boolean isSelected = mSelection.canSelectMultiple()
                && mSelection.isItemSelected(item);

        if (isSelected) {
            mSelection.addCheckedItemIndex(item, position);
        }

        mediaItemVH.bind(item, isSelected);
        if (isSelected && mSelection.isSelectionOrdered()) {
            mediaItemVH.setSelectionOrder(mSelection.getSelectedItemOrder(item));
        }
        // We also need to set Item as a tag so that OnClick/OnLongClickListeners can then
        // retrieve it.
        mediaItemVH.itemView.setTag(item);
    }

    @Override
    boolean isItemTypeSection(int position) {
        return getAdapterItem(position) instanceof DateHeader;
    }

    @Override
    public boolean isItemTypeMediaItem(int position) {
        return getAdapterItem(position) instanceof Item;
    }

    void setMediaItems(@NonNull List<Item> mediaItems) {
        setMediaItems(mediaItems, ACTION_CLEAR_AND_UPDATE_LIST);
    }

    void setMediaItems(@NonNull List<Item> mediaItems, @ItemsAction.Type int action) {
        final List<Object> mediaItemsWithDateHeaders;
        if (!mediaItems.isEmpty()) {
            // We'll have at least one section
            mediaItemsWithDateHeaders = new ArrayList<>(mediaItems.size() + 1);

            // First: show "Recent" section header if needed.
            if (mShowRecentSection) {
                mediaItemsWithDateHeaders.add(new DateHeader(DateHeader.RECENT));
            }

            int recentItemsCount = 0;
            long prevItemDate = -1;
            for (Item mediaItem : mediaItems) {
                final long itemDate = mediaItem.getDateTaken();

                if (mShowRecentSection && recentItemsCount < RECENT_MINIMUM_COUNT) {
                    // The minimum count of items in "Recent" section is not reached yet.
                    recentItemsCount++;
                } else if (!DateTimeUtils.isSameDate(prevItemDate, itemDate)) {
                    // The dateTaken of these two images are not on the same day: add a new date
                    // header
                    mediaItemsWithDateHeaders.add(new DateHeader(itemDate));
                }

                mediaItemsWithDateHeaders.add(mediaItem);

                prevItemDate = itemDate;
            }
        } else {
            mediaItemsWithDateHeaders = Collections.emptyList();
        }
        setAllItems(mediaItemsWithDateHeaders, action);
    }

    @VisibleForTesting
    static class DateHeader {
        static final int RECENT = -1;
        final long timestamp;

        DateHeader(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    private static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.date_header_title);
        }

        void bind(@NonNull DateHeader dateHeader) {
            if (dateHeader.timestamp == DateHeader.RECENT) {
                title.setText(R.string.recent);
            } else {
                title.setText(DateTimeUtils.getDateHeaderString(dateHeader.timestamp));
            }
        }
    }

    interface OnMediaItemClickListener {
        void onItemClick(@NonNull View view, int position, MediaItemGridViewHolder viewHolder);

        boolean onItemLongClick(@NonNull View view, int position);
    }
}
