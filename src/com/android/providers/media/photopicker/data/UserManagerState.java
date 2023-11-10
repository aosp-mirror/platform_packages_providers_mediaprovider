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
import android.annotation.RequiresApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.TabFragment;
import com.android.providers.media.photopicker.util.CrossProfileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiresApi(Build.VERSION_CODES.S)
/*
 * Interface to query user ids {@link UserId}
 */
public interface UserManagerState {
    /**
     * Whether there are more than 1 user profiles associated with the current user.
     */
    boolean isMultiUserProfiles();

    /**
     * @return if the given userId is a ManagedUserProfileId.
     */
    boolean isManagedUserProfile(UserId userId);

    /**
     * Returns the user profile selected in the photopicker. If the user does not have a
     * corresponding child profile, then this always returns the current user.
     */
    @Nullable
    UserId getCurrentUserProfileId();

    /**
     * A Map of all the profiles with their cross profile allowed status from current user.
     * key : userId of a profile
     * Value : cross profile allowed status of a user profile corresponding to user id with
     * current user .
     */
    @NonNull
    Map<UserId, Boolean> getCrossProfileAllowedStatusForAll();

    /**
     * A {@link MutableLiveData} to check if cross profile interaction allowed or not.
     */
    @NonNull
    MutableLiveData<Map<UserId, Boolean>> getCrossProfileAllowed();

    /**
     * A list of all user profile ids including current user that need to be shown
     * separately in PhotoPicker
     */
    @NonNull
    List<UserId> getAllUserProfileIds();

    /**
     * Updates on/off values of all the user profiles other than current user profile
     */
    void updateProfileOffValues();

    /**
     * Waits for Media Provider of the user profile corresponding to userId to be available.
     */
    void waitForMediaProviderToBeAvailable(UserId userId);

    /**
     * Get if it is allowed to access the otherUser profile from current user ( current user :
     * the user profile that started the photo picker activity)
     **/
    @NonNull
    boolean isCrossProfileAllowedToUser(UserId otherUser);

    /**
     * A {@link MutableLiveData} to check if there are multiple user profiles or not
     */
    @NonNull
    MutableLiveData<Boolean> getIsMultiUserProfiles();

    /**
     * Resets the user ids. This is usually called as a result of receiving broadcast that
     * any profile has been added or removed.
     */
    void resetUserIds();

    /**
     * Resets the user ids and set their cross profile values. This is usually called as a result
     * of receiving broadcast that any profile has been added or removed.
     */
    void resetUserIdsAndSetCrossProfileValues(Intent intent);

    /**
     * @return true if current user is the current user profile selected
     */
    boolean isCurrentUserSelected();

    /**
     * Checks device admin policy for cross-profile sharing for intent. It Also updates profile off
     * and blocked by admin status for all user profiles present on the device.
     */
    void setIntentAndCheckRestrictions(Intent intent);

    /**
     * Whether cross profile access corresponding to the userID is blocked
     * by admin for the current user.
     */
    boolean isBlockedByAdmin(UserId userId);

    /**
     * Whether profile corresponding to the userID is on or off.
     */
    boolean isProfileOff(UserId userId);

    /**
     * A map of all user profile labels corresponding to all profile userIds
     */
    Map<UserId, String> getProfileLabelsForAll();

    /**
     * Returns whether a user should be shown in the PhotoPicker depending on its quite mode status.
     *
     * @return One of {@link UserProperties.SHOW_IN_QUIET_MODE_PAUSED},
     *         {@link UserProperties.SHOW_IN_QUIET_MODE_HIDDEN}, or
     *         {@link UserProperties.SHOW_IN_QUIET_MODE_DEFAULT} depending on whether the profile
     *         should be shown in quiet mode or not.
     */
    int getShowInQuietMode(UserId userId);

    /**
     * A map of all user profile Icon ids corresponding to all profile userIds
     */
    Map<UserId, Drawable> getProfileBadgeForAll();

    /**
     * Set a user as a current user profile
     **/
    void setUserAsCurrentUserProfile(UserId userId);

    /**
     * @return true if provided user is the current user profile selected
     */
    boolean isUserSelectedAsCurrentUserProfile(UserId userId);

    /**
     * Creates an implementation of {@link UserManagerState}.
     * Todo(b/319067964): make this singleton
     */
    static UserManagerState create(Context context) {
        return new RuntimeUserManagerState(context);
    }

