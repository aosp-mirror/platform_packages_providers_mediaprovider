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

package com.android.providers.media.photopicker.viewmodel;

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Category.CategoryType;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.util.ForegroundThread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PickerViewModel to store and handle data for PhotoPickerActivity.
 */
public class PickerViewModel extends AndroidViewModel {
    public static final String TAG = "PhotoPicker";

    private static final int RECENT_MINIMUM_COUNT = 12;
    public static final int DEFAULT_MAX_SELECTION_LIMIT = 100;

    // TODO(b/193857982): we keep these four data sets now, we may find a way to reduce the data
    // set to reduce memories.
    // the list of Items with all photos and videos
    private MutableLiveData<List<Item>> mItemList;
    // the list of Items with all photos and videos in category
    private MutableLiveData<List<Item>> mCategoryItemList;
    private MutableLiveData<Map<Uri, Item>> mSelectedItemList = new MutableLiveData<>();
    private MutableLiveData<List<Category>> mCategoryList;
    private final ItemsProvider mItemsProvider;
    private final UserIdManager mUserIdManager;
    private boolean mSelectMultiple = false;
    private String mMimeTypeFilter = null;
    private int mMaxSelectionLimit = DEFAULT_MAX_SELECTION_LIMIT;
    // This is set to false when max selection limit is reached.
    private boolean mIsSelectionAllowed = true;
    // Show max label text view if and only if caller sets acceptable value for
    // {@link MediaStore#EXTRA_PICK_IMAGES_MAX}
    private boolean mShowMaxLabel = false;
    @CategoryType
    private String mCurrentCategoryType;

    public PickerViewModel(@NonNull Application application) {
        super(application);
        final Context context = application.getApplicationContext();
        mItemsProvider = new ItemsProvider(context);
        mUserIdManager = UserIdManager.create(context);
    }

    /**
     * @return the {@link LiveData} of selected items {@link #mSelectedItemList}.
     */
    public LiveData<Map<Uri, Item>> getSelectedItems() {
        if (mSelectedItemList.getValue() == null) {
            Map<Uri, Item> itemList = new HashMap<>();
            mSelectedItemList.setValue(itemList);
        }
        return mSelectedItemList;
    }

    /**
     * Add the selected {@code item} into {@link #mSelectedItemList}.
     */
    public void addSelectedItem(Item item) {
        if (mSelectedItemList.getValue() == null) {
            Map<Uri, Item> itemList = new HashMap<>();
            mSelectedItemList.setValue(itemList);
        }
        mSelectedItemList.getValue().put(item.getContentUri(), item);
        mSelectedItemList.postValue(mSelectedItemList.getValue());

        updateSelectionAllowed();
    }

    /**
     * Clear the selected Item list {@link #mSelectedItemList}.
     */
    public void clearSelectedItems() {
        if (mSelectedItemList.getValue() == null) {
            return;
        }
        mSelectedItemList.getValue().clear();
        mSelectedItemList.postValue(mSelectedItemList.getValue());
    }

    /**
     * Delete the selected {@code item} from the selected item list {@link #mSelectedItemList}.
     *
     * @param item the item to be deleted from the selected item list
     */
    public void deleteSelectedItem(Item item) {
        if (mSelectedItemList.getValue() == null) {
            return;
        }
        mSelectedItemList.getValue().remove(item.getContentUri());
        mSelectedItemList.postValue(mSelectedItemList.getValue());
        updateSelectionAllowed();
    }

    private void updateSelectionAllowed() {
        if (!mSelectMultiple) {
            return;
        }

        final int size = mSelectedItemList.getValue().size();
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
     * @return {@link UserIdManager} for this context.
     */
    public UserIdManager getUserIdManager() {
        return mUserIdManager;
    }

    /**
     * @return the list of Items with all photos and videos {@link #mItemList} on the device.
     */
    public LiveData<List<Item>> getItems() {
        if (mItemList == null) {
            updateItems();
        }
        return mItemList;
    }

    private List<Item> loadItems(@Nullable @CategoryType String category) {
        final List<Item> items = new ArrayList<>();
        final UserId userId = mUserIdManager.getCurrentUserProfileId();

        try (Cursor cursor = mItemsProvider.getItems(category, /* offset */ 0,
                /* limit */ -1, mMimeTypeFilter, userId)) {
            if (cursor == null) {
                return items;
            }

            // We only add the RECENT header on the PhotosTabFragment with CATEGORY_DEFAULT. In this
            // case, we call this method {loadItems} with null category. When the category is not
            // empty, we don't show the RECENT header.
            final boolean showRecent = TextUtils.isEmpty(category);

            int recentSize = 0;
            long currentDateTaken = 0;
            // add max label message header item
            if (mShowMaxLabel) {
                items.add(Item.createMessageItem());
            }

            if (showRecent) {
                // add Recent date header
                items.add(Item.createDateItem(0));
            }
            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                // here again.
                final Item item = Item.fromCursor(cursor, userId);
                final long dateTaken = item.getDateTaken();
                // the minimum count of items in recent is not reached
                if (showRecent && recentSize < RECENT_MINIMUM_COUNT) {
                    recentSize++;
                    currentDateTaken = dateTaken;
                }

                // The date taken of these two images are not on the
                // same day, add the new date header.
                if (!DateTimeUtils.isSameDate(currentDateTaken, dateTaken)) {
                    items.add(Item.createDateItem(dateTaken));
                    currentDateTaken = dateTaken;
                }
                items.add(item);
            }
        }

        if (TextUtils.isEmpty(category)) {
            Log.d(TAG, "Loaded " + items.size() + " items for user " + userId.toString());
        } else {
            Log.d(TAG, "Loaded " + items.size() + " items in " + category + " for user "
                    + userId.toString());
        }
        return items;
    }

