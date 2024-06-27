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

package com.android.photopicker.core.banners

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.database.DatabaseManagerTestImpl
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.tests.utils.mockito.nonNullableEq
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Unit tests for the [BannerManagerImpl] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BannerManagerImplTest {

    // Isolate the test device by providing a test wrapper around device config so that the
    // tests can control the flag values that are returned.
    val deviceConfigProxy = TestDeviceConfigProxyImpl()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        deviceConfigProxy.reset()
    }

    /**
     * Ensures that the [BannerManagerImpl] does not emits any Banner when all features are
     * disabled.
     */
    @Test
    fun testEmitsNoBannerWhenNoFeaturesEnabled() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    emptySet<FeatureRegistration>(),
                )

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = DatabaseManagerTestImpl(),
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            assertWithMessage("Expected no banner to be emitted")
                .that(bannerManager.flow.value)
                .isNull()
        }
    }

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    fun testEmitsCorrectBannerByPriority() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }
            bannerManager.refreshBanners()

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.flow.value?.declaration)
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)
        }
    }

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    fun testEmitsCorrectBannerByPriorityPreviouslyDismissed() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out the database state for PRIVACY_EXPLAINER and mark it as previously
            // dismissed.
            whenever(
                databaseManager.bannerState.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt()
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    uid = 0,
                    dismissed = true,
                )
            }

            bannerManager.refreshBanners()

            // Ensure BannerManager fetches the database state for the banner, with the correct uid
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.PRIVACY_EXPLAINER.id, 12345)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.flow.value)
                .isNull()
        }
    }

    /** Ensures that the [BannerManagerImpl] emits the highest priority Banner. */
    @Test
    fun testEmitsHighestPriorityBanner() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanners()

            // Ensure BannerManager fetches the database state for the banner, with the correct uids
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.PRIVACY_EXPLAINER.id, 12345)
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id, 0)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.flow.value?.declaration)
                .isEqualTo(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)
        }
    }

    /** Ensures that the [BannerManagerImpl] immediately shows the requested banner. */
    @Test
    fun testShowBanner() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            assertWithMessage("Initial banner was not null.")
                .that(bannerManager.flow.value)
                .isNull()

            bannerManager.showBanner(BannerDefinitions.PRIVACY_EXPLAINER)

            assertWithMessage("Incorrect banner was shown.")
                .that(bannerManager.flow.value?.declaration)
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)
        }
    }

    /** Ensures that the [BannerManagerImpl] immediately hides the shown banner. */
    @Test
    fun testHideBanner() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            assertWithMessage("Initial banner was not null.")
                .that(bannerManager.flow.value)
                .isNull()

            bannerManager.showBanner(BannerDefinitions.PRIVACY_EXPLAINER)

            assertWithMessage("Incorrect banner was shown.")
                .that(bannerManager.flow.value?.declaration)
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)

            bannerManager.hideBanners()

            assertWithMessage("Expected current banner to be null.")
                .that(bannerManager.flow.value)
                .isNull()
        }
    }

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the once dismissal strategy.
     */
    @Test
    fun testMarkBannerAsDismissedOnceStrategy() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            bannerManager.markBannerAsDismissed(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)
            verify(databaseManager.bannerState)
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id,
                        uid = 0,
                        dismissed = true,
                    )
                )
        }
    }

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the per uid dismissal
     * strategy.
     */
    @Test
    fun testMarkBannerAsDismissedPerUidStrategy() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            bannerManager.markBannerAsDismissed(BannerDefinitions.PRIVACY_EXPLAINER)
            verify(databaseManager.bannerState)
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                        uid = 12345,
                        dismissed = true,
                    )
                )
        }
    }

    /** Ensures that the [BannerManagerImpl] never shows banners with a priority less than zero. */
    @Test
    fun testIgnoresBannersWithNegativePriority() {

        runTest {
            // Mock out a feature and provide a fake registration that provides the mock.
            val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
            val mockRegistration =
                object : FeatureRegistration {
                    override val TAG = "MockedFeature"

                    override fun isEnabled(config: PhotopickerConfiguration) = true

                    override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
                }

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                )

            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    setOf(mockRegistration)
                )
            val databaseManager = DatabaseManagerTestImpl()

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
            val testDataService = TestDataServiceImpl()

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = testDataService,
                )

            whenever(mockSimpleUiFeature.ownedBanners) {
                setOf(BannerDefinitions.PRIVACY_EXPLAINER)
            }
            whenever(
                mockSimpleUiFeature.getBannerPriority(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER),
                    isNull(),
                    nonNullableEq(configurationManager.configuration.value),
                    nonNullableEq(testDataService),
                )
            ) {
                -1
            }

            bannerManager.refreshBanners()

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.flow.value)
                .isNull()
        }
    }
}