    /**
     * Implementation of {@link UserManagerState}. The class assumes that all its public methods are
     * called from main thread only.
     */
    final class RuntimeUserManagerState implements UserManagerState {

        private static final String TAG = "UserManagerState";
        private static final int PROVIDER_AVAILABILITY_MAX_RETRIES = 10;
        private static final long PROVIDER_AVAILABILITY_CHECK_DELAY = 4000;
        private static final int SHOW_IN_QUIET_MODE_DEFAULT = -1;

        private final Context mContext;
        // This is the user profile that started the photo picker activity. That's why it cannot
        // change in a UserIdManager instance.
        private final UserId mCurrentUser;
        private final Handler mHandler;

        private Runnable mIsProviderAvailableRunnable;

        // This is the user profile selected in the photo picker. Photo picker will display media
        // for this user. It could be different from mCurrentUser.
        private UserId mCurrentUserProfile = null;

        // A map of user profile ids (Except current user) with a Boolean value that represents
        // whether corresponding user profile is blocked by admin or not.
        private Map<UserId , Boolean> mIsProfileBlockedByAdminMap = new HashMap<>();

        // A map of user profile ids (Except current user) with a Boolean value that represents
        // whether corresponding user profile is on or off.
        private Map<UserId , Boolean> mProfileOffStatus = new HashMap<>();
        private final MutableLiveData<Boolean> mIsMultiUserProfiles = new MutableLiveData<>();

        // A list of all user profile Ids present on the device that require a separate tab to show
        // in PhotoPicker. It also includes currentUser/contextUser.
        private List<UserId> mUserProfileIds = new ArrayList<>();
        private UserManager mUserManager;

        /**
         * This live data will be posted every time when a user profile change occurs in the
         * background such as turning on/off/adding/removing a user profile. The complete map
         * will be reinitiated again in {@link #getCrossProfileAllowedStatusForAll()} and will
         * be posted into the below mutable live data. This live data will be observed later in
         * {@link TabFragment}.
         **/
        private final MutableLiveData<Map<UserId, Boolean>> mCrossProfileAllowedStatus =
                new MutableLiveData<>();

        private RuntimeUserManagerState(Context context) {
            this(context, UserId.CURRENT_USER);
        }

        @VisibleForTesting
        RuntimeUserManagerState(Context context, UserId currentUser) {
            mContext = context.getApplicationContext();
            mCurrentUser = checkNotNull(currentUser);
            mCurrentUserProfile = mCurrentUser;
            mHandler = new Handler(Looper.getMainLooper());
            mUserManager = mContext.getSystemService(UserManager.class);
            setUserIds();
        }

        private UserId getSystemUser() {
            return UserId.of(UserHandle.of(ActivityManager.getCurrentUser()));
        }

        private void setUserIds() {
            setUserIdsInternal();
            mIsMultiUserProfiles.postValue(isMultiUserProfiles());
        }

        private void setUserIdsInternal() {
            mUserProfileIds.clear();
            mUserProfileIds.add(getSystemUser());
            if (mUserManager == null) {
                Log.e(TAG, "Cannot obtain user manager");
                return;
            }

            // Here there could be other profiles too , that we do not want to show  anywhere in
            // photo picker at all.
            final List<UserHandle> userProfiles = mUserManager.getUserProfiles();
            if (SdkLevel.isAtLeastV()) {
                for (UserHandle userHandle : userProfiles) {
                    UserProperties userProperties = mUserManager.getUserProperties(userHandle);
                    UserId userId = UserId.of(userHandle);

                    // Check if we want to show this profile data in PhotoPicker or if it is
                    // an owner profile itself.
                    if (getSystemUser().getIdentifier() != userHandle.getIdentifier()
                            && userProperties.getShowInSharingSurfaces()
                            == userProperties.SHOW_IN_SHARING_SURFACES_SEPARATE) {
                        mUserProfileIds.add(userId);
                    }
                }
            } else {
                // if sdk version is less than V, then maximum two profiles with separate tab could
                // only be available
                for (UserHandle userHandle : userProfiles) {
                    if (mUserManager.isManagedProfile(userHandle.getIdentifier())) {
                        mUserProfileIds.add(UserId.of(userHandle));
                    }
                }
            }
        }

        @Override
        public boolean isMultiUserProfiles() {
            assertMainThread();
            return mUserProfileIds.size() > 1;
        }

        @Override
        public MutableLiveData<Map<UserId, Boolean>> getCrossProfileAllowed() {
            return mCrossProfileAllowedStatus;
        }