    private void loadItemsAsync() {
        ForegroundThread.getExecutor().execute(() -> {
            mItemList.postValue(loadItems(/* category= */ null));
        });
    }

    /**
     * Update the item List {@link #mItemList}
     */
    public void updateItems() {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        loadItemsAsync();
    }

    /**
     * Get the list of all photos and videos with the specific {@code category} on the device.
     *
     * @param category the category we want to be queried
     * @return the list of all photos and videos with the specific {@code category}
     *         {@link #mCategoryItemList}
     */
    public LiveData<List<Item>> getCategoryItems(@NonNull @CategoryType String category) {
        if (mCategoryItemList == null || !TextUtils.equals(category, mCurrentCategoryType)) {
            mCurrentCategoryType = category;
            updateCategoryItems(category);
        }
        return mCategoryItemList;
    }

    private void loadCategoryItemsAsync(@NonNull @CategoryType String category) {
        ForegroundThread.getExecutor().execute(() -> {
            mCategoryItemList.postValue(loadItems(category));
        });
    }

    /**
     * Update the item List with the {@code category} {@link #mCategoryItemList}
     */
    public void updateCategoryItems(@NonNull @CategoryType String category) {
        if (mCategoryItemList == null) {
            mCategoryItemList = new MutableLiveData<>();
        }
        loadCategoryItemsAsync(category);
    }

    /**
     * @return the list of Categories {@link #mCategoryList}
     */
    public LiveData<List<Category>> getCategories() {
        if (mCategoryList == null) {
            updateCategories();
        }
        return mCategoryList;
    }

    private List<Category> loadCategories() {
        final List<Category> categoryList = new ArrayList<>();
        final UserId userId = mUserIdManager.getCurrentUserProfileId();
        final Cursor cursor = mItemsProvider.getCategories(mMimeTypeFilter, userId);
        if (cursor == null) {
            return categoryList;
        }

        while (cursor.moveToNext()) {
            final Category category = Category.fromCursor(cursor);
            categoryList.add(category);
        }

        Log.d(TAG, "Loaded " + categoryList.size() + " categories for user " + userId.toString());
        return categoryList;
    }

    private void loadCategoriesAsync() {
        ForegroundThread.getExecutor().execute(() -> {
            mCategoryList.postValue(loadCategories());
        });
    }

    /**
     * Update the category List {@link #mCategoryList}
     */
    public void updateCategories() {
        if (mCategoryList == null) {
            mCategoryList = new MutableLiveData<>();
        }
        loadCategoriesAsync();
    }

    /**
     * Return whether supports multiple select {@link #mSelectMultiple} or not
     */
    public boolean canSelectMultiple() {
        return mSelectMultiple;
    }

    /**
     * Return whether the {@link #mMimeTypeFilter} is {@code null} or not
     */
    public boolean hasMimeTypeFilter() {
        return !TextUtils.isEmpty(mMimeTypeFilter);
    }

    /**
     * Parse values from {@code intent} and set corresponding fields
     */
    public void parseValuesFromIntent(Intent intent) throws IllegalArgumentException {
        mSelectMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        final String mimeType = intent.getType();
        if (isMimeTypeMedia(mimeType)) {
            mMimeTypeFilter = mimeType;
        }

        final Bundle extras = intent.getExtras();
        final boolean isExtraPickImagesMaxSet =
                extras != null && extras.containsKey(MediaStore.EXTRA_PICK_IMAGES_MAX);
        // 1. Check EXTRA_PICK_IMAGES_MAX only if EXTRA_ALLOW_MULTIPLE is set.
        // 2. Do not show "Set up to max items" message if EXTRA_PICK_IMAGES_MAX is not set
        if (mSelectMultiple && isExtraPickImagesMaxSet) {
            final int extraMax = intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                    /* defaultValue */ -1);
            // Multi selection max limit should always be greater than 0
            if (extraMax <= 0) {
                throw new IllegalArgumentException("Invalid EXTRA_PICK_IMAGES_MAX value");
            }
            // Multi selection limit should always be less than global max values allowed to select.
            if (extraMax <= DEFAULT_MAX_SELECTION_LIMIT) {
                mMaxSelectionLimit = extraMax;
            }
            mShowMaxLabel = true;
        }
    }

    private static boolean isMimeTypeMedia(@Nullable String mimeType) {
        return isImageMimeType(mimeType) || isVideoMimeType(mimeType);
    }

    /**
     * Return maximum limit of items that can be selected
     */
    public int getMaxSelectionLimit() {
        return mMaxSelectionLimit;
    }

    /**
     * Return whether more items can be selected or not.
     */
    public boolean isSelectionAllowed() {
        return mIsSelectionAllowed;
    }
}
