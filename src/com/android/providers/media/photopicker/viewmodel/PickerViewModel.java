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

import android.annotation.NonNull;
import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.DateTimeUtils;
import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

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

    private MutableLiveData<List<Item>> mItemList;
    private MutableLiveData<Map<Uri, Item>> mSelectedItemList = new MutableLiveData<>();
    private final ItemsProvider mItemsProvider;
    private final UserIdManager mUserIdManager;
    private boolean mSelectMultiple = false;

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
     * Add the selected ItemInfo.
     */
    public void addSelectedItem(Item item) {
        if (mSelectedItemList.getValue() == null) {
            Map<Uri, Item> itemList = new HashMap<>();
            mSelectedItemList.setValue(itemList);
        }
        mSelectedItemList.getValue().put(item.getContentUri(), item);
        mSelectedItemList.postValue(mSelectedItemList.getValue());
    }

    /**
     * Delete the selected ItemInfo.
     */
    public void deleteSelectedItem(Item item) {
        if (mSelectedItemList.getValue() == null) {
            return;
        }
        mSelectedItemList.getValue().remove(item.getContentUri());
        mSelectedItemList.postValue(mSelectedItemList.getValue());
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
        // TODO(b/168001592) call getItems() from worker thread.
        Cursor cursor = mItemsProvider.getItems(null, 0, -1, null, userId);
        if (cursor == null) {
            return items;
        }

        int recentSize = 0;
        long currentDateTaken = 0;
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

        Log.d(TAG, "Loaded " + items.size() + " items for user " + userId.toString());
        return items;
    }

    /**
     * Update the item List
     */
    public void updateItems() {
        if (mItemList == null) {
            mItemList = new MutableLiveData<>();
        }
        mItemList.postValue(loadItems());
    }

    /**
     * Return whether supports multiple select or not
     */
    public boolean canSelectMultiple() {
        return mSelectMultiple;
    }

    /**
     * Set the value for whether supports multiple select or not
     */
    public void setSelectMultiple(boolean allowMultiple) {
        mSelectMultiple = allowMultiple;
    }
}