        @Override
        public Map<UserId, Boolean> getCrossProfileAllowedStatusForAll() {
            assertMainThread();
            Map<UserId, Boolean> crossProfileAllowedStatusForAll = new HashMap<>();
            for (UserId userId : mUserProfileIds) {
                crossProfileAllowedStatusForAll.put(userId, isCrossProfileAllowedToUser(userId));
            }
            return crossProfileAllowedStatusForAll;
        }

        @Override
        public boolean isCrossProfileAllowedToUser(UserId otherUser) {
            assertMainThread();
            return !isProfileOff(otherUser) && !isBlockedByAdmin(otherUser);
        }

        @Override
        public MutableLiveData<Boolean> getIsMultiUserProfiles() {
            return mIsMultiUserProfiles;
        }

        @Override
        public void resetUserIds() {
            assertMainThread();
            setUserIds();
        }

        @Override
        public void resetUserIdsAndSetCrossProfileValues(Intent intent) {
            assertMainThread();
            setUserIdsInternal();
            setCrossProfileValues(intent);
            mIsMultiUserProfiles.postValue(isMultiUserProfiles());
        }

        @Override
        public boolean isCurrentUserSelected() {
            assertMainThread();
            return mCurrentUserProfile.equals(UserId.CURRENT_USER);
        }

        @Override
        public void setIntentAndCheckRestrictions(Intent intent) {
            assertMainThread();
            if (isMultiUserProfiles()) {
                updateCrossProfileValues(intent);
            }
        }

        @Override
        public UserId getCurrentUserProfileId() {
            assertMainThread();
            return mCurrentUserProfile;
        }

        /**
         * we need the information of personal and managed user to get the pre-exiting label and
         * icon of user profile ids in case while working with pre-v version.
         */
        @Override
        public boolean isManagedUserProfile(UserId userId) {
            assertMainThread();
            if (mUserManager == null) {
                Log.e(TAG, "Cannot obtain user manager");
                return false;
            }
            return userId.isManagedProfile(mUserManager);
        }

        @Override
        public void setUserAsCurrentUserProfile(UserId userId) {
            assertMainThread();
            if (!mUserProfileIds.contains(userId)) {
                Log.e(TAG, userId + " is not a valid user profile");
                return;
            }
            setCurrentUserProfileId(userId);
        }

        @Override
        public boolean isUserSelectedAsCurrentUserProfile(UserId userId) {
            assertMainThread();
            return mCurrentUserProfile.equals(userId);
        }

        private void setCurrentUserProfileId(UserId userId) {
            mCurrentUserProfile = userId;
        }

        private void updateCrossProfileValues(Intent intent) {
            setCrossProfileValues(intent);
            updateAndPostCrossProfileStatus();
        }

        private void setCrossProfileValues(Intent intent) {
            // 1. Check if PICK_IMAGES intent is allowed by admin to show cross user content
            setBlockedByAdminValue(intent);

            // 2. Check if work profile is off
            updateProfileOffValues();

            // 3. For first initial setup, wait for MediaProvider to be on.
            // (This is not blocking)
            for (UserId userId : mUserProfileIds) {
                if (mProfileOffStatus.get(userId)) {
                    waitForMediaProviderToBeAvailable(userId);
                }
            }
        }
        @Override
        public void waitForMediaProviderToBeAvailable(UserId userId) {
            assertMainThread();
            if (CrossProfileUtils.isMediaProviderAvailable(userId , mContext)) {
                mProfileOffStatus.put(userId, false);
                updateAndPostCrossProfileStatus();
                return;
            }
            waitForProviderToBeAvailable(userId, /* numOfTries */ 1);
        }

        private void waitForProviderToBeAvailable(UserId userId, int numOfTries) {
            // The runnable should make sure to post update on the live data if it is changed.
            mIsProviderAvailableRunnable = () -> {
                // We stop the recursive check when
                // 1. the provider is available
                // 2. the profile is in quiet mode, i.e. provider will not be available
                // 3. after maximum retries
                if (CrossProfileUtils.isMediaProviderAvailable(userId, mContext)) {
                    mProfileOffStatus.put(userId, false);
                    updateAndPostCrossProfileStatus();
                    return;
                }

                if (CrossProfileUtils.isQuietModeEnabled(userId, mContext)) {
                    return;
                }

                if (numOfTries <= PROVIDER_AVAILABILITY_MAX_RETRIES) {
                    Log.d(TAG, "MediaProvider is not available. Retry after "
                            + PROVIDER_AVAILABILITY_CHECK_DELAY);
                    waitForProviderToBeAvailable(userId, numOfTries + 1);
                    return;
                }

                Log.w(TAG, "Failed waiting for MediaProvider for user:" + userId
                        + " to be available");
            };

            mHandler.postDelayed(mIsProviderAvailableRunnable, PROVIDER_AVAILABILITY_CHECK_DELAY);
        }

