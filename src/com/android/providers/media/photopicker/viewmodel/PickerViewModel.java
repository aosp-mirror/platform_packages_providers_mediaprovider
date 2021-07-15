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
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
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

    private MutableLiveData<List<Item>> mItemList;
    private MutableLiveData<Map<Uri, Item>> mSelectedItemList = new MutableLiveData<>();
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

    public PickerViewModel(@NonNull Application application) {
        super(application);
        final Context context = application.getApplicationContext();
        mItemsProvider = new ItemsProvider(context);
        mUserIdManager = UserIdManager.create(context);
    }

    /**
     * @return the Map of selected Item.
     */
    public LiveData<Map<Uri, Item>> getSelectedItems() {
        if (mSelectedItemList.getValue() == null) {
            Map<Uri, Item> itemList = new HashMap<>();
            mSelectedItemList.setValue(itemList);
        }
        return mSelectedItemList;
    }

    /**
     * Add the selected Item.
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
     * Clear the selected Item list.
     */
    public void clearSelectedItems() {
        if (mSelectedItemList.getValue() == null) {
            return;
        }
        mSelectedItemList.getValue().clear();
        mSelectedItemList.postValue(mSelectedItemList.getValue());
    }

    /**
     * Delete the selected Item.
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
     * @return the list of Items with all photos and videos on the device.
     */
    public LiveData<List<Item>> getItems() {
        if (mItemList == null) {
            updateItems();
        }
        return mItemList;
    }

    private List<Item> loadItems() {
        final List<Item> items = new ArrayList<>();
        final UserId userId = mUserIdManager.getCurrentUserProfileId();

        try (Cursor cursor = mItemsProvider.getItems(/* category */ null, /* offset */ 0,
                /* limit */ -1, mMimeTypeFilter, userId)) {
            if (cursor == null) {
                return items;
            }

            int recentSize = 0;
            long currentDateTaken = 0;
            // add max label message header item
            if (mShowMaxLabel) {
                items.add(Item.createMessageItem());
            }
            // add Recent date header
            items.add(Item.createDateItem(0));
            while (cursor.moveToNext()) {
                // TODO(b/188394433): Return userId in the cursor so that we do not need to pass it
                //  here again.
                final Item item = Item.fromCursor(cursor, userId);
                final long dateTaken = item.getDateTaken();
                // the minimum count of items in recent is not reached
                if (recentSize < RECENT_MINIMUM_COUNT) {
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

        Log.d(TAG, "Loaded " + items.size() + " items for user " + userId.toString());
        return items;
    }

    private void loadItemsAsync() {
        ForegroundThread.getExecutor().execute(() -> {
            mItemList.postValue(loadItems());
        });
    }

    /**
     * Update the item List
     */
    public void updateItems() {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        loadItemsAsync();
    }

    /**
     * Return whether supports multiple select or not
     */
    public boolean canSelectMultiple() {
        return mSelectMultiple;
    }

    /**
     * Parse values from Intent and set corresponding fields
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

    public static boolean isMimeTypeMedia(@Nullable String mimeType) {
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
