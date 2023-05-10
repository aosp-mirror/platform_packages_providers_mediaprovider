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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from model to something RecyclerView understands.
 */
@VisibleForTesting
public abstract class TabAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    @VisibleForTesting
    public static final int ITEM_TYPE_BANNER = 0;
    // Date header sections for "Photos" tab
    static final int ITEM_TYPE_SECTION = 1;
    // Media items (a.k.a. Items) for "Photos" tab, Albums (a.k.a. Categories) for "Albums" tab
    private static final int ITEM_TYPE_MEDIA_ITEM = 2;

    @NonNull final ImageLoader mImageLoader;
    @NonNull private final LiveData<String> mCloudMediaProviderAppTitle;
    @NonNull private final LiveData<String> mCloudMediaAccountName;

    @Nullable private Banner mBanner;
    @Nullable private OnBannerEventListener mOnBannerEventListener;
    /**
     * Combined list of Sections and Media Items, ordered based on their position in the view.
     *
     * (List of {@link com.android.providers.media.photopicker.ui.PhotosTabAdapter.DateHeader} and
     * {@link com.android.providers.media.photopicker.data.model.Item} for the "Photos" tab)
     *
     * (List of {@link com.android.providers.media.photopicker.data.model.Category} for the "Albums"
     * tab)
     */
    @NonNull
    private final List<Object> mAllItems = new ArrayList<>();

    TabAdapter(@NonNull ImageLoader imageLoader, @NonNull LifecycleOwner lifecycleOwner,
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
        mImageLoader = imageLoader;
        mCloudMediaProviderAppTitle = cloudMediaProviderAppTitle;
        mCloudMediaAccountName = cloudMediaAccountName;

        shouldShowChooseAppBanner.observe(lifecycleOwner, isVisible ->
                setBannerVisibility(isVisible, Banner.CHOOSE_APP, onChooseAppBannerEventListener));
        shouldShowCloudMediaAvailableBanner.observe(lifecycleOwner, isVisible ->
                setBannerVisibility(isVisible, Banner.CLOUD_MEDIA_AVAILABLE,
                        onCloudMediaAvailableBannerEventListener));
        shouldShowAccountUpdatedBanner.observe(lifecycleOwner, isVisible ->
                setBannerVisibility(isVisible, Banner.ACCOUNT_UPDATED,
                        onAccountUpdatedBannerEventListener));
        shouldShowChooseAccountBanner.observe(lifecycleOwner, isVisible ->
                setBannerVisibility(isVisible, Banner.CHOOSE_ACCOUNT,
                        onChooseAccountBannerEventListener));
    }

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
        return getBannerCount() + getAllItemsCount();
    }

    @Override
    public final int getItemViewType(int position) {
        if (position < 0) {
            throw new IllegalStateException("Get item view type for negative position " + position);
        }
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

    @NonNull
    private RecyclerView.ViewHolder createBannerViewHolder(@NonNull ViewGroup viewGroup) {
        final View view = getView(viewGroup, R.layout.item_banner);
        return new BannerHolder(view);
    }

    @NonNull
    RecyclerView.ViewHolder createSectionViewHolder(@NonNull ViewGroup viewGroup) {
        // A descendant must override this method if and only if {@link isItemTypeSection} is
        // implemented and may return {@code true} for them.
        throw new IllegalStateException("Attempt to create an unimplemented section view holder");
    }

    @NonNull
    abstract RecyclerView.ViewHolder createMediaItemViewHolder(@NonNull ViewGroup viewGroup);

    private void onBindBannerViewHolder(@NonNull RecyclerView.ViewHolder itemHolder) {
        final BannerHolder bannerVH = (BannerHolder) itemHolder;
        bannerVH.bind(mBanner, mCloudMediaProviderAppTitle.getValue(),
                mCloudMediaAccountName.getValue(), mOnBannerEventListener);
    }

    void onBindSectionViewHolder(@NonNull RecyclerView.ViewHolder itemHolder, int position) {
        // no-op: descendants may implement
    }

    abstract void onBindMediaItemViewHolder(@NonNull RecyclerView.ViewHolder itemHolder,
            int position);

    private int getBannerCount() {
        return mBanner != null ? 1 : 0;
    }

    private int getAllItemsCount() {
        return mAllItems.size();
    }

    private boolean isItemTypeBanner(int position) {
        return position > -1 && position < getBannerCount();
    }

    boolean isItemTypeSection(int position) {
        // no-op: descendants may implement
        return false;
    }

    abstract boolean isItemTypeMediaItem(int position);

    /**
     * Update the banner visibility in tab adapter
     */
    private void setBannerVisibility(boolean isVisible, @NonNull Banner banner,
            @NonNull OnBannerEventListener onBannerEventListener) {
        if (isVisible) {
            if (mBanner == null) {
                mBanner = banner;
                mOnBannerEventListener = onBannerEventListener;
                notifyItemInserted(/* position */ 0);
                mOnBannerEventListener.onBannerAdded();
            } else {
                mBanner = banner;
                mOnBannerEventListener = onBannerEventListener;
                notifyItemChanged(/* position */ 0);
            }
        } else if (mBanner == banner) {
            mBanner = null;
            mOnBannerEventListener = null;
            notifyItemRemoved(/* position */ 0);
        }
    }

    /**
     * Update the List of all items (excluding the banner) in tab adapter {@link #mAllItems}
     */
    protected final void setAllItems(@NonNull List<?> items) {
        mAllItems.clear();
        mAllItems.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    final Object getAdapterItem(int position) {
        if (position < 0) {
            throw new IllegalStateException("Get adapter item for negative position " + position);
        }
        if (isItemTypeBanner(position)) {
            return mBanner;
        }

        final int effectiveItemIndex = position - getBannerCount();
        return mAllItems.get(effectiveItemIndex);
    }

    @NonNull
    final View getView(@NonNull ViewGroup viewGroup, int layout) {
        final LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        return inflater.inflate(layout, viewGroup, /* attachToRoot */ false);
    }

    private static class BannerHolder extends RecyclerView.ViewHolder {
        final TextView mPrimaryText;
        final TextView mSecondaryText;
        final Button mDismissButton;
        final Button mActionButton;

        BannerHolder(@NonNull View itemView) {
            super(itemView);
            mPrimaryText = itemView.findViewById(R.id.banner_primary_text);
            mSecondaryText = itemView.findViewById(R.id.banner_secondary_text);
            mDismissButton = itemView.findViewById(R.id.dismiss_button);
            mActionButton = itemView.findViewById(R.id.action_button);
        }

        void bind(@NonNull Banner banner, String cloudAppName, String cloudUserAccount,
                @NonNull OnBannerEventListener onBannerEventListener) {
            final Context context = itemView.getContext();

            itemView.setOnClickListener(v -> onBannerEventListener.onBannerClick());

            mPrimaryText.setText(banner.getPrimaryText(context, cloudAppName));
            mSecondaryText.setText(banner.getSecondaryText(context, cloudAppName,
                    cloudUserAccount));

            mDismissButton.setOnClickListener(v -> onBannerEventListener.onDismissButtonClick());

            if (banner.mActionButtonText != -1) {
                mActionButton.setText(banner.mActionButtonText);
                mActionButton.setVisibility(View.VISIBLE);
                mActionButton.setOnClickListener(v -> onBannerEventListener.onActionButtonClick());
            } else {
                mActionButton.setVisibility(View.GONE);
            }
        }
    }

    private enum Banner {
        // TODO(b/274426228): Replace `CLOUD_MEDIA_AVAILABLE` `mActionButtonText` from `-1` to
        //  `R.string.picker_banner_cloud_change_account_button`, post change cloud account
        //  functionality implementation from the Picker settings (b/261999521).
        CLOUD_MEDIA_AVAILABLE(R.string.picker_banner_cloud_first_time_available_title,
                R.string.picker_banner_cloud_first_time_available_desc, /* no action button */ -1),
        ACCOUNT_UPDATED(R.string.picker_banner_cloud_account_changed_title,
                R.string.picker_banner_cloud_account_changed_desc, /* no action button */ -1),
        // TODO(b/274426228): Replace `CHOOSE_ACCOUNT` `mActionButtonText` from `-1` to
        //  `R.string.picker_banner_cloud_choose_account_button`, post change cloud account
        //  functionality implementation from the Picker settings (b/261999521).
        CHOOSE_ACCOUNT(R.string.picker_banner_cloud_choose_account_title,
                R.string.picker_banner_cloud_choose_account_desc, /* no action button */ -1),
        CHOOSE_APP(R.string.picker_banner_cloud_choose_app_title,
                R.string.picker_banner_cloud_choose_app_desc,
                R.string.picker_banner_cloud_choose_app_button);

        @StringRes final int mPrimaryText;
        @StringRes final int mSecondaryText;
        @StringRes final int mActionButtonText;

        Banner(int primaryText, int secondaryText, int actionButtonText) {
            mPrimaryText = primaryText;
            mSecondaryText = secondaryText;
            mActionButtonText = actionButtonText;
        }

        String getPrimaryText(@NonNull Context context, String appName) {
            switch (this) {
                case CLOUD_MEDIA_AVAILABLE:
                    // fall-through
                case CHOOSE_APP:
                    return context.getString(mPrimaryText);
                case ACCOUNT_UPDATED:
                    // fall-through
                case CHOOSE_ACCOUNT:
                    return context.getString(mPrimaryText, appName);
                default:
                    throw new IllegalStateException("Unknown banner type " + name());
            }
        }

        String getSecondaryText(@NonNull Context context, String appName, String userAccount) {
            switch (this) {
                case CLOUD_MEDIA_AVAILABLE:
                    return context.getString(mSecondaryText, appName, userAccount);
                case ACCOUNT_UPDATED:
                    return context.getString(mSecondaryText, userAccount);
                case CHOOSE_ACCOUNT:
                    return context.getString(mSecondaryText, appName);
                case CHOOSE_APP:
                    return context.getString(mSecondaryText);
                default:
                    throw new IllegalStateException("Unknown banner type " + name());
            }
        }
    }

    interface OnBannerEventListener {
        void onActionButtonClick();

        void onDismissButtonClick();

        default void onBannerClick() {
            onActionButtonClick();
        }

        void onBannerAdded();
    }
}