        // Todo(b/319561515): Modify method to remove callbacks only for specified user
        private void stopWaitingForProviderToBeAvailable() {
            if (mIsProviderAvailableRunnable == null) {
                return;
            }
            mHandler.removeCallbacks(mIsProviderAvailableRunnable);
            mIsProviderAvailableRunnable = null;
        }

        @Override
        public void updateProfileOffValues() {
            assertMainThread();
            mProfileOffStatus.clear();
            for (UserId userId : mUserProfileIds) {
                mProfileOffStatus.put(userId, isProfileOffInternal(userId));
            }
            updateAndPostCrossProfileStatus();
        }

        private void updateAndPostCrossProfileStatus() {
            mCrossProfileAllowedStatus.postValue(getCrossProfileAllowedStatusForAll());
        }

        private Boolean isProfileOffInternal(UserId userId) {
            return CrossProfileUtils.isQuietModeEnabled(userId, mContext)
                    || !CrossProfileUtils.isMediaProviderAvailable(userId, mContext);
        }

        private boolean isCrossProfileStrategyDelegatedToParent(UserHandle userHandle) {
            if (SdkLevel.isAtLeastV()) {
                if (mUserManager == null) {
                    Log.e(TAG, "Cannot obtain user manager");
                    return false;
                }
                UserProperties userProperties = mUserManager.getUserProperties(userHandle);
                if (userProperties.getCrossProfileContentSharingStrategy()
                        == userProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT) {
                    return true;
                }
            }
            return false;
        }

        private UserHandle getProfileToCheckCrossProfileAccess(UserHandle userHandle) {
            if (mUserManager == null) {
                Log.e(TAG, "Cannot obtain user manager");
                return null;
            }
            return isCrossProfileStrategyDelegatedToParent(userHandle)
                    ? mUserManager.getProfileParent(userHandle) : userHandle;
        }


        /**
         * {@link #setBlockedByAdminValue(Intent)} Based on  assumption that the only profiles with
         * {@link UserProperties.CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION} could be systemUser
         * and managedUser(if available).
         *
         * Todo(b/319567023):Refactor the below {@link #setBlockedByAdminValue(Intent)} to
         * avoid assumptions mentioned above.
         */
        private void setBlockedByAdminValue(Intent intent) {
            if (intent == null) {
                Log.e(TAG, "No intent specified to check if cross profile forwarding is"
                        + " allowed.");
                return;
            }

            // List of all user profile ids that context user cannot access
            List<UserId> canNotForwardToUserProfiles = new ArrayList<>();

            /*
             * List of all user profile ids that have cross profile access among themselves.
             * It contains parent user and child profiles with user property
             * {@link UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT}
             */
            List<UserId> parentOrDelegatedFromParent = new ArrayList<>();

            // Userprofile to check cross profile intentForwarderActivity for
            UserHandle needToCheck = null;

            if (mUserManager == null) {
                Log.e(TAG, "Cannot obtain user manager");
                return;
            }

            for (UserId userId : mUserProfileIds) {
                /*
                 * All user profiles with user property
                 * {@link UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT}
                 * can access each other including its parent.
                 */
                if (userId.equals(getSystemUser())
                        || isCrossProfileStrategyDelegatedToParent(userId.getUserHandle())) {
                    parentOrDelegatedFromParent.add(userId);
                } else {
                    needToCheck = userId.getUserHandle();
                }
            }

            // When context user is a managed user , then will replace needToCheck with its parent
            // to check cross profile intentForwarderActivity for.
            if (needToCheck != null && needToCheck.equals(mCurrentUser.getUserHandle())) {
                needToCheck = mUserManager.getProfileParent(mCurrentUser.getUserHandle());
            }

            final PackageManager packageManager = mContext.getPackageManager();
            if (needToCheck != null && !CrossProfileUtils.isIntentAllowedCrossProfileAccessFromUser(
                    intent, packageManager,
                    getProfileToCheckCrossProfileAccess(mCurrentUser.getUserHandle()))) {
                if (parentOrDelegatedFromParent.contains(UserId.of(needToCheck))) {
                    // if user profile cannot access its parent then all direct child profiles with
                    // delegated from parent will also be inaccessible.
                    canNotForwardToUserProfiles.addAll(parentOrDelegatedFromParent);
                } else {
                    canNotForwardToUserProfiles.add(UserId.of(needToCheck));
                }
            }

            mIsProfileBlockedByAdminMap.clear();
            for (UserId userId : mUserProfileIds) {
                mIsProfileBlockedByAdminMap.put(userId,
                        canNotForwardToUserProfiles.contains(userId));
            }
        }

