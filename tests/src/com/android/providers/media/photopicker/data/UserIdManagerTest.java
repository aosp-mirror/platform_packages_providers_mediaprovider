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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.model.UserId;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class UserIdManagerTest {
    private final UserHandle personalUser = UserHandle.SYSTEM;
    private final UserHandle managedUser1 = UserHandle.of(100);
    // otherUser1 and otherUser2 are users without any work (aka managed) profile.
    private final UserHandle otherUser1 = UserHandle.of(200);
    private final UserHandle otherUser2 = UserHandle.of(201);

    private final Context mockContext = mock(Context.class);
    private final UserManager mockUserManager = mock(UserManager.class);

    private UserIdManager userIdManager;

    @Before
    public void setUp() throws Exception {
        when(mockContext.getApplicationContext()).thenReturn(mockContext);

        when(mockUserManager.isManagedProfile(managedUser1.getIdentifier())).thenReturn(true);
        when(mockUserManager.isManagedProfile(personalUser.getIdentifier())).thenReturn(false);
        when(mockUserManager.getProfileParent(managedUser1)).thenReturn(personalUser);
        when(mockUserManager.isManagedProfile(otherUser1.getIdentifier())).thenReturn(false);
        when(mockUserManager.isManagedProfile(otherUser2.getIdentifier())).thenReturn(false);

        when(mockContext.getSystemServiceName(UserManager.class)).thenReturn("mockUserManager");
        when(mockContext.getSystemService(UserManager.class)).thenReturn(mockUserManager);
    }

    @Test
    public void testUserIdManagerThrowsErrorIfCalledFromNonMainThread() {
        UserId currentUser = UserId.of(personalUser);
        initializeUserIdManager(currentUser, Arrays.asList(personalUser));

        assertThrows(IllegalStateException.class, () -> userIdManager.isMultiUserProfiles());
        assertThrows(IllegalStateException.class, () -> userIdManager.getCurrentUserProfileId());
        assertThrows(IllegalStateException.class, () -> userIdManager.isPersonalUserId());
        assertThrows(IllegalStateException.class, () -> userIdManager.isManagedUserId());
        assertThrows(IllegalStateException.class, () -> userIdManager.getPersonalUserId());
        assertThrows(IllegalStateException.class, () -> userIdManager.getManagedUserId());
    }

    // common cases for User Profiles
    @Test
    public void testUserIds_personaUser_currentUserIsPersonalUser() {
        // Returns the current user if there is only 1 user.
        UserId currentUser = UserId.of(personalUser);
        initializeUserIdManager(currentUser, Arrays.asList(personalUser));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isFalse();

            assertThat(userIdManager.isPersonalUserId()).isFalse();
            assertThat(userIdManager.isManagedUserId()).isFalse();

            assertThat(userIdManager.getPersonalUserId()).isNull();
            assertThat(userIdManager.getManagedUserId()).isNull();
        });
    }

    @Test
    public void testUserIds_personalUserAndManagedUser_currentUserIsPersonalUser() {
        // Returns both if there are personal and managed users.
        UserId currentUser = UserId.of(personalUser);
        initializeUserIdManager(currentUser, Arrays.asList(personalUser, managedUser1));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isTrue();

            assertThat(userIdManager.isPersonalUserId()).isTrue();
            assertThat(userIdManager.isManagedUserId()).isFalse();

            assertThat(userIdManager.getPersonalUserId()).isEqualTo(currentUser);
            assertThat(userIdManager.getManagedUserId()).isEqualTo(UserId.of(managedUser1));
        });
    }

    @Test
    public void testUserIds_personalUserAndManagedUser_currentUserIsManagedUser() {
        // Returns both if there are system and managed users.
        UserId currentUser = UserId.of(managedUser1);
        initializeUserIdManager(currentUser, Arrays.asList(personalUser, managedUser1));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isTrue();

            assertThat(userIdManager.isPersonalUserId()).isFalse();
            assertThat(userIdManager.isManagedUserId()).isTrue();

            assertThat(userIdManager.getPersonalUserId()).isEqualTo(UserId.of(personalUser));
            assertThat(userIdManager.getManagedUserId()).isEqualTo(currentUser);
        });
    }

    // other cases for User Profiles involving different users
    @Test
    public void testUserIds_otherUsers_currentUserIsOtherUser2() {
        // When there is no managed user, returns the current user.
        UserId currentUser = UserId.of(otherUser2);
        initializeUserIdManager(currentUser, Arrays.asList(otherUser1, otherUser2));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isFalse();

            assertThat(userIdManager.isPersonalUserId()).isFalse();
            assertThat(userIdManager.isManagedUserId()).isFalse();

            assertThat(userIdManager.getPersonalUserId()).isNull();
            assertThat(userIdManager.getManagedUserId()).isNull();
        });
    }

    @Test
    public void testUserIds_otherUserAndManagedUserAndPersonalUser_currentUserIsOtherUser() {
        UserId currentUser = UserId.of(otherUser1);
        initializeUserIdManager(currentUser, Arrays.asList(otherUser1, managedUser1,
                personalUser));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isFalse();

            assertThat(userIdManager.isPersonalUserId()).isFalse();
            assertThat(userIdManager.isManagedUserId()).isFalse();

            assertThat(userIdManager.getPersonalUserId()).isNull();
            assertThat(userIdManager.getManagedUserId()).isNull();
        });
    }

    @Test
    public void testGetUserIds_otherUserAndManagedUser_currentUserIsManagedUser() {
        // When there is no system user, returns the current user.
        // This is a case theoretically can happen but we don't expect. So we return the current
        // user only.
        UserId currentUser = UserId.of(managedUser1);
        initializeUserIdManager(currentUser, Arrays.asList(otherUser1, managedUser1,
                personalUser));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.isMultiUserProfiles()).isTrue();

            assertThat(userIdManager.isPersonalUserId()).isFalse();
            assertThat(userIdManager.isManagedUserId()).isTrue();

            assertThat(userIdManager.getPersonalUserId()).isEqualTo(UserId.of(personalUser));
            assertThat(userIdManager.getManagedUserId()).isEqualTo(currentUser);
        });
    }

    @Test
    public void testUserIds_personalUserAndManagedUser_returnCachedList() {
        UserId currentUser = UserId.of(personalUser);
        initializeUserIdManager(currentUser, Arrays.asList(personalUser, managedUser1));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertThat(userIdManager.getPersonalUserId()).isSameInstanceAs(
                    userIdManager.getPersonalUserId());
            assertThat(userIdManager.getManagedUserId()).isSameInstanceAs(
                    userIdManager.getManagedUserId());
        });
    }

    private void initializeUserIdManager(UserId current, List<UserHandle> usersOnDevice) {
        when(mockUserManager.getUserProfiles()).thenReturn(usersOnDevice);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            userIdManager = new UserIdManager.RuntimeUserIdManager(mockContext, current);
        });
    }
}
