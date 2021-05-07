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

import androidx.lifecycle.AndroidViewModel;

import com.android.providers.media.photopicker.data.ItemsProvider;

/**
 * GridViewModel to store and handle data for GridViewFragment.
 * TODO(169738588): Update the class to provide data for RecyclerView
 */
public class GridViewModel extends AndroidViewModel {
    ItemsProvider mItemsProvider;
    Cursor mCursor = null;
    public GridViewModel(@NonNull Application application) {
        super(application);
        mItemsProvider = new ItemsProvider(getApplication().getApplicationContext());
    }

    /**
     * @return {@link Cursor} with all photos and videos on the device.
     */
    public Cursor getItems() {
        if (mCursor == null) {
            loadItems();
        }
        return mCursor;
    }

    private void loadItems() {
        // TODO(b/168001592) call getItems() from worker thread.
        mCursor = mItemsProvider.getItems(null, 0, 0, null);
    }
}
