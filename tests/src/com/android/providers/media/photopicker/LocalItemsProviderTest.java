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

package com.android.providers.media.photopicker;

import android.database.Cursor;

import com.android.providers.media.photopicker.data.LocalItemsProvider;

import org.junit.Test;

public class LocalItemsProviderTest {
    @Test
    public void testGetItems() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider();
        Cursor res = localItemsProvider.getItems();
        // TODO: Add assertions when the implementation is added
    }

    @Test
    public void testGetItemsAll() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider();
        Cursor res = localItemsProvider.getItems("*/*");
        // TODO: Add assertions when the implementation is added
    }

    @Test
    public void testGetItemsImages() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider();
        Cursor res = localItemsProvider.getItems("image/*");
        // TODO: Add assertions when the implementation is added
    }

    @Test
    public void testGetItemsVideos() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider();
        Cursor res = localItemsProvider.getItems("video/*");
        // TODO: Add assertions when the implementation is added
    }

    @Test
    public void testGetItemsInvalidParam() throws Exception {
        LocalItemsProvider localItemsProvider = new LocalItemsProvider();
        Cursor res = localItemsProvider.getItems("audio/*");
        // TODO: Add assertions when the implementation is added
    }
}
