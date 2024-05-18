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

package com.android.photopicker.features.profileselector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserProperties
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.configuration.testActionPickImagesConfiguration
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectorViewModelTest {

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    private val mockContentResolver: MockContentResolver = MockContentResolver()

    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10

    init {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
        parcel1.recycle()

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)
        parcel2.recycle()
    }

    val TEST_MEDIA_IMAGE =
        Media.Image(
            mediaId = "id",
            pickerId = 1000L,
            authority = "a",
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("media")
                        path("picker")
                        path("a")
                        path("id")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority(MockContentProviderWrapper.AUTHORITY)
                        path("id")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
        )

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockUserManager.getUserProperties(any(UserHandle::class.java))) {
            UserProperties.Builder()
                .setCrossProfileContentSharingStrategy(
                    UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
                )
                .build()
        }

        // Stubs for UserMonitor
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }
        whenever(mockUserManager.getUserBadge()) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .getDrawable(R.drawable.android, /* theme= */ null)
        }
        whenever(mockUserManager.getProfileLabel()) { "label" }
        val mockResolveInfo = mock(ResolveInfo::class.java)
        whenever(mockResolveInfo.isCrossProfileIntentForwarderActivity()) { true }
        whenever(mockPackageManager.queryIntentActivities(any(Intent::class.java), anyInt())) {
            listOf(mockResolveInfo)
        }
    }

    /** Ensure the view model exposes the correct values via flows for the UI to consume */
    @Test
    fun testExposesUserProfileFlowsForUi() {

        runTest {
            whenever(mockUserManager.userProfiles) {
                listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED)
            }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

            val selection =
                Selection<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope)
                )

            val viewModel =
                ProfileSelectorViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        provideTestConfigurationFlow(
                            scope = this.backgroundScope,
                            defaultConfiguration = testActionPickImagesConfiguration,
                        ),
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            assertWithMessage("Expected available number of profiles to be 2.")
                .that(viewModel.allProfiles.value.size)
                .isEqualTo(2)

            assertWithMessage("Expected both primary and managed profiles to exist")
                .that(viewModel.allProfiles.value.map { it.profileType })
                .isEqualTo(listOf(UserProfile.ProfileType.PRIMARY, UserProfile.ProfileType.MANAGED))

            assertWithMessage("Expected both primary and managed profiles to exist")
                .that(viewModel.selectedProfile.value.profileType)
                .isEqualTo(UserProfile.ProfileType.PRIMARY)
        }
    }

    /** Ensures the view model clears the selection when the profile is changed. */
    @Test
    fun testProfileSwitchClearsSelection() {

        runTest {
            whenever(mockUserManager.userProfiles) {
                listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED)
            }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

            val selection =
                Selection<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope)
                )

            val viewModel =
                ProfileSelectorViewModel(
                    this.backgroundScope,
                    selection,
                    UserMonitor(
                        mockContext,
                        provideTestConfigurationFlow(
                            scope = this.backgroundScope,
                            defaultConfiguration = testActionPickImagesConfiguration,
                        ),
                        this.backgroundScope,
                        StandardTestDispatcher(this.testScheduler),
                        USER_HANDLE_PRIMARY
                    ),
                )

            selection.add(TEST_MEDIA_IMAGE)

            val managedProfile =
                viewModel.allProfiles.value.find {
                    it.profileType == UserProfile.ProfileType.MANAGED
                }
            checkNotNull(managedProfile) { "Expected a managed profile to exist!" }

            viewModel.requestSwitchUser(requested = managedProfile, context = mockContext)

            advanceTimeBy(100)

            assertWithMessage("Expected selection to be empty").that(selection.snapshot()).isEmpty()
        }
    }
}
