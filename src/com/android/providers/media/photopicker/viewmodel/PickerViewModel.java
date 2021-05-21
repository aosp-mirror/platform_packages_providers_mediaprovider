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
import android.database.Cursor;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.providers.media.photopicker.data.ItemsProvider;
import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * PickerViewModel to store and handle data for PhotoPickerActivity.
 */
public class PickerViewModel extends AndroidViewModel {
    public static final String TAG = "PhotoPicker";

    private MutableLiveData<List<Item>> mItemList;
    private MutableLiveData<List<Item>> mSelectedItemList = new MutableLiveData<>();
    private ItemsProvider mItemsProvider;

    public PickerViewModel(@NonNull Application application) {
        super(application);
        mItemsProvider = new ItemsProvider(application.getApplicationContext());
    }

    /**
     * @return list of selected Item.
     */
    public LiveData<List<Item>> getSelectedItems() {
        return mSelectedItemList;
    }

    /**
     * Add the selected ItemInfo.
     */
    public void addSelectedItem(Item item) {
        if (mSelectedItemList.getValue() == null) {
            List<Item> itemList = new ArrayList<>();
            mSelectedItemList.setValue(itemList);
        }
        mSelectedItemList.getValue().add(item);
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
        List<Item> items = new ArrayList<>();
        // TODO(b/168001592) call getItems() from worker thread.
        Cursor cursor = mItemsProvider.getItems(null, 0, 0, null);
        if (cursor == null) {
            return items;
        }

        while (cursor.moveToNext()) {
            items.add(Item.fromCursor(cursor));
        }

        Log.d(TAG, "Load items with count = " + items.size());
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
}
