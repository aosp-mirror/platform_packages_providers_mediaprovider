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

package com.android.providers.media.photopicker.data.model;

import static androidx.core.util.Preconditions.checkNotNull;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;

/**
 * Representation of a {@link UserHandle}.
 */
public final class UserId {
    // A current user represents the user of the app's process. It is mainly used for comparison.
    public static final UserId CURRENT_USER = UserId.of(Process.myUserHandle());

    private final UserHandle mUserHandle;

    private UserId(UserHandle userHandle) {
        checkNotNull(userHandle);
        mUserHandle = userHandle;
    }

    /**
     * Returns a {@link UserId} for a given {@link UserHandle}.
     */
    public static UserId of(UserHandle userHandle) {
        return new UserId(userHandle);
    }

    /**
     * Returns the given context if the user is the current user or unspecified. Otherwise, returns
     * an "android" package context as the user.
     *
     * @throws IllegalStateException if android package of the other user does not exist
     */
    Context asContext(Context context) {
        if (CURRENT_USER.equals(this)) {
            return context;
        }
        try {
            return context.createPackageContextAsUser("android", /* flags= */ 0, mUserHandle);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("android package not found.");
        }
    }

    /**
     * Return a content resolver instance of this user.
     */
    public ContentResolver getContentResolver(Context context) {
        return asContext(context).getContentResolver();
    }
}