        @Override
        public Map<UserId, String> getProfileLabelsForAll() {
            assertMainThread();
            Map<UserId, String> profileLabels = new HashMap<>();
            String personalTabLabel = mContext.getString(R.string.picker_personal_profile_label);
            profileLabels.put(getSystemUser(), personalTabLabel);
            if (SdkLevel.isAtLeastV()) {
                for (UserId userId : mUserProfileIds) {
                    UserHandle userHandle = userId.getUserHandle();
                    if (userHandle.getIdentifier() != getSystemUser().getIdentifier()) {
                        profileLabels.put(userId, getProfileLabel(userHandle));
                    }
                }
            }

            return profileLabels;
        }
        private String getProfileLabel(UserHandle userHandle) {
            if (SdkLevel.isAtLeastV()) {
                Context userContext = mContext.createContextAsUser(userHandle, 0 /* flags */);
                try {
                    UserManager userManager = userContext.getSystemService(UserManager.class);
                    if (userManager == null) {
                        Log.e(TAG, "Cannot obtain user manager");
                        return null;
                    }
                    return userManager.getProfileLabel();
                } catch (Resources.NotFoundException e) {
                    //Todo(b/318530691): Handle the label for the profile that is not defined
                    // already
                }
            }
            return null;
        }

        @Override
        public Map<UserId, Drawable> getProfileBadgeForAll() {
            assertMainThread();
            Map<UserId, Drawable> profileBadges = new HashMap<>();
            profileBadges.put(getSystemUser(), mContext.getDrawable(R.drawable.ic_personal_mode));
            if (SdkLevel.isAtLeastV()) {
                for (UserId userId : mUserProfileIds) {
                    UserHandle userHandle = userId.getUserHandle();
                    if (userHandle.getIdentifier() != getSystemUser().getIdentifier()) {
                        profileBadges.put(userId, getProfileBadge(userHandle));
                    }
                }
            }
            return profileBadges;
        }

        private Drawable getProfileBadge(UserHandle userHandle) {
            if (SdkLevel.isAtLeastV()) {
                Context userContext = mContext.createContextAsUser(userHandle, 0 /* flags */);
                try {
                    UserManager userManager = userContext.getSystemService(UserManager.class);
                    if (userManager == null) {
                        Log.e(TAG, "Cannot obtain user manager");
                        return null;
                    }
                    return userManager.getUserBadge();
                } catch (Resources.NotFoundException e) {
                    //Todo(b/318530691): Handle the icon for the profile that is not defined already
                }
            }
            return null;
        }

        @Override
        public int getShowInQuietMode(UserId userId) {
            assertMainThread();
            if (SdkLevel.isAtLeastV()) {
                if (mUserManager == null) {
                    Log.e(TAG, "Cannot obtain user manager");
                    return UserProperties.SHOW_IN_QUIET_MODE_DEFAULT;
                }
                UserProperties userProperties =
                        mUserManager.getUserProperties(userId.getUserHandle());
                return userProperties.getShowInQuietMode();
            }
            return SHOW_IN_QUIET_MODE_DEFAULT;
        }

        @Override
        public List<UserId> getAllUserProfileIds() {
            assertMainThread();
            return mUserProfileIds;
        }

        @Override
        public boolean isBlockedByAdmin(UserId userId) {
            assertMainThread();
            return mIsProfileBlockedByAdminMap.get(userId);
        }
        @Override
        public boolean isProfileOff(UserId userId) {
            assertMainThread();
            return mProfileOffStatus.get(userId);
        }

        private void assertMainThread() {
            if (Looper.getMainLooper().isCurrentThread()) return;

            throw new IllegalStateException("UserManagerState methods are expected to be called"
                    + "from main thread. " + (Looper.myLooper() == null ? "" : "Current thread "
                    + Looper.myLooper().getThread() + ", Main thread "
                    + Looper.getMainLooper().getThread()));
        }
    }
}
