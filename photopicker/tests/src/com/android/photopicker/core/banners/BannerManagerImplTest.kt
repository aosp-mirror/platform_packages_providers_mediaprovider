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
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.database.DatabaseManagerTestImpl
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Deferred
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
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
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
    private val PLATFORM_PROVIDED_PROFILE_LABEL = "Platform Label"

    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0
    private val PRIMARY_PROFILE_BASE: UserProfile

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MANAGED_PROFILE_BASE: UserProfile
    private val mockContentResolver: ContentResolver = mock(ContentResolver::class.java)

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

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

    val sessionId = generatePickerSessionId()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        deviceConfigProxy.reset()
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()

        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED) }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

        val mockResolveInfo = mock(ResolveInfo::class.java)
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    emptySet<FeatureRegistration>(),
                )

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = DatabaseManagerTestImpl(),
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    anyInt(),
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the per uid dismissal
     * strategy.
     */
    @Test
    fun testMarkBannerAsDismissedSessionStrategy() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
                )
            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
            val test_session_banner =
                object : BannerDeclaration {
                    override val id = "test_session_banner"
                    override val dismissableStrategy = BannerDeclaration.DismissStrategy.SESSION
                    override val dismissable = true
                }

            bannerManager.markBannerAsDismissed(test_session_banner)

            assertWithMessage("Expected banner state to be dismissed")
                .that(bannerManager.getBannerState(test_session_banner)?.dismissed)
                .isTrue()

            // Ensure no calls to persist the state in the database.
            verify(databaseManager.bannerState, never())
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.SWITCH_PROFILE.id,
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

                    override fun isEnabled(
                        config: PhotopickerConfiguration,
                        deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
                    ) = true

                    override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
                }

            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId,
                )

            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(mockRegistration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
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
                    nonNullableEq(userMonitor),
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

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    fun testHidesBannersOnProfileSwitch() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId,
                )
            val featureManager =
                FeatureManager(
                    configurationManager.configuration,
                    this.backgroundScope,
                    TestPrefetchDataService(),
                    setOf(SimpleUiFeature.Registration),
                )
            val databaseManager = DatabaseManagerTestImpl()

            val userMonitor =
                UserMonitor(
                    mockContext,
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

            val bannerManager =
                BannerManagerImpl(
                    scope = this.backgroundScope,
                    backgroundDispatcher = StandardTestDispatcher(this.testScheduler),
                    configurationManager = configurationManager,
                    databaseManager = databaseManager,
                    featureManager = featureManager,
                    dataService = TestDataServiceImpl(),
                    userMonitor = userMonitor,
                    processOwnerHandle = USER_HANDLE_PRIMARY,
                )

            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }
            bannerManager.refreshBanners()

            userMonitor.requestSwitchActiveUserProfile(
                requested = MANAGED_PROFILE_BASE,
                mockContext,
            )
            advanceTimeBy(100)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.flow.value)
                .isNull()
        }
    }
}
