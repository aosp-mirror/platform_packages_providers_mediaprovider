/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.user

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.tests.utils.mockito.capture
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Unit tests for the [UserMonitor] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UserMonitorTest {

    val USER_HANDLE_PRIMARY: UserHandle
    val USER_ID_PRIMARY: Int = 0
    val PRIMARY_PROFILE_BASE =
        UserProfile(identifier = USER_ID_PRIMARY, profileType = UserProfile.ProfileType.PRIMARY)

    val USER_HANDLE_MANAGED: UserHandle
    val USER_ID_MANAGED: Int = 10
    val MANAGED_PROFILE_BASE =
        UserProfile(identifier = USER_ID_MANAGED, profileType = UserProfile.ProfileType.MANAGED)

    val initialExpectedStatus: UserStatus

    lateinit var userMonitor: UserMonitor

    @Mock lateinit var context: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Captor lateinit var broadcastReceiver: ArgumentCaptor<BroadcastReceiver>
    @Captor lateinit var intentFilter: ArgumentCaptor<IntentFilter>
    @Captor lateinit var flag: ArgumentCaptor<Int>

    init {

        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)

        initialExpectedStatus =
            UserStatus(
                activeUserProfile = PRIMARY_PROFILE_BASE,
                allProfiles = listOf(PRIMARY_PROFILE_BASE, MANAGED_PROFILE_BASE)
            )
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mockSystemService(context, UserManager::class.java) { mockUserManager }
        whenever(context.getPackageManager()) { mockPackageManager }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.getUserProfiles()) {
            listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED)
        }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }
    }

    /** Ensures the initial [UserStatus] is emitted before any Broadcasts are received. */
    @Test
    fun testInitialUserStatusIsAvailable() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            launch {
                val reportedStatus = userMonitor.userStatus.first()
                assertThat(reportedStatus).isEqualTo(initialExpectedStatus)
            }
        }
    }

    /**
     * Ensures that a [BroadcastReceiver] is registered to listen for profile changes Note: This
     * test is forked for SdkLevel R and earlier devices since [Intent.ACTION_PROFILE_ACCESSIBLE]
     * and [Intent.ACTION_PROFILE_INACCESSIBLE] isn't available until SdkLevel 31.
     */
    @Test
    fun testRegistersBroadcastReceiverSdkRMinus() {

        assumeFalse(SdkLevel.isAtLeastS())

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            launch {
                val reportedStatus = userMonitor.userStatus.first()
                assertThat(reportedStatus).isEqualTo(initialExpectedStatus)
            }
            advanceTimeBy(100)
            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()
            val filter: IntentFilter = intentFilter.getValue()
            val flagValue: Int = flag.getValue()

            assertThat(receiver).isNotNull()
            assertThat(filter.countActions()).isEqualTo(2)
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)).isTrue()
            assertThat(flagValue).isEqualTo(0x4)
        }
    }

    /**
     * Ensures that a [BroadcastReceiver] is registered to listen for profile changes Note: This
     * test is forked for SdkLevel S devices since [Context.RECEIVER_NOT_EXPORTED] isn't available
     * until SdkLevel 33.
     */
    @Test
    fun testRegistersBroadcastReceiverSdkS() {

        assumeTrue(SdkLevel.isAtLeastS())
        assumeFalse(SdkLevel.isAtLeastT())

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            launch {
                val reportedStatus = userMonitor.userStatus.first()
                assertThat(reportedStatus).isEqualTo(initialExpectedStatus)
            }
            advanceTimeBy(100)
            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()
            val filter: IntentFilter = intentFilter.getValue()
            val flagValue: Int = flag.getValue()

            assertThat(receiver).isNotNull()
            assertThat(filter.countActions()).isEqualTo(4)
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_PROFILE_ACCESSIBLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_PROFILE_INACCESSIBLE)).isTrue()
            assertThat(flagValue).isEqualTo(0x4)
        }
    }

    /**
     * Ensures that a [BroadcastReceiver] is registered to listen for profile changes Note: This
     * test is forked for SdkLevel T and later devices.
     */
    @Test
    fun testRegistersBroadcastReceiverSdkTPlus() {

        assumeTrue(SdkLevel.isAtLeastT())

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            launch {
                val reportedStatus = userMonitor.userStatus.first()
                assertThat(reportedStatus).isEqualTo(initialExpectedStatus)
            }
            advanceTimeBy(100)
            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()
            val filter: IntentFilter = intentFilter.getValue()
            val flagValue: Int = flag.getValue()

            assertThat(receiver).isNotNull()
            assertThat(filter.countActions()).isEqualTo(4)
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_PROFILE_ACCESSIBLE)).isTrue()
            assertThat(filter.matchAction(Intent.ACTION_PROFILE_INACCESSIBLE)).isTrue()
            assertThat(flagValue).isEqualTo(Context.RECEIVER_NOT_EXPORTED)
        }
    }

    /** Ensures that the [BroadcastReceiver] updates the state. */
    @Test
    fun testUpdatesUserStatusOnBroadcast() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()

            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)
            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()

            // Simulate the Work profile being disabled
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { true }
            val intent = Intent()
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            intent.putExtra(Intent.EXTRA_USER, USER_HANDLE_MANAGED)
            receiver.onReceive(context, intent)

            advanceTimeBy(100)

            val expectedUpdatedStatus =
                UserStatus(
                    activeUserProfile = PRIMARY_PROFILE_BASE,
                    allProfiles =
                        listOf(PRIMARY_PROFILE_BASE, MANAGED_PROFILE_BASE.copy(enabled = false))
                )

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.get(0)).isEqualTo(initialExpectedStatus)
            assertThat(emissions.get(1)).isEqualTo(expectedUpdatedStatus)
        }
    }

    /** Ensures that duplicate Broadcasts don't result in duplicate emissions */
    @Test
    fun testDuplicateBroadcastsDontEmitNewState() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()

            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)
            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()

            // Simulate the Work profile being disabled
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { true }
            val intent = Intent()
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            intent.putExtra(Intent.EXTRA_USER, USER_HANDLE_MANAGED)
            receiver.onReceive(context, intent)

            advanceTimeBy(100)

            // A new state should be emitted for the disabled profile.
            assertThat(emissions.size).isEqualTo(2)

            // Send a duplicate broadcast with the other action
            val intent2 = Intent()
            intent2.setAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            intent2.putExtra(Intent.EXTRA_USER, USER_HANDLE_MANAGED)
            receiver.onReceive(context, intent2)

            advanceTimeBy(100)

            // There should still only be two emissions.
            assertThat(emissions.size).isEqualTo(2)
        }
    }

    /** Ensures that a requested profile switch succeeds, and updates subscribers with new state. */
    @Test
    fun testRequestSwitchActiveUserProfileSuccess() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()
            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)

            backgroundScope.launch {
                val switchResult =
                    userMonitor.requestSwitchActiveUserProfile(
                        UserProfile(identifier = USER_ID_MANAGED)
                    )
                assertThat(switchResult).isEqualTo(SwitchUserProfileResult.SUCCESS)
            }

            advanceTimeBy(100)

            val expectedStatus =
                UserStatus(
                    activeUserProfile = MANAGED_PROFILE_BASE,
                    allProfiles = listOf(PRIMARY_PROFILE_BASE, MANAGED_PROFILE_BASE)
                )

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.get(0)).isEqualTo(initialExpectedStatus)
            assertThat(emissions.get(1)).isEqualTo(expectedStatus)
        }
    }

    /** Ensures that a disabled profile cannot be made the active profile */
    @Test
    fun testRequestSwitchActiveUserProfileDisabled() {

        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { true }

        val initialState =
            UserStatus(
                activeUserProfile =
                    UserProfile(
                        identifier = USER_ID_PRIMARY,
                        profileType = UserProfile.ProfileType.PRIMARY,
                        enabled = true
                    ),
                allProfiles =
                    listOf(
                        UserProfile(
                            identifier = USER_ID_PRIMARY,
                            profileType = UserProfile.ProfileType.PRIMARY,
                            enabled = true
                        ),
                        UserProfile(
                            identifier = USER_ID_MANAGED,
                            profileType = UserProfile.ProfileType.MANAGED,
                            enabled = false
                        )
                    )
            )

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()
            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)

            backgroundScope.launch {
                val switchResult =
                    userMonitor.requestSwitchActiveUserProfile(
                        UserProfile(identifier = USER_ID_MANAGED)
                    )
                assertThat(switchResult).isEqualTo(SwitchUserProfileResult.FAILED_PROFILE_DISABLED)
            }

            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.get(0)).isEqualTo(initialState)
        }
    }

    /** Ensures that only known profiles can be made the active profile. */
    @Test
    fun testRequestSwitchActiveUserProfileUnknown() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()
            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)

            backgroundScope.launch {
                val switchResult =
                    userMonitor.requestSwitchActiveUserProfile(UserProfile(identifier = 999))
                assertThat(switchResult).isEqualTo(SwitchUserProfileResult.FAILED_UNKNOWN_PROFILE)
            }

            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.get(0)).isEqualTo(initialExpectedStatus)
        }
    }

    /**
     * Ensures that if the active profile becomes disabled, the active profile reverts to the
     * process owner profile.
     */
    @Test
    fun testActiveProfileBecomesDisabled() {

        runTest { // this: TestScope
            userMonitor =
                UserMonitor(
                    context,
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY
                )

            val emissions = mutableListOf<UserStatus>()
            backgroundScope.launch { userMonitor.userStatus.toList(emissions) }
            advanceTimeBy(100)

            verify(context)
                .registerReceiver(capture(broadcastReceiver), capture(intentFilter), capture(flag))

            backgroundScope.launch {
                val switchResult =
                    userMonitor.requestSwitchActiveUserProfile(
                        UserProfile(identifier = USER_ID_MANAGED)
                    )
                assertThat(switchResult).isEqualTo(SwitchUserProfileResult.SUCCESS)
            }

            advanceTimeBy(100)
            assertThat(emissions.last().activeUserProfile).isEqualTo(MANAGED_PROFILE_BASE)

            val receiver: BroadcastReceiver = broadcastReceiver.getValue()

            // Simulate the Work profile being disabled
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { true }
            val intent = Intent()
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            intent.putExtra(Intent.EXTRA_USER, USER_HANDLE_MANAGED)
            receiver.onReceive(context, intent)
            advanceTimeBy(100)

            assertThat(emissions.last().activeUserProfile).isEqualTo(PRIMARY_PROFILE_BASE)
        }
    }
}
