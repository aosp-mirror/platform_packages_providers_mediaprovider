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

package com.android.providers.media.util;


import static androidx.core.util.Preconditions.checkNotNull;

import android.annotation.UserIdInt;
import android.util.SparseArray;

import androidx.annotation.NonNull;

/**
 * A {@link SparseArray} customized for a common use-case of storing state per-user.
 *
 * @param <T> user state type
 */
public abstract class PerUser<T> extends SparseArray<T> {
    /**
     * Initialize state for the given user
     */
    @NonNull
    protected abstract T create(@UserIdInt int userId);

    /**
     * @return -
     *      {@link #get(int)} if not {@code null} or
     *      {@link #create(int)} otherwise.
     */
    @NonNull
    public T forUser(@UserIdInt int userId) {
        T userState = super.get(userId);
        if (userState == null) {
            userState = checkNotNull(create(userId));
            put(userId, userState);
        }
        return userState;
    }
}
