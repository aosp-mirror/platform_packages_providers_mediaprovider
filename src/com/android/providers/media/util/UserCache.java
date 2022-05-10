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

package com.android.providers.media.util;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static com.android.providers.media.util.Logging.TAG;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.modules.utils.build.SdkLevel;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * UserCache is a class that keeps track of all users that the current MediaProvider
 * instance is responsible for. By default, it handles storage for the user it is running as,
 * but as of Android API 31, it will also handle storage for profiles that share media
 * with their parent - profiles for which @link{UserManager#isMediaSharedWithParent} is set.
 *
 * It also keeps a cache of user contexts, for improving these lookups.
 *
 * Note that we don't use the USER_ broadcasts for keeping this state up to date, because they
 * aren't guaranteed to be received before the volume events for a user.
 */
public class UserCache {
    // This is being used for non work profile users. It is introduced to remove the necessity of
    // second cache i.e. mUserIsWorkProfile
    private static final String NO_WORK_PROFILE_OWNER_APP = "No Work Profile Owner App";

    final Object mLock = new Object();
    final Context mContext;
    final UserManager mUserManager;

    @GuardedBy("mLock")
    final LongSparseArray<Context> mUserContexts = new LongSparseArray<>();

    // This contains a mapping from userId to packageName of the Profile Owner App
    // or NO_WORK_PROFILE_OWNER_APP
    @GuardedBy("mLock")
    final SparseArray<String> mWorkProfileOwnerApps = new SparseArray<>();

    @GuardedBy("mLock")
    final ArrayList<UserHandle> mUsers = new ArrayList<>();

    public UserCache(Context context) {
        mContext = context;
        mUserManager = context.getSystemService(UserManager.class);

        update();
    }

    @SuppressLint("NewApi")
    private void update() {
        List<UserHandle> profiles = mUserManager.getEnabledProfiles();
        synchronized (mLock) {
            mUsers.clear();
            // Add the user we're running as by default
            mUsers.add(Process.myUserHandle());
            if (!SdkLevel.isAtLeastS()) {
                // Before S, we only handle the owner user
                return;
            }

            // App cloning is not supported for profile users like AFW.
            if (mUserManager.isProfile()) {
                return;
            }

            // And find all profiles that share media with us
            for (UserHandle profile : profiles) {
                if (!profile.equals(mContext.getUser())) {
                    // Check if it's unlocked, and it's a profile that shares media with us
                    if (isUnlockedAndMediaSharedWithParent(profile)) {
                        mUsers.add(profile);
                    }
                }
            }
        }
    }

    private boolean isUnlockedAndMediaSharedWithParent(@NonNull UserHandle profile) {
        Context userContext = getContextForUser(profile);
        UserManager userManager = userContext.getSystemService(UserManager.class);
        return (SdkLevel.isAtLeastT() ?
                userManager.isUserUnlocked() : userManager.isUserUnlocked(profile))
                && userManager.isMediaSharedWithParent();
    }

    public @NonNull List<UserHandle> updateAndGetUsers() {
        update();
        synchronized (mLock) {
            return (List<UserHandle>) mUsers.clone();
        }
    }

    public @NonNull List<UserHandle> getUsersCached() {
        synchronized (mLock) {
            return (List<UserHandle>) mUsers.clone();
        }
    }

    public boolean isWorkProfile(int userId) {
        if (userId == 0) {
            // Owner user can not have a work profile
            return false;
        }

        synchronized (mLock) {
            int index = mWorkProfileOwnerApps.indexOfKey(userId);
            if (index >= 0) {
                return !NO_WORK_PROFILE_OWNER_APP.equals(mWorkProfileOwnerApps.valueAt(index));
            }
        }

        Context userContext = getContextForUser(UserHandle.of(userId));
        PackageManager packageManager = userContext.getPackageManager();
        DevicePolicyManager policyManager = userContext.getSystemService(
                DevicePolicyManager.class);
        boolean isWorkProfile = false;
        for (ApplicationInfo ai : packageManager.getInstalledApplications(
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE)) {
            if (policyManager.isProfileOwnerApp(ai.packageName)) {
                synchronized (mLock) {
                    mWorkProfileOwnerApps.put(userId, ai.packageName);
                }
                isWorkProfile = true;
                break;
            }
        }

        if(!isWorkProfile) {
            synchronized (mLock) {
                // NO_WORK_PROFILE_OWNER_APP is being used for all the non work profile users
                mWorkProfileOwnerApps.put(userId, NO_WORK_PROFILE_OWNER_APP);
            }
        }

        return isWorkProfile;
    }

    public @NonNull Context getContextForUser(@NonNull UserHandle user) {
        Context userContext;
        synchronized (mLock) {
            userContext = mUserContexts.get(user.getIdentifier());
            if (userContext != null) {
                return userContext;
            }
        }
        try {
            userContext = mContext.createPackageContextAsUser("system", 0, user);
            synchronized (mLock) {
                mUserContexts.put(user.getIdentifier(), userContext);
            }
            return userContext;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Failed to create context for user " + user, e);
        }
    }

    /**
     *  Returns whether the passed in user shares media with its parent (or peer).
     *
     * @param user user to check
     * @return whether the user shares media with its parent
     */
    public boolean userSharesMediaWithParent(@NonNull UserHandle user) {
        if (Process.myUserHandle().equals(user)) {
            // Early return path - the owner user doesn't have a parent
            return false;
        }
        boolean found = userSharesMediaWithParentCached(user);
        if (!found) {
            // Update the cache and try again
            update();
            found = userSharesMediaWithParentCached(user);
        }
        return found;
    }

    /**
     *  Returns whether the passed in user shares media with its parent (or peer).
     *  Note that the value returned here is based on cached data; it relies on
     *  other callers to keep the user cache up-to-date.
     *
     * @param user user to check
     * @return whether the user shares media with its parent
     */
    public boolean userSharesMediaWithParentCached(@NonNull UserHandle user) {
        synchronized (mLock) {
            // It must be a user that we manage, and not equal to the main user that we run as
            return !Process.myUserHandle().equals(user) && mUsers.contains(user);
        }
    }

    public void dump(PrintWriter writer) {
        writer.println("User cache state:");
        synchronized (mLock) {
            for (UserHandle user : mUsers) {
                writer.println("  user: " + user);
            }
        }
    }

    public void invalidateWorkProfileOwnerApps(@NonNull String packageName) {
        synchronized (mLock) {
            if (mWorkProfileOwnerApps.size() == 0) {
                Log.w(TAG, "WorkProfileOwnerApps cache is empty");
                return;
            }

            boolean cacheMissForGivenPackage = true;
            for (int i = 0; i < mWorkProfileOwnerApps.size(); i++) {
                final int userId = mWorkProfileOwnerApps.keyAt(i);
                if (packageName.equals(mWorkProfileOwnerApps.get(userId))) {
                    Log.i(TAG, "Invalidating WorkProfileOwnerApps cache for package " + packageName
                            + ". UserId: " + userId);
                    mWorkProfileOwnerApps.remove(userId);
                    cacheMissForGivenPackage = false;
                }
            }

            if(cacheMissForGivenPackage) {
                Log.w(TAG, "WorkProfileOwnerApps cache miss for package " + packageName);
            }
        }
    }
}
