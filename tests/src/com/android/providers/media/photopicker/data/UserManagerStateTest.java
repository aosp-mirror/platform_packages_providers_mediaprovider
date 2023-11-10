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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserProperties;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.model.UserId;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

@SdkSuppress(minSdkVersion = 31, codeName = "S")
public class UserManagerStateTest {
    private final UserHandle mPersonalUser = UserHandle.SYSTEM;
    private final UserHandle mManagedUser = UserHandle.of(100); // like a managed profile
    private final UserHandle mOtherUser1 = UserHandle.of(101); // like a private profile
    private final UserHandle mOtherUser2 = UserHandle.of(102); // like a clone profile
    private final UserHandle mOtherUser3 = UserHandle.of(103); //  like a invalid user
    private final Context mMockContext = mock(Context.class);
    private final UserManager mMockUserManager = mock(UserManager.class);
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private UserManagerState mUserManagerState;

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);

        // set Managed Profile identification
        when(mMockUserManager.isManagedProfile(
                mManagedUser.getIdentifier())).thenReturn(true);
        when(mMockUserManager.isManagedProfile(
                mPersonalUser.getIdentifier())).thenReturn(false);
        when(mMockUserManager.isManagedProfile(
                mOtherUser1.getIdentifier())).thenReturn(false);
        when(mMockUserManager.isManagedProfile(
                mOtherUser2.getIdentifier())).thenReturn(false);
        when(mMockUserManager.isManagedProfile(
                mOtherUser3.getIdentifier())).thenReturn(false);

        // set all user profile parents
        when(mMockUserManager.getProfileParent(mPersonalUser)).thenReturn(null);
        when(mMockUserManager.getProfileParent(mManagedUser)).thenReturn(mPersonalUser);
        when(mMockUserManager.getProfileParent(mOtherUser1)).thenReturn(mPersonalUser);
        when(mMockUserManager.getProfileParent(mOtherUser2)).thenReturn(mPersonalUser);
        when(mMockUserManager.getProfileParent(mOtherUser3)).thenReturn(mPersonalUser);

        if (SdkLevel.isAtLeastV()) {
            //Personal user
            UserProperties mPersonalUserProperties = new UserProperties.Builder().build();

            UserProperties mManagedUserProperties = new UserProperties.Builder() // managed user
                    .setShowInSharingSurfaces(UserProperties.SHOW_IN_SHARING_SURFACES_SEPARATE)
                    .setCrossProfileContentSharingStrategy(
                            UserProperties.CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION)
                    .setShowInQuietMode(UserProperties.SHOW_IN_QUIET_MODE_PAUSED)
                    .build();

            UserProperties mOtherUser1Properties = new UserProperties.Builder() // private user
                    .setShowInSharingSurfaces(UserProperties.SHOW_IN_SHARING_SURFACES_SEPARATE)
                    .setCrossProfileContentSharingStrategy(
                            UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT)
                    .setShowInQuietMode(UserProperties.SHOW_IN_QUIET_MODE_HIDDEN)
                    .build();

            UserProperties mOtherUser2Properties = new UserProperties.Builder() // clone user
                    .setShowInSharingSurfaces(UserProperties.SHOW_IN_SHARING_SURFACES_WITH_PARENT)
                    .setCrossProfileContentSharingStrategy(
                            UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT)
                    .setShowInQuietMode(UserProperties.SHOW_IN_QUIET_MODE_DEFAULT)
                    .build();

            // get user properties
            when(mMockUserManager.getUserProperties(mPersonalUser))
                    .thenReturn(mPersonalUserProperties);
            when(mMockUserManager.getUserProperties(mManagedUser))
                    .thenReturn(mManagedUserProperties);
            when(mMockUserManager.getUserProperties(mOtherUser1)).thenReturn(mOtherUser1Properties);
            when(mMockUserManager.getUserProperties(mOtherUser2)).thenReturn(mOtherUser2Properties);
        }

        when(mMockContext.getSystemServiceName(UserManager.class)).thenReturn(
                "mockUserManager");
        when(mMockContext.getSystemService(UserManager.class)).thenReturn(mMockUserManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
    }

    @Test
    public void testUserManagerStateThrowsErrorIfCalledFromNonMainThread() {
        initializeUserManagerState(UserId.of(mPersonalUser),
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));
        assertThrows(IllegalStateException.class, () -> mUserManagerState.isMultiUserProfiles());
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.isManagedUserProfile(UserId.of(mManagedUser)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.getCurrentUserProfileId());
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.getCrossProfileAllowedStatusForAll());
        assertThrows(IllegalStateException.class, () -> mUserManagerState.getAllUserProfileIds());
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.updateProfileOffValues());
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.waitForMediaProviderToBeAvailable(
                        UserId.of(mPersonalUser)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.isCrossProfileAllowedToUser(UserId.of(mManagedUser)));
        assertThrows(IllegalStateException.class, () -> mUserManagerState.resetUserIds());
        assertThrows(IllegalStateException.class, () -> mUserManagerState.isCurrentUserSelected());
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.isBlockedByAdmin(UserId.of(mManagedUser)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.isProfileOff(UserId.of(mManagedUser)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.getShowInQuietMode(UserId.of(mOtherUser1)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mManagedUser)));
        assertThrows(IllegalStateException.class,
                () -> mUserManagerState.isUserSelectedAsCurrentUserProfile(
                        UserId.of(mPersonalUser)));
    }

    @Test
    public void testGetAllUserProfileIdsThatNeedToShowInPhotoPicker_currentUserIsPersonalUser() {
        initializeUserManagerState(UserId.of(mPersonalUser),
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));

            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);
        });
    }

    @Test
    public void testGetAllUserProfileIdsThatNeedToShowInPhotoPicker_currentUserIsManagedUser() {
        initializeUserManagerState(UserId.of(mManagedUser),
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));

            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);
        });
    }
    @Test
    public void testGetAllUserProfileIdsThatNeedToShowInPhotoPicker_currentUserIsOtherUser1() {
        initializeUserManagerState(UserId.of(mOtherUser1),
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);
        });
    }

    @Test
    public void testUserIds_singleUserProfileAvailable() {
        // if current user is personal and no other profile is available
        UserId currentUser = UserId.of(mPersonalUser);
        initializeUserManagerState(currentUser, Arrays.asList(mPersonalUser));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(mUserManagerState.isMultiUserProfiles()).isFalse();

            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mPersonalUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(Arrays.asList(UserId.of(mPersonalUser)));
        });
    }


    @Test
    public void testUserIds_multiUserProfilesAvailable_currentUserIsPersonalUser() {
        UserId currentUser = UserId.of(mPersonalUser);

        // if available user profiles are {personal , managed, otherUser1 }
        initializeUserManagerState(currentUser,
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(mUserManagerState.isMultiUserProfiles()).isTrue();

            assertThat(mUserManagerState.getCurrentUserProfileId()).isEqualTo(UserId.of(
                    mPersonalUser));

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);

            assertThat(mUserManagerState.isManagedUserProfile(
                    mUserManagerState.getCurrentUserProfileId())).isFalse();
        });

        // if available user profiles are {personal , otherUser1 }
        initializeUserManagerState(currentUser, Arrays.asList(mPersonalUser, mOtherUser1));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {

            if (SdkLevel.isAtLeastV()) {
                assertThat(mUserManagerState.isMultiUserProfiles()).isTrue();
            } else {
                assertThat(mUserManagerState.isMultiUserProfiles()).isFalse();
            }

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);
        });


        // if available user profiles are {personal , otherUser2 }
        initializeUserManagerState(currentUser, Arrays.asList(mPersonalUser, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(mUserManagerState.isMultiUserProfiles()).isFalse();

            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mPersonalUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(Arrays.asList(UserId.of(mPersonalUser)));
        });
    }

    @Test
    public void testUserIds_multiUserProfilesAvailable_currentUserIsOtherUser2() {
        UserId currentUser = UserId.of(mOtherUser2);

        initializeUserManagerState(currentUser,
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(mUserManagerState.isMultiUserProfiles()).isTrue();
            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mOtherUser2));

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);

        });

        initializeUserManagerState(currentUser, Arrays.asList(mPersonalUser, mOtherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(mUserManagerState.isMultiUserProfiles()).isFalse();
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(Arrays.asList(UserId.of(mPersonalUser)));

        });
    }

    @Test
    public void testCurrentUser_AfterSettingASpecificUserAsCurrentUser() {
        UserId currentUser = UserId.of(mPersonalUser);
        initializeUserManagerState(currentUser,
                Arrays.asList(mPersonalUser, mManagedUser, mOtherUser1, mOtherUser2));

        // set current user as managed profile
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mManagedUser));

            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mManagedUser));
            assertThat(mUserManagerState.isManagedUserProfile(
                    mUserManagerState.getCurrentUserProfileId())).isTrue();
        });

        // set current user as otherUser2
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mOtherUser2));
            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mManagedUser));
        });

        // set current user as otherUser1
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mOtherUser1));
            UserHandle currentUserProfile = SdkLevel.isAtLeastV() ? mOtherUser1 : mManagedUser;
            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(currentUserProfile));
        });

        // set current user as personalUser
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mPersonalUser));
            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mPersonalUser));
        });

        // set current user otherUser3
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState.setUserAsCurrentUserProfile(UserId.of(mOtherUser3));
            assertThat(mUserManagerState.getCurrentUserProfileId())
                    .isEqualTo(UserId.of(mPersonalUser));

            List<UserId> userIdList = SdkLevel.isAtLeastV() ? Arrays.asList(
                    UserId.of(mPersonalUser), UserId.of(mManagedUser), UserId.of(mOtherUser1))
                    : Arrays.asList(UserId.of(mPersonalUser), UserId.of(mManagedUser));
            assertThat(mUserManagerState.getAllUserProfileIds())
                    .containsExactlyElementsIn(userIdList);
        });

    }



    private void initializeUserManagerState(UserId current, List<UserHandle> usersOnDevice) {
        when(mMockUserManager.getUserProfiles()).thenReturn(usersOnDevice);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mUserManagerState = new UserManagerState.RuntimeUserManagerState(mMockContext, current);
        });
    }
}
