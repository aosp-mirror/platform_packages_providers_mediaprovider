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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.util.AccentColorResources;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

/**
 * {@link RecyclerView.ViewHolder} of a {@link View} representing a (media) {@link Item} (a photo or
 * a video).
 */
class MediaItemGridViewHolder extends RecyclerView.ViewHolder {
    private final LifecycleOwner mLifecycleOwner;
    private final ImageLoader mImageLoader;
    private final ImageView mIconThumb;
    private final ImageView mIconGif;
    private final ImageView mIconMotionPhoto;
    private final View mVideoBadgeContainer;
    private final TextView mVideoDuration;
    private final View mOverlayGradient;
    private final boolean mCanSelectMultiple;
    private final boolean mShowOrderedSelectionLabel;
    private final TextView mSelectedOrderText;
    private LiveData<Integer> mSelectionOrder;
    private final ImageView mCheckIcon;

    private final View.OnHoverListener mOnMediaItemHoverListener;
    private final PhotosTabAdapter.OnMediaItemClickListener mOnMediaItemClickListener;
    private final PickerViewModel mPickerViewModel;

    MediaItemGridViewHolder(
            @NonNull LifecycleOwner lifecycleOwner,
            @NonNull View itemView,
            @NonNull ImageLoader imageLoader,
            @NonNull PhotosTabAdapter.OnMediaItemClickListener onMediaItemClickListener,
            View.OnHoverListener onMediaItemHoverListener,
            PickerViewModel pickerViewModel,
            boolean canSelectMultiple,
            boolean isOrderedSelection) {
        super(itemView);
        mLifecycleOwner = lifecycleOwner;
        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mIconGif = itemView.findViewById(R.id.icon_gif);
        mIconMotionPhoto = itemView.findViewById(R.id.icon_motion_photo);
        mVideoBadgeContainer = itemView.findViewById(R.id.video_container);
        mVideoDuration = mVideoBadgeContainer.findViewById(R.id.video_duration);
        mOverlayGradient = itemView.findViewById(R.id.overlay_gradient);
        mImageLoader = imageLoader;
        mOnMediaItemClickListener = onMediaItemClickListener;
        mCanSelectMultiple = canSelectMultiple;
        mShowOrderedSelectionLabel = isOrderedSelection;
        mOnMediaItemHoverListener = onMediaItemHoverListener;
        mPickerViewModel = pickerViewModel;
        mSelectedOrderText = itemView.findViewById(R.id.selected_order);
        mCheckIcon = itemView.findViewById(R.id.icon_check);
        mCheckIcon.setVisibility(
                (mCanSelectMultiple && !mShowOrderedSelectionLabel) ? VISIBLE : GONE);
        mSelectedOrderText.setVisibility(
                (mCanSelectMultiple && mShowOrderedSelectionLabel) ? VISIBLE : GONE);

        if (mPickerViewModel.getPickerAccentColorParameters().isCustomPickerColorSet()) {
            setCustomSelectedMediaIconColors(
                    mPickerViewModel.getPickerAccentColorParameters().getPickerAccentColor(),
                    mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                            AccentColorResources.SURFACE_CONTAINER_COLOR_LIGHT,
                            AccentColorResources.SURFACE_CONTAINER_COLOR_DARK
                    ));
        }
    }

    public void bind(@NonNull Item item, boolean isSelected) {
        int position = getAbsoluteAdapterPosition();
        itemView.setOnClickListener(v -> mOnMediaItemClickListener.onItemClick(v, position, this));
        itemView.setOnLongClickListener(v ->
                mOnMediaItemClickListener.onItemLongClick(v, position));
        itemView.setOnHoverListener(mOnMediaItemHoverListener);

        mImageLoader.loadPhotoThumbnail(item, mIconThumb);

        mIconGif.setVisibility(item.isGifOrAnimatedWebp() ? VISIBLE : GONE);
        mIconMotionPhoto.setVisibility(item.isMotionPhoto() ? VISIBLE : GONE);

        if (item.isVideo()) {
            mVideoBadgeContainer.setVisibility(VISIBLE);
            mVideoDuration.setText(item.getDurationText());
        } else {
            mVideoBadgeContainer.setVisibility(GONE);
        }

        if (showShowOverlayGradient(item)) {
            mOverlayGradient.setVisibility(VISIBLE);
        } else {
            mOverlayGradient.setVisibility(GONE);
        }

        final Context context = getContext();
        itemView.setContentDescription(item.getContentDescription(context));

        if (mCanSelectMultiple) {
            itemView.setSelected(isSelected);
            mSelectedOrderText.setText("");
            // There is an issue b/223695510 about not selected in Accessibility mode. It only
            // says selected state, but it doesn't say not selected state. Add the not selected
            // only to avoid that it says selected twice.
            itemView.setStateDescription(
                    isSelected ? null : context.getString(R.string.not_selected));
        }
    }

    /** Sets the LiveData selection order for the current grid item view. */
    public void setSelectionOrder(LiveData<Integer> selectionOrder) {
        if (selectionOrder == null) {
            mSelectedOrderText.setText("");
            if (mSelectionOrder != null) {
                mSelectionOrder.removeObservers(mLifecycleOwner);
            }
        } else {
            mSelectedOrderText.setText(selectionOrder.getValue().toString());
            selectionOrder.observe(
                    mLifecycleOwner,
                    val -> {
                        mSelectedOrderText.setText(val.toString());
                    });
        }
        mSelectionOrder = selectionOrder;
    }

    private void setCustomSelectedMediaIconColors(
            int checkIconColor, int uncheckedIconColor) {
        // Selected Media icon colors for unordered selection
        StateListDrawable drawableCheckIcon = (StateListDrawable) mCheckIcon.getDrawable();
        // Set color of the selected media icon
        LayerDrawable checkIcon = (LayerDrawable) drawableCheckIcon.getStateDrawable(0);
        VectorDrawable selectedMediaBaseCircle = (VectorDrawable) checkIcon.findDrawableByLayerId(
                R.id.selected_radio_button_selected_mark);
        selectedMediaBaseCircle.setTint(checkIconColor);
        // Set color of the unselected media icon
        VectorDrawable uncheckedIcon = (VectorDrawable) drawableCheckIcon.getStateDrawable(1);
        uncheckedIcon.setTint(uncheckedIconColor);
        mCheckIcon.setImageDrawable(drawableCheckIcon);

        // Selected Media icon for ordered selection
        StateListDrawable drawableOrderedSelection =
                (StateListDrawable) mSelectedOrderText.getBackground();
        // Set color of selected media icon
        LayerDrawable orderedIcon = (LayerDrawable) drawableOrderedSelection.getStateDrawable(0);
        GradientDrawable orderedIconBaseCircle =
                (GradientDrawable) orderedIcon.findDrawableByLayerId(
                        R.id.ordered_selection_selected_icon);
        orderedIconBaseCircle.setColor(checkIconColor);
        // Set color of the unselected media icon
        VectorDrawable orderedSelectionSelectedItem =
                (VectorDrawable) drawableOrderedSelection.getStateDrawable(1);
        orderedSelectionSelectedItem.setTint(uncheckedIconColor);
        mSelectedOrderText.setBackground(drawableOrderedSelection);
        mSelectedOrderText.setTextColor(Color.parseColor(
                mPickerViewModel.getPickerAccentColorParameters().isAccentColorBright()
                        ? AccentColorResources.DARK_TEXT_COLOR
                        : AccentColorResources.LIGHT_TEXT_COLOR));
    }

    @NonNull
    private Context getContext() {
        return itemView.getContext();
    }

    /**
     * Get the {@link ImageView} for the thumbnail image representing this MediaItem.
     * @return the image view for the thumbnail.
     */
    public ImageView getThumbnailImageView() {
        return mIconThumb;
    }

    private boolean showShowOverlayGradient(@NonNull Item item) {
        return mCanSelectMultiple
                || item.isGifOrAnimatedWebp()
                || item.isVideo()
                || item.isMotionPhoto();
    }

    /** Release any non-reusable resources, as the view is being recycled. */
    public void release() {
        if (mSelectionOrder != null) {
            mSelectionOrder.removeObservers(mLifecycleOwner);
            mSelectionOrder = null;
        }
    }
}
