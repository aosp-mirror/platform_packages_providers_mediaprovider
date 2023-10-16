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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

/**
 * Representation of a {@link UserHandle}.
 */
public final class UserId {
    // A current user represents the user of the app's process. It is mainly used for comparison.
    public static final UserId CURRENT_USER = UserId.of(Process.myUserHandle());

    private static final String TAG = "PhotoPickerUserId";

    private final UserHandle mUserHandle;

    private UserId(UserHandle userHandle) {
        checkNotNull(userHandle);
        mUserHandle = userHandle;
    }

    public UserHandle getUserHandle() {
        return mUserHandle;
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
     * @throws NameNotFoundException if android package of the other user does not exist
     */
    Context asContext(Context context) throws NameNotFoundException {
        if (CURRENT_USER.equals(this)) {
            return context;
        }
        return context.createPackageContextAsUser("android", /* flags= */ 0, mUserHandle);
    }

    /**
     * Return a content resolver instance of this user.
     */
    public ContentResolver getContentResolver(Context context) throws NameNotFoundException {
        return asContext(context).getContentResolver();
    }

    /**
     * Return Package Manager as an instance of this user.
     */
    @NonNull
    public PackageManager getPackageManager(Context context) throws NameNotFoundException {
        return asContext(context).getPackageManager();
    }

    /**
     * Return identifier of the user.
     */
    public int getIdentifier() {
        return mUserHandle.getIdentifier();
    }

    /**
     * @return {@link UserHandle} of parent user profile. Otherwise returns {@code null}.
     */
    public static UserHandle getParentProfile(UserManager userManager, UserHandle userHandle) {
        return userManager.getProfileParent(userHandle);
    }

    /**
     * Returns true if the this user is a managed profile.
     */
    public boolean isManagedProfile(UserManager userManager) {
        return userManager.isManagedProfile(mUserHandle.getIdentifier());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        try {
            if (obj != null) {
                UserId other = (UserId)obj;
                return mUserHandle == other.mUserHandle;
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "Cannot check equality due to ", e);
        }
        return false;
    }

    @Override
    public String toString() {
        return String.valueOf(this.mUserHandle.getIdentifier());
    }
}
