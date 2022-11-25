/*
 * Copyright (C) 2022 The Android Open Source Project
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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;

/**
 * Adapts from model to something RecyclerView understands.
 */
abstract class TabAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int ITEM_TYPE_BANNER = 0;
    // Date header sections for "Photos" tab
    static final int ITEM_TYPE_SECTION = 1;
    // Media items (a.k.a. Items) for "Photos" tab, Albums (a.k.a. Categories) for "Albums" tab
    private static final int ITEM_TYPE_MEDIA_ITEM = 2;

    boolean mShowBanner;

    @NonNull
    @Override
    public final RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        switch (viewType) {
            case ITEM_TYPE_BANNER:
                return createBannerViewHolder(viewGroup);
            case ITEM_TYPE_SECTION:
                return createSectionViewHolder(viewGroup);
            case ITEM_TYPE_MEDIA_ITEM:
                return createMediaItemViewHolder(viewGroup);
            default:
                throw new IllegalArgumentException("Unknown item view type " + viewType);
        }
    }

    @Override
    public final void onBindViewHolder(@NonNull RecyclerView.ViewHolder itemHolder, int position) {
        final int itemViewType = getItemViewType(position);
        switch (itemViewType) {
            case ITEM_TYPE_BANNER:
                onBindBannerViewHolder(itemHolder);
                break;
            case ITEM_TYPE_SECTION:
                onBindSectionViewHolder(itemHolder, position);
                break;
            case ITEM_TYPE_MEDIA_ITEM:
                onBindMediaItemViewHolder(itemHolder, position);
                break;
            default:
                throw new IllegalArgumentException("Unknown item view type " + itemViewType);
        }
    }

    @Override
    public final int getItemCount() {
        return getBannerCount() + getSectionCount() + getMediaItemCount();
    }

    @Override
    public final int getItemViewType(int position) {
        if (isItemTypeBanner(position)) {
            return ITEM_TYPE_BANNER;
        } else if (isItemTypeSection(position)) {
            return ITEM_TYPE_SECTION;
        } else if (isItemTypeMediaItem(position)) {
            return ITEM_TYPE_MEDIA_ITEM;
        } else {
            throw new IllegalStateException("Item at position " + position
                    + " is of neither of the defined types");
        }
    }

    private RecyclerView.ViewHolder createBannerViewHolder(@NonNull ViewGroup viewGroup) {
        final View view = getView(viewGroup, R.layout.item_banner);
        return new BannerHolder(view);
    }

    RecyclerView.ViewHolder createSectionViewHolder(@NonNull ViewGroup viewGroup) {
        // no-op: descendants may implement
        return null;
    }

    abstract RecyclerView.ViewHolder createMediaItemViewHolder(@NonNull ViewGroup viewGroup);

    private void onBindBannerViewHolder(@NonNull RecyclerView.ViewHolder itemHolder) {
        // no-op for now
    }

    void onBindSectionViewHolder(@NonNull RecyclerView.ViewHolder itemHolder, int position) {
        // no-op: descendants may implement
    }

    abstract void onBindMediaItemViewHolder(@NonNull RecyclerView.ViewHolder itemHolder,
            int position);

    final int getBannerCount() {
        return (mShowBanner ? 1 : 0);
    }

    int getSectionCount() {
        // Effectively a no-op. Extending classes may (or may not) want to override.
        return 0;
    }

    abstract int getMediaItemCount();

    final boolean isItemTypeBanner(int position) {
        return position == 0 && mShowBanner;
    }

    boolean isItemTypeSection(int position) {
        // no-op: descendants may implement
        return false;
    }

    abstract boolean isItemTypeMediaItem(int position);

    /**
     * Update the banner visibility in tab adapter {@link #mShowBanner}
     */
    final void setShowBanner(boolean showBanner) {
        if (showBanner != mShowBanner) {
            mShowBanner = showBanner;
            notifyDataSetChanged();
        }
    }

    @NonNull
    final View getView(@NonNull ViewGroup viewGroup, int layout) {
        final LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        return inflater.inflate(layout, viewGroup, /* attachToRoot */ false);
    }

    private static class BannerHolder extends RecyclerView.ViewHolder {
        BannerHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
