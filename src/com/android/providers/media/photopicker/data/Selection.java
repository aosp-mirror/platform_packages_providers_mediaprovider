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

import android.annotation.Nullable;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A class that tracks Selection
 */
public class Selection {
    /**
     * Contains positions of checked Item at UI. {@link #mCheckedItemIndexes} may have more number
     * of indexes , from the number of items present in {@link #mSelectedItems}. The index in
     * {@link #mCheckedItemIndexes} is a potential index that needs to be rechecked in
     * notifyItemChanged() at the time of deselecting the unavailable item at UI when user is
     * offline and tries adding unavailable non cached items. the item corresponding to the index in
     * {@link #mCheckedItemIndexes} may no longer be selected.
     */
    private final Map<Item, Integer> mCheckedItemIndexes = new HashMap<>();

    // The list of selected items.
    private Map<Uri, Item> mSelectedItems = new LinkedHashMap<>();
    private Map<Uri, MutableLiveData<Integer>> mSelectedItemsOrder = new HashMap<>();
    private Map<String, Item> mItemGrantRevocationMap = new HashMap<>();

    private MutableLiveData<Integer> mSelectedItemSize = new MutableLiveData<>();
    // The list of selected items for preview. This needs to be saved separately so that if activity
    // gets killed, we will still have deselected items for preview.
    private List<Item> mSelectedItemsForPreview = new ArrayList<>();
    private boolean mIsSelectionOrdered = false;
    private boolean mSelectMultiple = false;
    private int mMaxSelectionLimit = 1;
    // This is set to false when max selection limit is reached.
    private boolean mIsSelectionAllowed = true;

    private int mTotalNumberOfPreGrantedItems = 0;

    private Set<String> mPreGrantedItemsSet;

    private static final String TAG = "PhotoPickerSelection";

    /**
     * Updates the list of pre granted items and the count of selected items.
     */
    public void setPreGrantedItemSet(@Nullable Set<String> preGrantedItemSet) {
        if (preGrantedItemSet != null) {
            mPreGrantedItemsSet = preGrantedItemSet;
            setTotalNumberOfPreGrantedItems(preGrantedItemSet.size());
            Log.d(TAG, "Pre-Granted items have been loaded. Number of items:"
                    + preGrantedItemSet.size());
        } else {
            mPreGrantedItemsSet = new HashSet<>(0);
            Log.d(TAG, "No Pre-Granted items present");
        }
    }

    /**
     * @return a set of item ids that are pre granted for the current package and user.
     */
    @Nullable
    public Set<String> getPreGrantedItems() {
        return mPreGrantedItemsSet;
    }

    /**
     * @return {@link #mSelectedItems} - A {@link List} of selected {@link Item}
     */
    public List<Item> getSelectedItems() {
        ArrayList<Item> result = new ArrayList<>(mSelectedItems.values());
        return Collections.unmodifiableList(result);
    }

    /**
     * @return A {@link Set} of selected {@link Item} ids.
     */
    public Set<String> getSelectedItemsIds() {
        return mSelectedItems.values().stream().map(Item::getId).collect(
                Collectors.toSet());
    }

    /**
     * @return A {@link List} of selected {@link Item} that do not hold a READ_GRANT.
     */
    public List<Item> getSelectedItemsWithoutGrants() {
        return mSelectedItems.values().stream().filter((Item item) -> !item.isPreGranted())
                .collect(Collectors.toList());
    }

    /**
     * @return Indexes - A {@link List} of checked {@link Item} positions.
     */
    public Collection<Integer> getCheckedItemsIndexes() {
        return mCheckedItemIndexes.values();
    }

    /**
     * @return A {@link List} of items for which the grants need to be revoked.
     */
    public List<Item> getPreGrantedItemsToBeRevoked() {
        return mItemGrantRevocationMap.values().stream().collect(Collectors.toList());
    }

    /**
     * @return A {@link List} of ids for which the grants need to be revoked.
     */
    public List<String> getPreGrantedItemIdsToBeRevoked() {
        return mItemGrantRevocationMap.keySet().stream().collect(Collectors.toList());
    }

    /**
     * Sets the count of pre granted items to ensure that the correct number is displayed in
     * preview and on the add button.
     */
    public void setTotalNumberOfPreGrantedItems(int totalNumberOfPreGrantedItems) {
        mTotalNumberOfPreGrantedItems = totalNumberOfPreGrantedItems;
        mSelectedItemSize.postValue(getTotalItemsCount());
    }

    /**
     * @return {@link LiveData} of count of selected items in {@link #mSelectedItems}
     */
    public LiveData<Integer> getSelectedItemCount() {
        if (mSelectedItemSize.getValue() == null) {
            mSelectedItemSize.setValue(getTotalItemsCount());
        }
        return mSelectedItemSize;
    }

