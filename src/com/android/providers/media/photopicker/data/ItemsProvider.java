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

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

/**
 * The base class that is responsible for obtaining data from all providers and
 * merge the data together and provide it to ViewModel.
 */
public class ItemsProvider {
    private Context mContext;
    private LocalItemsProvider mLocalItemsProvider;
    public ItemsProvider(Context context) {
        mContext = context;
        mLocalItemsProvider = new LocalItemsProvider(mContext);
    }

    /**
     * @return {@link Cursor} with all photos and videos on the device.
     */
    public Cursor getItems() {
        return mLocalItemsProvider.getItems(/* mimeType */ null);
    }
}
