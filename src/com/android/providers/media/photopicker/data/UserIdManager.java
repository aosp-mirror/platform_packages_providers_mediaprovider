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

import static androidx.core.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.media.photopicker.data.model.UserId;

import java.util.List;

/**
 * Interface to query user ids {@link UserId}
 */
public interface UserIdManager {

    /**
     * Whether there are more than 1 user profiles associated with the current user.
     * @return
     */
    boolean isMultiUserProfiles();

    /**
     * Returns the personal user profile id iff there are at least 2 user profiles for current
     * user. Otherwise, returns null.
     */
    @Nullable
    UserId getPersonalUserId();

    /**
     * Returns the managed user profile id iff there are at least 2 user profiles for current user.
     * Otherwise, returns null.
     */
    @Nullable
    UserId getManagedUserId();

    /**
     * Returns the current user profile id. This can be managed user profile id, personal user
     * profile id. If the user does not have a corresponding managed profile, then this always
     * returns the current user.
     */
    @Nullable
    UserId getCurrentUserProfileId();

    void setCurrentUserProfileId(UserId userId);

    /**
     * Whether the current user is the personal user profile iff there are at least 2 user
     * profiles for current user. Otherwise, returns false.
     */
    boolean isPersonalUserId();

    /**
     * Whether the current user is the managed user profile iff there are at least 2 user
     * profiles for current user. Otherwise, returns false.
     */
    boolean isManagedUserId();

    /**
     * Creates an implementation of {@link UserIdManager}.
     */
    static UserIdManager create(Context context) {
        return new RuntimeUserIdManager(context);
    }

    /**
     * Implementation of {@link UserIdManager}.
     */
    final class RuntimeUserIdManager implements UserIdManager {

        private static final String TAG = "UserIdManager";

        private final Context mContext;
        private final UserId mCurrentUser;

        @GuardedBy("mLock")
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private UserId mPersonalUser = null;
        @GuardedBy("mLock")
        private UserId mManagedUser = null;

        @GuardedBy("mLock")
        private UserId mCurrentUserProfile = null;

        private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (mLock) {
                    mPersonalUser = null;
                    mManagedUser = null;
                    setUserIds();
                }
            }
        };

        private RuntimeUserIdManager(Context context) {
            this(context, UserId.CURRENT_USER);
        }

        @VisibleForTesting
        RuntimeUserIdManager(Context context, UserId currentUser) {
            mContext = context.getApplicationContext();
            mCurrentUser = checkNotNull(currentUser);
            mCurrentUserProfile = mCurrentUser;
            setUserIds();

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
            mContext.registerReceiver(mIntentReceiver, filter);
        }

        @Override
        public boolean isMultiUserProfiles() {
            synchronized (mLock) {
                return mPersonalUser != null;
            }
        }

        @Override
        public UserId getPersonalUserId() {
            synchronized (mLock) {
                return mPersonalUser;
            }
        }

        @Override
        public UserId getManagedUserId() {
            synchronized (mLock) {
                return mManagedUser;
            }
        }

        @Override
        public UserId getCurrentUserProfileId() {
            synchronized (mLock) {
                return mCurrentUserProfile;
            }
        }

        @Override
        public void setCurrentUserProfileId(UserId userId) {
            synchronized (mLock) {
                mCurrentUserProfile = userId;
            }
        }

        @Override
        public boolean isPersonalUserId() {
            return mCurrentUser.equals(getPersonalUserId());
        }

        @Override
        public boolean isManagedUserId() {
            return mCurrentUser.equals(getManagedUserId());
        }

        private void setUserIds() {
            synchronized (mLock) {
                setUserIdsInternal();
            }
        }

        @GuardedBy("mLock")
        private void setUserIdsInternal() {
            UserManager userManager =  mContext.getSystemService(UserManager.class);
            if (userManager == null) {
                Log.e(TAG, "Cannot obtain user manager");
                return;
            }

            final List<UserHandle> userProfiles = userManager.getUserProfiles();
            if (userProfiles.size() < 2) {
                Log.d(TAG, "Only 1 user profile found");
                return;
            }

            if (mCurrentUser.isManagedProfile(userManager)) {
                final UserId managedUser = mCurrentUser;
                final UserHandle parentUser =
                        userManager.getProfileParent(managedUser.getUserHandle());
                if (parentUser != null) {
                    mPersonalUser = UserId.of(parentUser);
                    mManagedUser = managedUser;
                }

            } else {
                final UserId personalUser = mCurrentUser;
                // Check if this personal profile is a parent of any other managed profile.
                for (UserHandle userHandle : userProfiles) {
                    if (userManager.isManagedProfile(userHandle.getIdentifier())) {
                        final UserHandle parentUser =
                                userManager.getProfileParent(userHandle);
                        if (parentUser != null &&
                                parentUser.equals(personalUser.getUserHandle())) {
                            mPersonalUser = personalUser;
                            mManagedUser = UserId.of(userHandle);
                        }
                    }
                }
            }
        }
    }
}
