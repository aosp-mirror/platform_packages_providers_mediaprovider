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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.GridLayoutManager;
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
class PhotosTabAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int ITEM_TYPE_DATE_HEADER = 0;
    static final int ITEM_TYPE_PHOTO = 1;

    private static final int RECENT_MINIMUM_COUNT = 12;

    private final boolean mShowRecentSection;
    /**
     * List of {@link com.android.providers.media.photopicker.data.model.Item Item} and
     * {@link DateHeader} objects.
     */
    private List<Object> mItems = new ArrayList<>();
    private final ImageLoader mImageLoader;
    private final View.OnClickListener mOnMediaItemClickListener;
    private final View.OnLongClickListener mOnMediaItemLongClickListener;
    private final Selection mSelection;

    PhotosTabAdapter(boolean showRecentSection,
            @NonNull Selection selection,
            @NonNull ImageLoader imageLoader,
            @NonNull View.OnClickListener onMediaItemClickListener,
            @NonNull View.OnLongClickListener onMediaItemLongClickListener) {
        mShowRecentSection = showRecentSection;
        mImageLoader = imageLoader;
        mSelection = selection;
        mOnMediaItemClickListener = onMediaItemClickListener;
        mOnMediaItemLongClickListener = onMediaItemLongClickListener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        if (viewType == ITEM_TYPE_DATE_HEADER) {
            final View view = inflater.inflate(R.layout.item_date_header, viewGroup, false);
            return new DateHeaderViewHolder(view);
        } else /* viewType == ITEM_TYPE_PHOTO */ {
            final View view = inflater.inflate(R.layout.item_photo_grid, viewGroup, false);
            view.setOnClickListener(mOnMediaItemClickListener);
            view.setOnLongClickListener(mOnMediaItemLongClickListener);

            return new MediaItemGridViewHolder(view, mImageLoader, mSelection.canSelectMultiple());
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (viewHolder instanceof DateHeaderViewHolder) {
            final DateHeader dateHeader = (DateHeader) getAdapterItem(position);
            final DateHeaderViewHolder dateHeaderVH = (DateHeaderViewHolder) viewHolder;

            dateHeaderVH.bind(dateHeader);
        } else /* viewHolder instanceof MediaItemGridViewHolder */ {
            final Item item = (Item) getAdapterItem(position);
            final MediaItemGridViewHolder mediaItemVH  = (MediaItemGridViewHolder) viewHolder;

            final boolean isSelected = mSelection.canSelectMultiple()
                    && mSelection.isItemSelected(item);
            mediaItemVH.bind(item, isSelected);

            // We also need to set Item as a tag so that OnClick/OnLongClickListeners can then
            // retrieve it.
            mediaItemVH.itemView.setTag(item);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (getAdapterItem(position) instanceof Item) {
            return ITEM_TYPE_PHOTO;
        } else /* instanceof DateHeader */ {
            return ITEM_TYPE_DATE_HEADER;
        }
    }

    void setMediaItems(@NonNull List<Item> mediaItems) {
        if (!mediaItems.isEmpty()) {
            mItems = new ArrayList<>(mediaItems.size() + 1); // We'll have at least one section

            // First: show "Recent" section header if needed.
            if (mShowRecentSection) {
                mItems.add(new DateHeader(DateHeader.RECENT));
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
                    mItems.add(new DateHeader(itemDate));
                }

                mItems.add(mediaItem);

                prevItemDate = itemDate;
            }
        } else {
            mItems = Collections.emptyList();
        }

        notifyDataSetChanged();
    }

    @Nullable
    @VisibleForTesting
    Object getAdapterItem(int position) {
        return mItems.get(position);
    }

    @NonNull
    public GridLayoutManager.SpanSizeLookup createSpanSizeLookup(
            @NonNull GridLayoutManager layoutManager) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int itemViewType = getItemViewType(position);
                // For the item view type is ITEM_TYPE_DATE_HEADER, it is full
                // span, return the span count of the layoutManager.
                if (itemViewType == ITEM_TYPE_DATE_HEADER ) {
                    return layoutManager.getSpanCount();
                } else {
                    return 1;
                }
            }
        };
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
