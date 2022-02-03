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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.util.CrossProfileUtils;

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

    /**
     * Set Managed User as current user profile
     */
    void setManagedAsCurrentUserProfile();

    /**
     * Set Personal User as current user profile
     */
    void setPersonalAsCurrentUserProfile();

    /**
     * @return true iff current user is the current user profile selected
     */
    boolean isCurrentUserSelected();

    /**
     * @return true iff managed user is the current user profile selected
     */
    boolean isManagedUserSelected();

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
     * Whether the current user is allowed to access other profile data.
     */
    boolean isCrossProfileAllowed();

    /**
     * Whether cross profile access is blocked by admin for the current user.
     */
    boolean isBlockedByAdmin();

    /**
     * Whether the work profile corresponding to the current user is turned off.
     */
    boolean isWorkProfileOff();

    /**
     * Set intent to check for device admin policy.
     */
    void setIntentAndCheckRestrictions(Intent intent);

    /**
     * Waits for Media Provider of the work profile to be available.
     */
    @WorkerThread
    void waitForMediaProviderToBeAvailable();

    /**
     * Checks if work profile is switched off and updates the data.
     */
    @WorkerThread
    void updateWorkProfileOffValue();

    /**
     * Resets the user ids. This is usually called as a result of receiving broadcast that
     * managed profile has been added or removed.
     */
    void resetUserIds();

    /**
     * @return {@link MutableLiveData} to check if cross profile interaction allowed or not
     */
    @NonNull
    MutableLiveData<Boolean> getCrossProfileAllowed();

    /**
     * @return {@link MutableLiveData} to check if there are multiple user profiles or not
     */
    @NonNull
    MutableLiveData<Boolean> getIsMultiUserProfiles();

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

        // These values are copied from DocumentsUI
        private static final int PROVIDER_AVAILABILITY_MAX_RETRIES = 10;
        private static final long PROVIDER_AVAILABILITY_CHECK_DELAY = 4000;

        private final Context mContext;
        private final UserId mCurrentUser;
        private final Handler mHandler;

        private Runnable mIsProviderAvailableRunnable;

        @GuardedBy("mLock")
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private UserId mPersonalUser = null;
        @GuardedBy("mLock")
        private UserId mManagedUser = null;

        @GuardedBy("mLock")
        private UserId mCurrentUserProfile = null;

        // Set default values to negative case, only set as false if checks pass.
        private boolean mIsBlockedByAdmin = true;
        private boolean mIsWorkProfileOff = true;

        private final MutableLiveData<Boolean> mIsMultiUserProfiles = new MutableLiveData<>();
        private final MutableLiveData<Boolean> mIsCrossProfileAllowed = new MutableLiveData<>();

        private RuntimeUserIdManager(Context context) {
            this(context, UserId.CURRENT_USER);
        }

        @VisibleForTesting
        RuntimeUserIdManager(Context context, UserId currentUser) {
            mContext = context.getApplicationContext();
            mCurrentUser = checkNotNull(currentUser);
            mCurrentUserProfile = mCurrentUser;
            mHandler = new Handler(Looper.getMainLooper());
            setUserIds();
        }

        @Override
        public MutableLiveData<Boolean> getCrossProfileAllowed() {
            return mIsCrossProfileAllowed;
        }

        @Override
        public MutableLiveData<Boolean> getIsMultiUserProfiles() {
            return mIsMultiUserProfiles;
        }

        @Override
        public void resetUserIds() {
            synchronized (mLock) {
                setUserIds();
            }
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
        public void setManagedAsCurrentUserProfile() {
            setCurrentUserProfileId(getManagedUserId());
        }

        @Override
        public void setPersonalAsCurrentUserProfile() {
            setCurrentUserProfileId(getPersonalUserId());
        }

        @Override
        public void setIntentAndCheckRestrictions(Intent intent) {
            if (isMultiUserProfiles()) {
                updateCrossProfileValues(intent);
            }
        }

        public boolean isCurrentUserSelected() {
            synchronized (mLock) {
                return mCurrentUserProfile.equals(UserId.CURRENT_USER);
            }
        }

        public boolean isManagedUserSelected() {
            synchronized (mLock) {
                return mCurrentUserProfile.equals(getManagedUserId());
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
                setUserIdsInternalLocked();
            }
            mIsMultiUserProfiles.postValue(isMultiUserProfiles());
        }

        private void setCurrentUserProfileId(UserId userId) {
            synchronized (mLock) {
                mCurrentUserProfile = userId;
            }
        }

        @GuardedBy("mLock")
        private void setUserIdsInternalLocked() {
            mPersonalUser = null;
            mManagedUser = null;
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

        @Override
        public boolean isCrossProfileAllowed() {
            return (!isWorkProfileOff() && !isBlockedByAdmin());
        }

        @Override
        public boolean isWorkProfileOff() {
            return mIsWorkProfileOff;
        }

        @Override
        public boolean isBlockedByAdmin() {
            return mIsBlockedByAdmin;
        }

        @Override
        public void updateWorkProfileOffValue() {
            mIsWorkProfileOff = isWorkProfileOffInternal(getManagedUserId());
            mIsCrossProfileAllowed.postValue(isCrossProfileAllowed());
        }

        @Override
        public void waitForMediaProviderToBeAvailable() {
            final UserId managedUserProfileId = getManagedUserId();
            if (CrossProfileUtils.isMediaProviderAvailable(managedUserProfileId, mContext)) {
                mIsWorkProfileOff = false;
                mIsCrossProfileAllowed.postValue(isCrossProfileAllowed());
                stopWaitingForProviderToBeAvailable();
                return;
            }
            waitForProviderToBeAvailable(managedUserProfileId, /* numOfTries */ 1);
        }

        private void updateCrossProfileValues(Intent intent) {
            setCrossProfileValues(intent);
            mIsCrossProfileAllowed.postValue(isCrossProfileAllowed());
        }

        private void setCrossProfileValues(Intent intent) {
            // 1. Check if PICK_IMAGES intent is allowed by admin to show cross user content
            setBlockedByAdminValue(intent);

            // 2. Check if work profile is off
            updateWorkProfileOffValue();

            // 3. For first initial setup, wait for MediaProvider to be on.
            // (This is not blocking)
            if (mIsWorkProfileOff) {
                waitForMediaProviderToBeAvailable();
            }
        }

        private void setBlockedByAdminValue(Intent intent) {
            if (intent == null) {
                Log.e(TAG, "No intent specified to check if cross profile forwarding is"
                        + " allowed.");
                return;
            }
            final PackageManager packageManager = mContext.getPackageManager();
            if (!CrossProfileUtils.isIntentAllowedCrossProfileAccess(intent, packageManager)) {
                mIsBlockedByAdmin = true;
                return;
            }
            mIsBlockedByAdmin = false;
        }

        private boolean isWorkProfileOffInternal(UserId managedUserProfileId) {
            return CrossProfileUtils.isQuietModeEnabled(managedUserProfileId, mContext) ||
                    !CrossProfileUtils.isMediaProviderAvailable(managedUserProfileId, mContext);
        }

        private void waitForProviderToBeAvailable(UserId userId, int numOfTries) {
            // The runnable should make sure to post update on the live data if it is changed.
            mIsProviderAvailableRunnable = () -> {
                // We stop the recursive check when
                // 1. the provider is available
                // 2. the profile is in quiet mode, i.e. provider will not be available
                // 3. after maximum retries
                if (CrossProfileUtils.isMediaProviderAvailable(userId, mContext)) {
                    mIsWorkProfileOff = false;
                    mIsCrossProfileAllowed.postValue(isCrossProfileAllowed());
                    return;
                }

                if (CrossProfileUtils.isQuietModeEnabled(userId, mContext)) {
                    return;
                }

                if (numOfTries <= PROVIDER_AVAILABILITY_MAX_RETRIES) {
                    Log.d(TAG, "MediaProvider is not available. Retry after " +
                            PROVIDER_AVAILABILITY_CHECK_DELAY);
                    waitForProviderToBeAvailable(userId, numOfTries + 1);
                    return;
                }

                Log.w(TAG, "Failed waiting for MediaProvider for user:" + userId +
                        " to be available");
            };

            mHandler.postDelayed(mIsProviderAvailableRunnable, PROVIDER_AVAILABILITY_CHECK_DELAY);
        }

        private void stopWaitingForProviderToBeAvailable() {
            if (mIsProviderAvailableRunnable == null) {
                return;
            }
            mHandler.removeCallbacks(mIsProviderAvailableRunnable);
            mIsProviderAvailableRunnable = null;
        }
    }
}
