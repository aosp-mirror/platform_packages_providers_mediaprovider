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

package com.android.photopicker.core.components

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.SurfaceControlViewHost
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.embedded.EmbeddedState
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.utils.mockito.whenever
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Unit tests for the [MediaGrid] composables.
 *
 * Since [MediaGrid]'s default implementation uses Glide to load images, the [ApplicationModule] is
 * uninstalled and this test mocks out Glide's dependencies to always return a test image.
 *
 * The data in this test suite is provided by [FakeInMemoryPagingSource] to isolate device state and
 * avoid creating test images on the device itself. Metadata is generated in the paging source, and
 * all images are backed by a test resource png that is provided by the content resolver mock.
 */
@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MediaGridTest {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createComposeRule()
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    /**
     * MediaGrid uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @Mock lateinit var mockContentProvider: ContentProvider

    @Mock lateinit var mockSurfaceControlViewHost: SurfaceControlViewHost

    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in collapsed mode
     */
    private lateinit var testEmbeddedStateWithHostInCollapsedState: EmbeddedState

    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in Expanded state
     */
    private lateinit var testEmbeddedStateWithHostInExpandedState: EmbeddedState

    lateinit var pager: Pager<MediaPageKey, Media>
    lateinit var flow: Flow<PagingData<MediaGridItem>>

    private val MEDIA_GRID_TEST_TAG = "media_grid"
    private val BANNER_CONTENT_TEST_TAG = "banner_content"
    private val CUSTOM_ITEM_TEST_TAG = "custom_item"
    private val CUSTOM_ITEM_SEPARATOR_TAG = "custom_separator"
    private val CUSTOM_ITEM_FACTORY_TEXT = "custom item factory"
    private val CUSTOM_ITEM_SEPARATOR_TEXT = "custom item separator"

    private val FIRST_SEPARATOR_LABEL = "First"
    private val SECOND_SEPARATOR_LABEL = "Second"

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    /* A small MediaGridItem list that includes two Separators with three MediaItems in between */
    private val dataWithSeparators =
        buildList<MediaGridItem>() {
            add(MediaGridItem.SeparatorItem(FIRST_SEPARATOR_LABEL))
            for (i in 1..3) {
                add(
                    MediaGridItem.MediaItem(
                        media =
                            Media.Image(
                                mediaId = "$i",
                                pickerId = i.toLong(),
                                authority = "a",
                                mediaSource = MediaSource.LOCAL,
                                mediaUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("media")
                                            path("picker")
                                            path("a")
                                            path("$i")
                                        }
                                        .build(),
                                glideLoadableUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("a")
                                            path("$i")
                                        }
                                        .build(),
                                dateTakenMillisLong =
                                    LocalDateTime.now()
                                        .minus(i.toLong(), ChronoUnit.DAYS)
                                        .toEpochSecond(ZoneOffset.UTC) * 1000,
                                sizeInBytes = 1000L,
                                mimeType = "image/png",
                                standardMimeTypeExtension = 1,
                            )
                    )
                )
            }
            add(MediaGridItem.SeparatorItem(SECOND_SEPARATOR_LABEL))
        }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        // Stub out the content resolver for Glide
        provider = MockContentProviderWrapper(mockContentProvider)
        contentResolver = ContentResolver.wrap(provider)

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .openRawResourceFd(R.drawable.android)
        }

        initEmbeddedStates()

        // Normally this would be created in the view model that owns the paged data.
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryMediaPagingSource() }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pager.flow.toMediaGridItemFromMedia().insertMonthSeparators()
    }

    /** Initialize [EmbeddedState] instances */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun initEmbeddedStates() {
        if (SdkLevel.isAtLeastU()) {
            @Suppress("DEPRECATION")
            whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true }
            testEmbeddedStateWithHostInCollapsedState =
                EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)
            testEmbeddedStateWithHostInExpandedState =
                EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)
        }
    }

    /**
     * Test wrapper around the mediaGrid which sets up the required collections, and applies a test
     * tag before rendering the mediaGrid.
     */
    @Composable
    private fun grid(
        selection: SelectionImpl<Media>,
        onItemClick: (MediaGridItem) -> Unit,
        onItemLongPress: (MediaGridItem) -> Unit = {},
        bannerContent: (@Composable () -> Unit)? = null,
    ) {
        val items = flow.collectAsLazyPagingItems()
        val selected by selection.flow.collectAsStateWithLifecycle()

        mediaGrid(
            items = items,
            selection = selected,
            onItemClick = onItemClick,
            onItemLongPress = onItemLongPress,
            bannerContent = bannerContent,
            modifier = Modifier.testTag(MEDIA_GRID_TEST_TAG),
        )
    }

    /**
     * A custom content item factory that renders the same text string for each item in the grid.
     */
    @Composable
    private fun customContentItemFactory(item: MediaGridItem, onClick: ((MediaGridItem) -> Unit)?) {
        Box(
            modifier =
                // .clickable also merges the semantics of its descendants
                Modifier.testTag(CUSTOM_ITEM_TEST_TAG).clickable {
                    if (item is MediaGridItem.MediaItem) {
                        onClick?.invoke(item)
                    }
                }
        ) {
            Text(CUSTOM_ITEM_FACTORY_TEXT)
        }
    }

    /** A custom content separator factory that renders the same text string for each separator. */
    @Composable
    private fun customContentSeparatorFactory() {
        Box(
            modifier =
                // Merge the semantics into the parent node to make it easy to asset and select
                // these nodes in the tree.
                Modifier.semantics(mergeDescendants = true) {}.testTag(CUSTOM_ITEM_SEPARATOR_TAG)
        ) {
            Text(CUSTOM_ITEM_SEPARATOR_TEXT)
        }
    }

    /** Ensures the MediaGrid loads media with the correct semantic information */
    @Test
    fun testMediaGridDisplaysMedia() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /** Ensures the MediaGrid shows any banner content that is provided. */
    @Test
    fun testMediaGridDisplaysBannerContent() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(
                        selection = selection,
                        onItemClick = {},
                        onItemLongPress = {},
                        bannerContent = {
                            Text(
                                text = "bannerContent",
                                modifier = Modifier.testTag(BANNER_CONTENT_TEST_TAG),
                            )
                        },
                    )
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(BANNER_CONTENT_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /** Ensures the AlbumGrid loads media with the correct semantic information */
    @Test
    fun testAlbumGridDisplaysMedia() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        // Modify the pager and flow to get data from the FakeInMemoryAlbumPagingSource.

        // Normally this would be created in the view model that owns the paged data.
        val pagerForAlbums: Pager<MediaPageKey, Group.Album> =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryAlbumPagingSource() }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pagerForAlbums.flow.toMediaGridItemFromAlbum()

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /**
     * Ensures the MediaGrid continues to load media as the grid is scrolled. This further ensures
     * the grid, paging and glide integrations are correctly setup.
     */
    @Test
    fun testMediaGridScroll() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        mediaGrid.assertIsDisplayed()
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemSingleSelect() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("")
                                    selectionLimit(1)
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("")
                            selectionLimit(1)
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(1)
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemMultiSelect() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val selectedString = resources.getString(R.string.photopicker_item_selected)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("")
                                    selectionLimit(50)
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("")
                            selectionLimit(50)
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(50)
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Ensure the selected semantics got applied to the selected node.
            composeTestRule.waitUntilAtLeastOneExists(hasContentDescription(selectedString))
        }
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemOrderedSelection() {
        val photopickerConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                selectionLimit(2)
                pickImagesInOrder(true)
            }

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration = photopickerConfiguration,
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides photopickerConfiguration
                ) {
                    PhotopickerTheme(isDarkTheme = false, config = photopickerConfiguration) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Ensure the ordered selected semantics got applied to the selected node.
            composeTestRule.waitUntilAtLeastOneExists(hasText("1"))
        }
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridLongPressItem() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("TEST_ACTION")
                                intent(Intent("TEST_ACTION"))
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ {},
                            /* onItemLongPress=*/ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { longClick() }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected long press handler to have executed.")
                .that(selection.snapshot())
                .isNotEmpty()
        }
    }

    /** Ensures that Separators are correctly inserted into the MediaGrid. */
    @Test
    fun testMediaGridSeparator() {
        // Provide a custom PagingData that puts Separators in specific positions to reduce
        // test flakiness of having to scroll to find a separator.
        val customData = PagingData.from(dataWithSeparators)
        val dataFlow = flowOf(customData)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    val items = dataFlow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("TEST_ACTION")
                                intent(Intent("TEST_ACTION"))
                            },
                    ) {
                        mediaGrid(items = items, selection = selected, onItemClick = {})
                    }
                }
            }

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .assertCountEquals(3)
            composeTestRule.onNode(hasText(FIRST_SEPARATOR_LABEL)).assertIsDisplayed()
            composeTestRule.onNode(hasText(SECOND_SEPARATOR_LABEL)).assertIsDisplayed()
        }
    }

    /** Ensures that the grid uses a custom content item factory when it is provided */
    @Test
    fun testMediaGridCustomContentItemFactory() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    val items = flow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    mediaGrid(
                        items = items,
                        selection = selected,
                        onItemClick = {},
                        onItemLongPress = {},
                        contentItemFactory = { item, _, onClick, _, _ ->
                            customContentItemFactory(item, onClick)
                        },
                    )
                }
            }

            composeTestRule
                .onAllNodes(hasTestTag(CUSTOM_ITEM_TEST_TAG))
                .assertAll(hasText(CUSTOM_ITEM_FACTORY_TEXT))
        }
    }

    /** Ensures that the grid uses a custom content item factory when it is provided */
    @Test
    fun testMediaGridCustomContentSeparatorFactory() {
        // Provide a custom PagingData that puts Separators in specific positions to reduce
        // test flakiness of having to scroll to find a separator.
        val customData = PagingData.from(dataWithSeparators)
        val dataFlow = flowOf(customData)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    val items = dataFlow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    mediaGrid(
                        items = items,
                        selection = selected,
                        onItemClick = {},
                        contentSeparatorFactory = { _ -> customContentSeparatorFactory() },
                    )
                }
            }

            composeTestRule
                .onAllNodes(hasTestTag(CUSTOM_ITEM_SEPARATOR_TAG))
                .assertAll(hasText(CUSTOM_ITEM_SEPARATOR_TEXT))
        }
    }

    /** Ensures that touches are transferring for embedded when swipe up in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_CollapsedMode_SwipeUp() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that touches are transferring for embedded when swipe down in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_CollapsedMode_SwipeDown() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that clicks are not transferring for embedded in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_CollapsedMode_Click() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { click() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }

    /** Ensures that touches are not transferring for embedded when swipe up in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_ExpandedMode_SwipeUP() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }

    /** Ensures that touches are transferring for embedded when swipe down in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_ExpandedMode_SwipeDown() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that clicks are not transferring for embedded in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_ExpandedMode_Click() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { click() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }
}
