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

package com.android.photopicker.features.privacyexplainer

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.banners.BannerStateDao
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.mockito.nonNullableEq
import com.android.photopicker.tests.utils.mockito.whenever
import dagger.Lazy
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
import kotlinx.coroutines.test.advanceTimeBy
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
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class PrivacyExplainerFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider

    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var databaseManager: DatabaseManager
    @Inject override lateinit var configurationManager: ConfigurationManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        // Stub out the content resolver for Glide
        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
    }

    @Test
    fun testPrivacyExplainerBannerIsShown() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)
            whenever(bannerStateDao.getBannerState(anyString(), anyInt())) { null }

            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            val expectedPrivacyMessage =
                resources.getString(R.string.photopicker_privacy_explainer, "Test Package")

            bannerManager.get().refreshBanners()
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                    bannerManager = bannerManager.get(),
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedPrivacyMessage)).assertIsDisplayed()
        }

    @Test
    fun testPrivacyExplainerBannerIsHiddenWhenDismissed() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            whenever(bannerStateDao.getBannerState(anyString(), anyInt())) { null }
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt()
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345
                )
            }
            // Mock out database state with previously dismissed state.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
            advanceTimeBy(1000)
            val resources = getTestableContext().getResources()
            val expectedPrivacyMessage =
                resources.getString(R.string.photopicker_privacy_explainer, "Test Package")

            bannerManager.get().refreshBanners()
            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                    bannerManager = bannerManager.get(),
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedPrivacyMessage)).assertIsNotDisplayed()
        }
}