    /**
     * @return {@link LiveData} of the item selection order.
     */
    public LiveData<Integer> getSelectedItemOrder(Item item) {
        return mSelectedItemsOrder.get(item.getContentUri());
    }

    private int getTotalItemsCount() {
        return mSelectedItems.size() - countOfPreGrantedItems() + mTotalNumberOfPreGrantedItems
                - mItemGrantRevocationMap.size();
    }

    /**
     * Add the selected {@code item} into {@link #mSelectedItems}.
     */
    public void addSelectedItem(Item item) {
        if (item.isPreGranted() && mItemGrantRevocationMap.containsKey(item.getId())) {
            mItemGrantRevocationMap.remove(item.getId());
        }
        if (mIsSelectionOrdered) {
            mSelectedItemsOrder.put(
                    item.getContentUri(), new MutableLiveData(getTotalItemsCount() + 1));
        }
        mSelectedItems.put(item.getContentUri(), item);
        mSelectedItemSize.postValue(getTotalItemsCount());
        updateSelectionAllowed();
    }

    /**
     * Add the checked {@code item} index into {@link #mCheckedItemIndexes}.
     */
    public void addCheckedItemIndex(Item item, Integer index) {
        mCheckedItemIndexes.put(item, index);
    }

    /**
     * Clears {@link #mSelectedItems} and sets the selected item as given {@code item}
     */
    public void setSelectedItem(Item item) {
        mSelectedItemsOrder.clear();
        mSelectedItems.clear();
        mSelectedItems.put(item.getContentUri(), item);
        if (mIsSelectionOrdered) {
            mSelectedItemsOrder.put(
                    item.getContentUri(), new MutableLiveData(getTotalItemsCount()));
        }
        mSelectedItemSize.postValue(getTotalItemsCount());
        updateSelectionAllowed();
    }

    /**
     * Remove the {@code item} from the selected item list {@link #mSelectedItems}
     *
     * @param item the item to be removed from the selected item list
     */
    public void removeSelectedItem(Item item) {
        if (item.isPreGranted()) {
            // Maintain a list of items that were pre-granted but the user has deselected them in
            // the current session. This list will be used to revoke existing grants for these
            // items.
            mItemGrantRevocationMap.put(item.getId(), item);
        }
        if (mIsSelectionOrdered) {
            MutableLiveData<Integer> removedItem = mSelectedItemsOrder.remove(item.getContentUri());
            int removedItemOrder = removedItem.getValue().intValue();
            mSelectedItemsOrder.values().stream()
                    .filter(order -> order.getValue().intValue() > removedItemOrder)
                    .forEach(
                            order -> {
                                order.setValue(order.getValue().intValue() - 1);
                            });
        }
        mSelectedItems.remove(item.getContentUri());
        mSelectedItemSize.postValue(getTotalItemsCount());
        updateSelectionAllowed();
    }

    /**
     * Remove the {@code item} index from the checked item  index list {@link #mCheckedItemIndexes}.
     *
     * @param item the item to be removed from the selected item list
     */
    public void removeCheckedItemIndex(Item item) {
        mCheckedItemIndexes.remove(item);
    }

    /**
     * Clear all selected items and checked positions
     */
    public void clearSelectedItems() {
        mSelectedItemsOrder.clear();
        mSelectedItems.clear();
        mCheckedItemIndexes.clear();
        mSelectedItemSize.postValue(getTotalItemsCount());
        updateSelectionAllowed();
    }

    /**
     * Clear all checked items
     */
    public void clearCheckedItemList() {
        mCheckedItemIndexes.clear();
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
        if (size  - countOfPreGrantedItems() >= mMaxSelectionLimit) {
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

    private int countOfPreGrantedItems() {
        if (mSelectedItems.values() != null) {
            return (int) mSelectedItems.values().stream().filter(Item::isPreGranted).count();
        } else {
            return 0;
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
        final boolean isExtraOrderedSelectionSet =
                extras != null && extras.containsKey(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER);

        if (intent.getAction() != null
                && intent.getAction().equals(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)) {
            // If this is picking media for an app, enable multiselect.
            mSelectMultiple = true;
            // disable ordered selection.
            mIsSelectionOrdered = false;
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

            if (isExtraOrderedSelectionSet) {
                throw new IllegalArgumentException(
                        "EXTRA_PICK_IMAGES_IN_ORDER is not supported for ACTION_GET_CONTENT");
            }

            mSelectMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            if (mSelectMultiple) {
                mMaxSelectionLimit = MediaStore.getPickImagesMaxLimit();
            }

            return;
        }

        if (isExtraOrderedSelectionSet) {
            mIsSelectionOrdered = extras.getBoolean(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER);
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

    /** Return whether ordered selection is enabled or not. */
    public boolean isSelectionOrdered() {
        return mIsSelectionOrdered;
    }

    /**
     * Return maximum limit of items that can be selected
     */
    public int getMaxSelectionLimit() {
        return mMaxSelectionLimit;
    }
}
