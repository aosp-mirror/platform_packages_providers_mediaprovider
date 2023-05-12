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

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Category;

import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
class AlbumsTabAdapter extends TabAdapter {

    private final OnAlbumClickListener mOnAlbumClickListener;
    private final boolean mHasMimeTypeFilter;

    AlbumsTabAdapter(@NonNull ImageLoader imageLoader,
            @NonNull OnAlbumClickListener onAlbumClickListener,
            boolean hasMimeTypeFilter,
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
        mOnAlbumClickListener = onAlbumClickListener;
        mHasMimeTypeFilter = hasMimeTypeFilter;
    }

    @NonNull
    @Override
    RecyclerView.ViewHolder createMediaItemViewHolder(@NonNull ViewGroup viewGroup) {
        final View view = getView(viewGroup, R.layout.item_album_grid);
        return new AlbumGridHolder(view, mImageLoader, mHasMimeTypeFilter, mOnAlbumClickListener);
    }

    @Override
    void onBindMediaItemViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        final Category category = (Category) getAdapterItem(position);
        final AlbumGridHolder albumGridVH = (AlbumGridHolder) viewHolder;
        albumGridVH.bind(category);
    }

    @Override
    boolean isItemTypeMediaItem(int position) {
        return getAdapterItem(position) instanceof Category;
    }

    void updateCategoryList(@NonNull List<Category> categoryList) {
        setAllItems(categoryList);
    }

    interface OnAlbumClickListener {
        void onAlbumClick(@NonNull Category category);
    }
}
