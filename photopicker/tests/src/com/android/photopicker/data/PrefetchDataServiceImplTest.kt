/*
 * Copyright (C) 2024 The Android Open Source Project
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

package src.com.android.photopicker.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserProperties
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.PrefetchDataServiceImpl
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.features.search.model.GlobalSearchState
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class PrefetchDataServiceImplTest {
    @Mock private lateinit var mockPrimaryUserContext: Context
    @Mock private lateinit var mockManagedUserContext: Context
    @Mock private lateinit var mockUserManager: UserManager
    @Mock private lateinit var mockPackageManager: PackageManager
    @Mock private lateinit var mockResolveInfo: ResolveInfo

    private lateinit var testPrimaryUserContentProvider: TestMediaProvider
    private lateinit var testManagedUserContentProvider: TestMediaProvider
    private lateinit var testPrimaryUserContentResolver: ContentResolver
    private lateinit var testManagedUserContentResolver: ContentResolver

    private val PLATFORM_PROVIDED_PROFILE_LABEL = "Platform Label"

    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0
    private val PRIMARY_PROFILE_BASE: UserProfile

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MANAGED_PROFILE_BASE: UserProfile

    init {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
        parcel1.recycle()

        PRIMARY_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_PRIMARY,
                profileType = UserProfile.ProfileType.PRIMARY,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)
        parcel2.recycle()

        MANAGED_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_MANAGED,
                profileType = UserProfile.ProfileType.MANAGED,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()

        testPrimaryUserContentProvider = TestMediaProvider()
        testPrimaryUserContentResolver = ContentResolver.wrap(testPrimaryUserContentProvider)
        testManagedUserContentProvider = TestMediaProvider()
        testManagedUserContentResolver = ContentResolver.wrap(testManagedUserContentProvider)

        mockSystemService(mockPrimaryUserContext, UserManager::class.java) { mockUserManager }
        mockSystemService(mockManagedUserContext, UserManager::class.java) { mockUserManager }

        whenever(mockPrimaryUserContext.packageManager) { mockPackageManager }
        whenever(mockPrimaryUserContext.contentResolver) { testPrimaryUserContentResolver }
        whenever(mockManagedUserContext.contentResolver) { testManagedUserContentResolver }
        whenever(
            mockPrimaryUserContext.createPackageContextAsUser(
                any(),
                anyInt(),
                nonNullableEq(USER_HANDLE_PRIMARY),
            )
        ) {
            mockPrimaryUserContext
        }
        whenever(
            mockPrimaryUserContext.createPackageContextAsUser(
                any(),
                anyInt(),
                nonNullableEq(USER_HANDLE_MANAGED),
            )
        ) {
            mockManagedUserContext
        }
        whenever(
            mockManagedUserContext.createPackageContextAsUser(
                any(),
                anyInt(),
                nonNullableEq(USER_HANDLE_PRIMARY),
            )
        ) {
            mockPrimaryUserContext
        }
        whenever(
            mockManagedUserContext.createPackageContextAsUser(
                any(),
                anyInt(),
                nonNullableEq(USER_HANDLE_MANAGED),
            )
        ) {
            mockManagedUserContext
        }
        whenever(
            mockPrimaryUserContext.createContextAsUser(nonNullableEq(USER_HANDLE_PRIMARY), anyInt())
        ) {
            mockPrimaryUserContext
        }
        whenever(
            mockPrimaryUserContext.createContextAsUser(nonNullableEq(USER_HANDLE_MANAGED), anyInt())
        ) {
            mockManagedUserContext
        }
        whenever(
            mockManagedUserContext.createContextAsUser(nonNullableEq(USER_HANDLE_PRIMARY), anyInt())
        ) {
            mockPrimaryUserContext
        }
        whenever(
            mockManagedUserContext.createContextAsUser(nonNullableEq(USER_HANDLE_MANAGED), anyInt())
        ) {
            mockManagedUserContext
        }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED) }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

        whenever(mockResolveInfo.isCrossProfileIntentForwarderActivity()) { true }
        whenever(mockPackageManager.queryIntentActivities(any(Intent::class.java), anyInt())) {
            listOf(mockResolveInfo)
        }

        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(mockUserManager.getProfileLabel()) { PLATFORM_PROVIDED_PROFILE_LABEL }
            whenever(mockUserManager.getUserProperties(USER_HANDLE_PRIMARY)) {
                UserProperties.Builder().build()
            }
            // By default, allow managed profile to be available
            whenever(mockUserManager.getUserProperties(USER_HANDLE_MANAGED)) {
                UserProperties.Builder()
                    .setCrossProfileContentSharingStrategy(
                        UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
                    )
                    .build()
            }
        }
    }

    @Test
    fun testGetGlobalSearchStateEnabled() = runTest {
        testManagedUserContentProvider.searchProviders = listOf()

        val userMonitor =
            UserMonitor(
                mockPrimaryUserContext,
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ),
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                USER_HANDLE_PRIMARY,
            )

        val prefetchDataService =
            PrefetchDataServiceImpl(
                MediaProviderClient(),
                userMonitor,
                mockPrimaryUserContext,
                StandardTestDispatcher(this.testScheduler),
                backgroundScope,
            )

        val globalSearchState = prefetchDataService.getGlobalSearchState()

        assertWithMessage("Global search state is not enabled")
            .that(globalSearchState)
            .isEqualTo(GlobalSearchState.ENABLED)
    }

    @Test
    fun testGetGlobalSearchStateEnabledInOtherProfiles() = runTest {
        val userMonitor =
            UserMonitor(
                mockPrimaryUserContext,
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ),
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                USER_HANDLE_PRIMARY,
            )

        val prefetchDataService =
            PrefetchDataServiceImpl(
                MediaProviderClient(),
                userMonitor,
                mockPrimaryUserContext,
                StandardTestDispatcher(this.testScheduler),
                backgroundScope,
            )

        testPrimaryUserContentProvider.searchProviders = listOf()
        val globalSearchState1 = prefetchDataService.getGlobalSearchState()
        assertWithMessage("Global search state is not enabled in other profiles")
            .that(globalSearchState1)
            .isEqualTo(GlobalSearchState.ENABLED_IN_OTHER_PROFILES_ONLY)

        testPrimaryUserContentProvider.searchProviders = null
        val globalSearchState2 = prefetchDataService.getGlobalSearchState()
        assertWithMessage("Global search state is not enabled in other profiles")
            .that(globalSearchState2)
            .isEqualTo(GlobalSearchState.ENABLED_IN_OTHER_PROFILES_ONLY)
    }

    @Test
    fun testGetGlobalSearchStateUnknown() = runTest {
        val userMonitor =
            UserMonitor(
                mockPrimaryUserContext,
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ),
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                USER_HANDLE_PRIMARY,
            )

        val prefetchDataService =
            PrefetchDataServiceImpl(
                MediaProviderClient(),
                userMonitor,
                mockPrimaryUserContext,
                StandardTestDispatcher(this.testScheduler),
                backgroundScope,
            )

        testPrimaryUserContentProvider.searchProviders = listOf()
        testManagedUserContentProvider.searchProviders = null
        val globalSearchState = prefetchDataService.getGlobalSearchState()

        assertWithMessage("Global search state is not enabled in other profiles")
            .that(globalSearchState)
            .isEqualTo(GlobalSearchState.UNKNOWN)
    }

    @Test
    fun testGetGlobalSearchStateDisabled() = runTest {
        testPrimaryUserContentProvider.searchProviders = listOf()
        testManagedUserContentProvider.searchProviders = listOf()

        val userMonitor =
            UserMonitor(
                mockPrimaryUserContext,
                provideTestConfigurationFlow(
                    scope = this.backgroundScope,
                    defaultConfiguration =
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ),
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                USER_HANDLE_PRIMARY,
            )

        val prefetchDataService =
            PrefetchDataServiceImpl(
                MediaProviderClient(),
                userMonitor,
                mockPrimaryUserContext,
                StandardTestDispatcher(this.testScheduler),
                backgroundScope,
            )

        val globalSearchState = prefetchDataService.getGlobalSearchState()

        assertWithMessage("Global search state is not enabled in other profiles")
            .that(globalSearchState)
            .isEqualTo(GlobalSearchState.DISABLED)
    }
}
