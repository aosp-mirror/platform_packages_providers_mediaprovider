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

package com.android.providers.media.photopicker.data;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class that tracks Selection
 */
public class Selection {
    // The list of selected items.
    private Map<Uri, Item> mSelectedItems = new HashMap<>();
    private MutableLiveData<Integer> mSelectedItemSize = new MutableLiveData<>();
    // The list of selected items for preview. This needs to be saved separately so that if activity
    // gets killed, we will still have deselected items for preview.
    private List<Item> mSelectedItemsForPreview = new ArrayList<>();
    private boolean mSelectMultiple = false;
    private int mMaxSelectionLimit = 1;
    // This is set to false when max selection limit is reached.
    private boolean mIsSelectionAllowed = true;

    /**
     * @return {@link #mSelectedItems} - A {@link List} of selected {@link Item}
     */
    public List<Item> getSelectedItems() {
        return Collections.unmodifiableList(new ArrayList<>(mSelectedItems.values()));
    }

    /**
     * @return {@link LiveData} of count of selected items in {@link #mSelectedItems}
     */
    public LiveData<Integer> getSelectedItemCount() {
        if (mSelectedItemSize.getValue() == null) {
            mSelectedItemSize.setValue(mSelectedItems.size());
        }
        return mSelectedItemSize;
    }

    /**
     * Add the selected {@code item} into {@link #mSelectedItems}.
     */
    public void addSelectedItem(Item item) {
        mSelectedItems.put(item.getContentUri(), item);
        mSelectedItemSize.postValue(mSelectedItems.size());
        updateSelectionAllowed();
    }

    /**
     * Clears {@link #mSelectedItems} and sets the selected item as given {@code item}
     */
    public void setSelectedItem(Item item) {
        mSelectedItems.clear();
        mSelectedItems.put(item.getContentUri(), item);
        mSelectedItemSize.postValue(mSelectedItems.size());
        updateSelectionAllowed();
    }

    /**
     * Remove the {@code item} from the selected item list {@link #mSelectedItems}.
     *
     * @param item the item to be removed from the selected item list
     */
    public void removeSelectedItem(Item item) {
        mSelectedItems.remove(item.getContentUri());
        mSelectedItemSize.postValue(mSelectedItems.size());
        updateSelectionAllowed();
    }

    /**
     * Clear all selected items
     */
    public void clearSelectedItems() {
        mSelectedItems.clear();
        mSelectedItemSize.postValue(mSelectedItems.size());
        updateSelectionAllowed();
    }

    /**
     * @return {@code true} if give {@code item} is present in selected items
     *         {@link #mSelectedItems}, {@code false} otherwise
     */
    public boolean isItemSelected(Item item) {
        return mSelectedItems.containsKey(item.getContentUri());
    }

    private void updateSelectionAllowed() {
        final int size = mSelectedItems.size();
        if (size >= mMaxSelectionLimit) {
            if (mIsSelectionAllowed) {
                mIsSelectionAllowed = false;
            }
        } else {
            // size < mMaxSelectionLimit
            if (!mIsSelectionAllowed) {
                mIsSelectionAllowed = true;
            }
        }
    }

    /**
     * @return returns whether more items can be selected or not. {@code true} if the number of
     *         selected items is lower than or equal to {@code mMaxLimit}, {@code false} otherwise.
     */
    public boolean isSelectionAllowed() {
        return mIsSelectionAllowed;
    }

    /**
     * Prepares current selected items for previewing all selected items in multi-select preview.
     * The method also sorts the selected items by {@link Item#compareTo} method which sorts based
     * on dateTaken values.
     */
    public void prepareSelectedItemsForPreviewAll() {
        mSelectedItemsForPreview = new ArrayList<>(mSelectedItems.values());
        mSelectedItemsForPreview.sort(Collections.reverseOrder(Item::compareTo));
    }

    /**
     * Sets the given {@code item} as the item for previewing. This method will be used while
     * previewing on long press.
     */
    public void prepareItemForPreviewOnLongPress(Item item) {
        mSelectedItemsForPreview = Collections.singletonList(item);
    }

    /**
     * @return {@link #mSelectedItemsForPreview} - selected items for preview.
     */
    public List<Item> getSelectedItemsForPreview() {
        return Collections.unmodifiableList(mSelectedItemsForPreview);
    }

    /** Parse values from {@code intent} and set corresponding fields */
    public void parseSelectionValuesFromIntent(Intent intent) {
        final Bundle extras = intent.getExtras();
        final boolean isExtraPickImagesMaxSet =
                extras != null && extras.containsKey(MediaStore.EXTRA_PICK_IMAGES_MAX);

        if (intent.getAction() != null
                && intent.getAction().equals(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)) {
            // If this is picking media for an app, enable multiselect.
            mSelectMultiple = true;
            // Allow selections up to the limit.
            // TODO(b/255301849): Update max limit after discussing with product team.
            mMaxSelectionLimit = MediaStore.getPickImagesMaxLimit();

            return;
        } else if (intent.getAction() != null
                // Support Intent.EXTRA_ALLOW_MULTIPLE flag only for ACTION_GET_CONTENT
                && intent.getAction().equals(Intent.ACTION_GET_CONTENT)) {
            if (isExtraPickImagesMaxSet) {
                throw new IllegalArgumentException(
                        "EXTRA_PICK_IMAGES_MAX is not supported for " + "ACTION_GET_CONTENT");
            }

            mSelectMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            if (mSelectMultiple) {
                mMaxSelectionLimit = MediaStore.getPickImagesMaxLimit();
            }

            return;
        }

        // Check EXTRA_PICK_IMAGES_MAX value only if the flag is set.
        if (isExtraPickImagesMaxSet) {
            final int extraMax =
                    intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                            /* defaultValue */ -1);
            // Multi selection max limit should always be greater than 1 and less than or equal
            // to PICK_IMAGES_MAX_LIMIT.
            if (extraMax <= 1 || extraMax > MediaStore.getPickImagesMaxLimit()) {
                throw new IllegalArgumentException("Invalid EXTRA_PICK_IMAGES_MAX value");
            }
            mSelectMultiple = true;
            mMaxSelectionLimit = extraMax;
        }
    }

    /**
     * Return whether supports multiple select {@link #mSelectMultiple} or not
     */
    public boolean canSelectMultiple() {
        return mSelectMultiple;
    }

    /**
     * Return maximum limit of items that can be selected
     */
    public int getMaxSelectionLimit() {
        return mMaxSelectionLimit;
    }
}
