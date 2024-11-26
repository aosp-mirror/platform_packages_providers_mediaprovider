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
package com.android.photopicker.core.embedded

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.test.mock.MockContentResolver
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import android.widget.photopicker.IEmbeddedPhotoPickerClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceComponent
import com.android.photopicker.core.EmbeddedServiceComponentBuilder
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.extensions.requireSystemService
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.tests.utils.StubProvider
import com.android.photopicker.tests.utils.mockito.capture
import com.android.photopicker.tests.utils.mockito.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.EntryPoints
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
class SessionTest : EmbeddedPhotopickerFeatureBaseTest() {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule :
        PhotopickerTestModule(TestOptions.build { runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED) })

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for EmbeddedServiceModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    @Inject @Main lateinit var mainDispatcher: CoroutineDispatcher

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

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
    @Inject lateinit var embeddedServiceComponentBuilder: EmbeddedServiceComponentBuilder
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService
    @Inject lateinit var embeddedLifecycle: EmbeddedLifecycle

    @Captor lateinit var uriCaptor: ArgumentCaptor<Uri>

    @Captor lateinit var uriCaptor2: ArgumentCaptor<Uri>

    @Captor lateinit var uriCaptor3: ArgumentCaptor<Uri>

    private lateinit var mockTextContextWrapper: FakeTestContextWrapper

    @Mock lateinit var mockClient: IEmbeddedPhotoPickerClient

    val featureInfo = EmbeddedPhotoPickerFeatureInfo.Builder().build()

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING: String = "taken on"

    // Session has a surfacePackage which outlives the test if not closed, so it always needs to be
    // closed at the end of each test to prevent any existing UI activity from leaking into the next
    // test. Hold a reference to it in the test class and try to call close in the @After block.
    private var session: Session? = null

    /**
     * This is the method test cases should use to acquire a Session. This ensures the [Session]
     * under test will get cleaned up after the test case is completed. If a Session is not created
     * through this hook, it needs to be closed manually.
     */
    private fun getSessionUnderTest(component: EmbeddedServiceComponent): Session {
        val (displayId, width, height) = assumeDisplaysAndGetDisplayDataForTestDevice()
        // Try to close the previous session before it gets dereferenced if one exists.
        session?.close()
        val newSession =
            Session(
                context = getTestableContext(),
                clientPackageName = getTestableContext().getPackageName(),
                clientUid = Process.myUid(),
                component = component,
                width = width,
                height = height,
                displayId = displayId,
                hostToken = Binder(),
                featureInfo = featureInfo,
                clientCallback = mockClient,
                grantUriPermission = { _, uri -> mockTextContextWrapper.grantUriPermission(uri) },
                revokeUriPermission = { _, uri -> mockTextContextWrapper.revokeUriPermission(uri) },
            )
        session = newSession
        return newSession
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        hiltRule.inject()

        mockTextContextWrapper = spy(FakeTestContextWrapper())

        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

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

    @After()
    fun teardown() {
        // It is important to tearDown glide after every test to ensure it picks up the updated
        // mocks from Hilt and mocks aren't leaked between tests.
        session?.close()
        session = null
    }

    /**
     * Helper method to ensure that a display exists for the test and extracts accurate display
     * information so the test runs with real display data.
     */
    private fun assumeDisplaysAndGetDisplayDataForTestDevice(): Triple<Int, Int, Int> {

        val context = getTestableContext()
        val displayManager: DisplayManager = context.requireSystemService()
        val windowManager: WindowManager = context.requireSystemService()

        // Suppress this test on any devices that don't have a display.
        assumeTrue(displayManager.displays.size > 0)
        val display =
            checkNotNull(displayManager.displays.first()) {
                "The displayId provided to openSession did not result in a valid display."
            }
        val windowMetrics = windowManager.getCurrentWindowMetrics()
        val bounds = windowMetrics.getBounds()

        return Triple(display.getDisplayId(), bounds.width(), bounds.height())
    }

    @Test
    fun testCreateSessionStartsPhotopickerUi() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            // After creating the view the lifecycle should be advanced to RESUMED to ensure the
            // view is running.
            assertWithMessage("Expected state to be Resumed")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify that data in PhotoGrid is displayed
            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .assert(hasClickAction())
                .assertIsDisplayed()
                .performClick()

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }

    @Test
    fun testCloseSessionDestroysLifecycle() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val session = getSessionUnderTest(component)
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            session.close()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be Destroyed")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.DESTROYED)
        }

    @Test
    fun testSessionInitSetsLifecycleToResumed() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            assertWithMessage("Expected state to be Initialized")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.INITIALIZED)

            getSessionUnderTest(component)
            advanceTimeBy(100)

            assertWithMessage("Expected state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun testSessionNotifyVisibilityChangedUpdatesLifecycleState() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)
            val sessionLifecycle: EmbeddedLifecycle = entryPoint.lifecycle()

            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            assertWithMessage("Expected initial state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)

            async { session.notifyVisibilityChanged(isVisible = false) }.await()
            advanceTimeBy(100)

            assertWithMessage("Expected state to be CREATED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.CREATED)

            async { session.notifyVisibilityChanged(isVisible = true) }.await()
            advanceTimeBy(100)

            assertWithMessage("Expected final state to be RESUMED")
                .that(sessionLifecycle.lifecycle.currentState)
                .isEqualTo(Lifecycle.State.RESUMED)
        }

    @Test
    fun testSessionSetsCallerInConfiguration() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)

            // Create a session with the component and let it initialize.
            getSessionUnderTest(component)
            advanceTimeBy(100)

            val configuration = entryPoint.configurationManager().get().configuration.value
            assertWithMessage("Expected configuration to contain caller's package name")
                .that(configuration.callingPackage)
                .isEqualTo(getTestableContext().getPackageName())
            assertWithMessage("Expected configuration to contain caller's uid")
                .that(configuration.callingPackageUid)
                .isEqualTo(Process.myUid())
            assertWithMessage("Expected configuration to contain caller's display label")
                .that(configuration.callingPackageLabel)
                .isNotNull()
        }

    @Test
    fun testSessionSetsEmbeddedPhotopickerFeatureInfoInConfiguration() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val entryPoint = EntryPoints.get(component, Session.EmbeddedEntryPoint::class.java)

            // Create a session with the component and let it initialize.
            getSessionUnderTest(component)
            advanceTimeBy(100)

            val configuration = entryPoint.configurationManager().get().configuration.value
            assertWithMessage(
                    "Expected configuration to contain the featureInfo max selection limit"
                )
                .that(configuration.selectionLimit)
                .isEqualTo(featureInfo.maxSelectionLimit)
            assertWithMessage("Expected configuration to contain the featureInfo mime types")
                .that(configuration.mimeTypes)
                .isEqualTo(featureInfo.mimeTypes)
            assertWithMessage(
                    "Expected configuration to contain the featureInfo ordered selection flag"
                )
                .that(configuration.pickImagesInOrder)
                .isEqualTo(featureInfo.isOrderedSelection)
            assertWithMessage("Expected configuration to contain the featureInfo pre-selected URIs")
                .that(configuration.preSelectedUris)
                .isEqualTo(featureInfo.preSelectedUris)
        }

    @Test
    fun testURIDebounceOnSelectionOfMediaItems() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val session = getSessionUnderTest(component)

            val itemCount = 20
            setUpTestDataWithStubProvider(itemCount)

            advanceTimeBy(100)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }

            composeTestRule.waitForIdle()

            clearInvocations(mockTextContextWrapper, mockClient)

            // Get all image nodes
            val allImageNodes =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Make list of indices to select
            var indicesToSelect = setOf(2, 0, 4) // Select images at indices 2, 0, and 4
            var indicesToDeselect = setOf(0)
            var expectedUrisSelected: List<Uri> = constructUrisForIndices(indicesToSelect)
            var expectedUrisDeselected: List<Uri> = constructUrisForIndices(indicesToDeselect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToSelect)

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Filter image nodes based on the indices to deselect and performClick
            performClickForIndices(allImageNodes, indicesToDeselect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(2) // Indices {2, 4}

            // Verify that grantUriPermission is invoked for all newly selected media.
            verify(mockTextContextWrapper, times(2)).grantUriPermission(capture(uriCaptor))
            var capturedUris = uriCaptor.allValues
            assertThat(capturedUris.toList())
                .containsExactlyElementsIn(expectedUrisSelected - expectedUrisDeselected)

            verify(mockTextContextWrapper, never()).revokeUriPermission(capture(uriCaptor2))

            // Since we deselected an item just after selection within Uri debounce time ,
            // deselected callback should not be invoked
            verify(mockClient, never()).onUriPermissionRevoked(anyList())
            verify(mockClient, times(1))
                .onUriPermissionGranted(expectedUrisSelected - expectedUrisDeselected)

            clearInvocations(mockTextContextWrapper, mockClient)

            // Next set of selection & deselection
            var nextIndicesToSelect = setOf(6, 8)
            var nextIndicesToDeselect = setOf(2)
            var nextExpectedUrisSelected: List<Uri> = constructUrisForIndices(nextIndicesToSelect)
            var nextExpectedUrisDeselected: List<Uri> =
                constructUrisForIndices(nextIndicesToDeselect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, nextIndicesToSelect)

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, nextIndicesToDeselect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(3) // Indices {4, 6, 8}

            // Verify that grantUriPermission is invoked for all newly selected media.
            verify(mockTextContextWrapper, times(2)).grantUriPermission(capture(uriCaptor3))
            var nextCapturedUris = uriCaptor3.allValues
            assertThat(nextCapturedUris.toList())
                .containsExactlyElementsIn(nextExpectedUrisSelected)

            // Verify that revokeUriPermission is invoked for newly deselected media.
            verify(mockTextContextWrapper, times(1)).revokeUriPermission(capture(uriCaptor2))
            nextCapturedUris = uriCaptor2.allValues

            assertThat(nextCapturedUris.toList())
                .containsExactlyElementsIn(nextExpectedUrisDeselected)

            verify(mockClient, times(1)).onUriPermissionGranted(nextExpectedUrisSelected)
            verify(mockClient, times(1)).onUriPermissionRevoked(nextExpectedUrisDeselected)
        }

    @Test
    fun testSelectionUpdateGrantsAndRevokesPermissionSuccess() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val session = getSessionUnderTest(component)

            val itemCount = 20
            setUpTestDataWithStubProvider(itemCount)

            advanceTimeBy(100)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }

            composeTestRule.waitForIdle()

            // Get all image nodes
            val allImageNodes =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Make list of indices to select
            var indicesToSelect = setOf(2, 0, 4) // Select images at indices 2, 0, and 4
            var expectedUris: List<Uri> = constructUrisForIndices(indicesToSelect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToSelect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(3)

            // Verify that grantUriPermission is invoked for all newly selected media.
            verify(mockTextContextWrapper, times(3)).grantUriPermission(capture(uriCaptor))
            var capturedUris = uriCaptor.allValues

            assertThat(capturedUris.toList()).containsExactlyElementsIn(expectedUris)

            // Verify that client callback is invoked for all uris that were successfully
            // granted permission
            verify(mockClient, times(1)).onUriPermissionGranted(expectedUris)

            clearInvocations(mockTextContextWrapper, mockClient)

            // Make list of indices to deselect.
            val indicesToDeselect = setOf(2, 0) // Deselect images at indices 2, 0
            // Get difference of two list which is the final selected uris and get expectedUri list.
            expectedUris = constructUrisForIndices(indicesToDeselect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToDeselect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Verify that revokeUriPermission is invoked for all newly deselected media.
            verify(mockTextContextWrapper, times(2)).revokeUriPermission(capture(uriCaptor2))
            capturedUris = uriCaptor2.allValues

            assertThat(capturedUris.toList()).containsExactlyElementsIn(expectedUris)

            // Verify that client callback is invoked for all uris that were successfully
            // revoked permission
            verify(mockClient, times(1)).onUriPermissionRevoked(expectedUris)

            clearInvocations(mockTextContextWrapper, mockClient)

            // Make list of indices to select again
            indicesToSelect = setOf(7, 8) // Select images at indices 7,8
            expectedUris = constructUrisForIndices(indicesToSelect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToSelect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(3)

            // Verify that grantUriPermission is invoked for all newly selected media.
            verify(mockTextContextWrapper, times(2)).grantUriPermission(capture(uriCaptor3))
            capturedUris = uriCaptor3.allValues

            assertThat(capturedUris).containsExactlyElementsIn(expectedUris)

            // Verify that client callback is invoked for all uris that were successfully
            // granted permission
            verify(mockClient, times(1)).onUriPermissionGranted(expectedUris)
        }

    @Test
    fun testSelectionGrantOrRevokePermissionFailed() =
        testScope.runTest {
            setUpTestDataWithStubProvider(20)

            // Mark image at node 0 as media item we aren't able to grant permission.
            val grantFailureUri = constructUrisForIndices(setOf(0))[0]
            whenever(mockTextContextWrapper.grantUriPermission(grantFailureUri)) {
                EmbeddedService.GrantResult.FAILURE
            }

            val component = embeddedServiceComponentBuilder.build()
            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }

            composeTestRule.waitForIdle()

            // Get all image nodes
            val allImageNodes =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Make list of indices to select
            var indicesToSelect = setOf(2, 0, 4) // Select images at indices 2, 0, and 4
            var expectedUris: List<Uri> = constructUrisForIndices(indicesToSelect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToSelect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(3)

            // Verify that client callback is invoked for all uris that were successfully
            // granted permission and never for the uri that we failed granting permission
            verify(mockTextContextWrapper, times(3)).grantUriPermission(capture(uriCaptor))
            var capturedUris = uriCaptor.allValues

            assertThat(capturedUris.toList()).containsExactlyElementsIn(expectedUris)

            verify(mockClient, never()).onUriPermissionGranted(expectedUris)
            verify(mockClient, times(1)).onUriPermissionGranted(expectedUris - grantFailureUri)

            clearInvocations(mockTextContextWrapper, mockClient)

            // Mark image at node 2 as media item we aren't able to revoke permission.
            val revokeFailureUri = constructUrisForIndices(setOf(2))[0]
            whenever(mockTextContextWrapper.revokeUriPermission(revokeFailureUri)) {
                EmbeddedService.GrantResult.FAILURE
            }

            // Make list of indices to select
            var indicesToDeselect = setOf(2) // Deselect image at indices 2
            expectedUris = constructUrisForIndices(indicesToDeselect)

            // Filter image nodes based on the indices to select and performClick
            performClickForIndices(allImageNodes, indicesToDeselect)

            // Wait for PhotoGridViewModel to modify Selection and to invoke client
            // callbacks after media selection/deselection
            advanceTimeBy(100 + Session.URI_DEBOUNCE_TIME)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(2) // images at indices 0, 4 are still selected

            // Verify client callback is never invoked if we failed to revoke permission to uri
            verify(mockTextContextWrapper, times(1)).revokeUriPermission(capture(uriCaptor2))
            capturedUris = uriCaptor2.allValues

            assertThat(capturedUris.toList()).containsExactlyElementsIn(expectedUris)

            verify(mockClient, never()).onUriPermissionRevoked(listOf(revokeFailureUri))
        }

    @Test
    fun testSessionNotifyResizedChangesViewSize() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()

            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            val initialWidth = session.getView().width
            val initialHeight = session.getView().height

            val newWidth = 2 * initialWidth
            val newHeight = 2 * initialHeight

            async { session.notifyResized(newWidth, newHeight) }.await()
            advanceTimeBy(100)

            assertWithMessage("Expected view's width to be resized")
                .that(session.getView().width)
                .isEqualTo(newWidth)

            assertWithMessage("Expected view's height to be resized")
                .that(session.getView().height)
                .isEqualTo(newHeight)
        }

    @Test
    fun testSessionNotifyConfigurationChangedOnThemeChange() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }

            async { session.notifyPhotopickerExpanded(true) }.await()
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            // This is the label for the "Photos" tab in the picker.
            val photosTabLabel = resources.getString(R.string.photopicker_photos_nav_button_label)

            val node = composeTestRule.onNodeWithText(photosTabLabel)
            node.assertIsDisplayed()
            val initialColor = node.extractTextColor()

            // Create new configuration which will update theme to dark
            val newConfig = Configuration()
            newConfig.uiMode = Configuration.UI_MODE_NIGHT_YES
            async { session.notifyConfigurationChanged(newConfig) }.await()
            advanceTimeBy(100)

            val finalColor = node.extractTextColor()

            assertWithMessage("Expected text colors to change on theme change")
                .that(initialColor)
                .isNotEqualTo(finalColor)
        }

    @Test
    fun testNotifyPhotopickerExpandedTrueHiddenFeaturesVisible() =
        testScope.runTest {
            val component = embeddedServiceComponentBuilder.build()
            val session = getSessionUnderTest(component)
            advanceTimeBy(100)

            // Now the view is in the test's compose tree, so do a simple check to make sure
            // the view actually initialized and the test can locate the photo grid / modify the
            // selection.
            composeTestRule.setContent {
                // Wrap the surfacePackage inside of an [AndroidView] to make the view accessible to
                // the test.
                AndroidView(
                    factory = {
                        SurfaceView(getTestableContext()).apply {
                            setChildSurfacePackage(session.surfacePackage)
                        }
                    }
                )
            }

            val resources = getTestableContext().getResources()
            // This is the label for the "Photos" tab in the picker.
            val photosTabLabel = resources.getString(R.string.photopicker_photos_nav_button_label)

            composeTestRule.onNodeWithText(photosTabLabel).assertDoesNotExist()

            async { session.notifyPhotopickerExpanded(true) }.await()
            advanceTimeBy(100)

            composeTestRule.onNodeWithText(photosTabLabel).assertExists().assertIsDisplayed()
        }

    /** Gets the correct nodes of media item for given indices and performs click. */
    private fun performClickForIndices(
        allImageNodes: SemanticsNodeInteractionCollection,
        indicesToSelect: Set<Int>,
    ) {
        var imageNodesToSelect =
            indicesToSelect.mapNotNull { index ->
                try {
                    allImageNodes.get(index)
                } catch (e: AssertionError) {
                    // Fail the test if no node found at given position
                    fail("Unexpected AssertionError: Index out of bounds") // Fail the test
                    null
                }
            }

        for (node in imageNodesToSelect) {
            node.assert(hasClickAction()).assertIsDisplayed().performClick()
        }
    }

    /** Using [StubProvider] as a backing provider, set custom number of media */
    private fun setUpTestDataWithStubProvider(mediaCount: Int): List<Media> {
        val stubProvider =
            Provider(
                authority = StubProvider.AUTHORITY,
                mediaSource = MediaSource.LOCAL,
                uid = 1,
                displayName = "Stub Provider",
            )

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        testDataService.setAvailableProviders(listOf(stubProvider))
        testDataService.collectionInfo.put(
            stubProvider,
            CollectionInfo(
                authority = stubProvider.authority,
                collectionId = null,
                accountName = null,
            ),
        )

        val testImages = StubProvider.getTestMediaFromStubProvider(mediaCount)
        testDataService.mediaList = testImages
        return testImages
    }

    /**
     * Fake [ContextWrapper] class to mock [ContextWrapper#grantUriPermission] and
     * [ContextWrapper#revokeUriPermission] in tests.
     *
     * These methods by default return Success. Tests can manipulate the behaviour for specific uri
     * in their tests as we are spying this class instead of mocking.
     */
    open class FakeTestContextWrapper {
        open fun grantUriPermission(uri: Uri): EmbeddedService.GrantResult {
            return EmbeddedService.GrantResult.SUCCESS
        }

        open fun revokeUriPermission(uri: Uri): EmbeddedService.GrantResult {
            return EmbeddedService.GrantResult.SUCCESS
        }
    }

    /**
     * Constructs URI for given indices for test.
     *
     * Follows format "content://stubprovider/$id"
     */
    private fun constructUrisForIndices(uriIndices: Set<Int>): List<Uri> {
        val newUris = uriIndices.map { index -> Uri.parse("content://stubprovider/${index + 1}") }
        return newUris
    }

    private fun SemanticsNodeInteraction.extractTextColor(): Color? {
        val textLayoutResults = mutableListOf<TextLayoutResult>()
        this.fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(textLayoutResults)
        return if (textLayoutResults.isEmpty()) {
            null // No text found, return null
        } else {
            textLayoutResults.first().layoutInput.style.color
        }
    }
}
