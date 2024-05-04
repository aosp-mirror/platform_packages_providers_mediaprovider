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

package com.android.photopicker.features.profileselector

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.mockSystemService
import com.android.photopicker.tests.utils.mockito.whenever
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class ProfileSelectorFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    @BindValue @Main val mainScope: TestScope = TestScope(testDispatcher)
    @BindValue @Background var testBackgroundScope: CoroutineScope = mainScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = mainScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle

    val contentResolver: ContentResolver = MockContentResolver()

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10

    init {

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)
        parcel2.recycle()
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()
        // Stubs for UserMonitor
        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockContext.contentResolver) { contentResolver }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "com.android.photopicker" }

        // Recursively return the same mockContext for all user packages to keep the stubing simple.
        whenever(
            mockContext.createPackageContextAsUser(
                anyString(),
                anyInt(),
                any(UserHandle::class.java)
            )
        ) {
            mockContext
        }
    }

    @Test
    fun testProfileSelectorIsShownWithMultipleProfiles() =
        mainScope.runTest {

            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_switch_button_description)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testProfileSelectorIsNotShownOnlyOneProfile() =
        mainScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_switch_button_description)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    fun testAvailableProfilesAreDisplayed() =
        mainScope.runTest {
            val resources = getTestableContext().getResources()

            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_profile_switch_button_description)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
                .performClick()

            // Ensure personal profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_primary_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()

            // Ensure managed profile option exists
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_profile_managed_label)))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }
}
