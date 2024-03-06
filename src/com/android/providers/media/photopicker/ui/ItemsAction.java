/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents the actions that can be performed on lis of items / category items, based on different
 * scenarios like next page load, refreshing the list, updating the list on profile switch etc.
 */
public class ItemsAction {

    // This is basically a no-op action which will meet no conditions in the code.
    public static final int ACTION_DEFAULT = 0;
    public static final int ACTION_VIEW_CREATED = 1;
    public static final int ACTION_LOAD_NEXT_PAGE = 2;
    public static final int ACTION_CLEAR_AND_UPDATE_LIST = 3;
    public static final int ACTION_CLEAR_GRID = 4;
    public static final int ACTION_REFRESH_ITEMS = 5;


    private ItemsAction() {
    }

    /** @hide */
    @IntDef({ACTION_DEFAULT,
            ACTION_VIEW_CREATED,
            ACTION_LOAD_NEXT_PAGE,
            ACTION_CLEAR_AND_UPDATE_LIST,
            ACTION_CLEAR_GRID,
            ACTION_REFRESH_ITEMS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {
    }
}
