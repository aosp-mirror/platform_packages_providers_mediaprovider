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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
class PhotosTabAdapter extends TabAdapter {

    private static final int RECENT_MINIMUM_COUNT = 12;

    private final boolean mShowRecentSection;
    private final View.OnClickListener mOnMediaItemClickListener;
    private final View.OnLongClickListener mOnMediaItemLongClickListener;
    private final Selection mSelection;

    PhotosTabAdapter(boolean showRecentSection,
            @NonNull Selection selection,
            @NonNull ImageLoader imageLoader,
            @NonNull View.OnClickListener onMediaItemClickListener,
            @NonNull View.OnLongClickListener onMediaItemLongClickListener,
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
            @NonNull OnBannerEventListener onChooseAccountBannerEventListener) {
        super(imageLoader, lifecycleOwner, cloudMediaProviderAppTitle, cloudMediaAccountName,
                shouldShowChooseAppBanner, shouldShowCloudMediaAvailableBanner,
                shouldShowAccountUpdatedBanner, shouldShowChooseAccountBanner,
                onChooseAppBannerEventListener, onCloudMediaAvailableBannerEventListener,
                onAccountUpdatedBannerEventListener, onChooseAccountBannerEventListener);
        mShowRecentSection = showRecentSection;
        mSelection = selection;
        mOnMediaItemClickListener = onMediaItemClickListener;
        mOnMediaItemLongClickListener = onMediaItemLongClickListener;
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
        view.setOnClickListener(mOnMediaItemClickListener);
        view.setOnLongClickListener(mOnMediaItemLongClickListener);

        return new MediaItemGridViewHolder(view, mImageLoader, mSelection.canSelectMultiple());
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
        final MediaItemGridViewHolder mediaItemVH  = (MediaItemGridViewHolder) viewHolder;

        final boolean isSelected = mSelection.canSelectMultiple()
                && mSelection.isItemSelected(item);
        mediaItemVH.bind(item, isSelected);

        // We also need to set Item as a tag so that OnClick/OnLongClickListeners can then
        // retrieve it.
        mediaItemVH.itemView.setTag(item);
    }

    @Override
    boolean isItemTypeSection(int position) {
        return getAdapterItem(position) instanceof DateHeader;
    }

    @Override
    boolean isItemTypeMediaItem(int position) {
        return getAdapterItem(position) instanceof Item;
    }

    void setMediaItems(@NonNull List<Item> mediaItems) {
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
        setAllItems(mediaItemsWithDateHeaders);

        notifyDataSetChanged();
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
}
