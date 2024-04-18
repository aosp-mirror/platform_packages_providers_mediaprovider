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
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.utils.mockito.whenever
import com.bumptech.glide.Glide
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
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
@UninstallModules(ApplicationModule::class)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MediaGridTest {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createComposeRule()

    /**
     * MediaGrid uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper

    @Mock lateinit var mockContentProvider: ContentProvider

    lateinit var pager: Pager<MediaPageKey, Media>
    lateinit var flow: Flow<PagingData<MediaGridItem>>

    private val MEDIA_GRID_TEST_TAG = "media_grid"
    private val CUSTOM_ITEM_TEST_TAG = "custom_item"
    private val CUSTOM_ITEM_SEPARATOR_TAG = "custom_separator"
    private val CUSTOM_ITEM_FACTORY_TEXT = "custom item factory"
    private val CUSTOM_ITEM_SEPARATOR_TEXT = "custom item separator"

    private val FIRST_SEPARATOR_LABEL = "First"
    private val SECOND_SEPARATOR_LABEL = "Second"

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
                                mediaUri = Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("media")
                                        path("picker")
                                        path("a")
                                        path("$i")
                                    }
                                    .build(),
                                glideLoadableUri = Uri.EMPTY.buildUpon()
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

        // Normally this would be created in the view model that owns the paged data.
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryMediaPagingSource() }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pager.flow.toMediaGridItemFromMedia().insertMonthSeparators()
    }

    @After()
    fun teardown() {
        // It is important to tearDown glide after every test to ensure it picks up the updated
        // mocks from Hilt and mocks aren't leaked between tests.
        Glide.tearDown()
    }

    /**
     * Test wrapper around the mediaGrid which sets up the required collections, and applies a test
     * tag before rendering the mediaGrid.
     */
    @Composable
    private fun grid(
        selection: Selection<Media>,
        onItemClick: (MediaGridItem) -> Unit,
        onItemLongPress: (MediaGridItem) -> Unit = {},
    ) {
        val items = flow.collectAsLazyPagingItems()
        val selected by selection.flow.collectAsStateWithLifecycle()

        mediaGrid(
            items = items,
            selection = selected,
            onItemClick = onItemClick,
            onItemLongPress = onItemLongPress,
            modifier = Modifier.testTag(MEDIA_GRID_TEST_TAG)
        )
    }

    /**
     * A custom content item factory that renders the same text string for each item in the grid.
     */
    @Composable
    private fun customContentItemFactory(
        item: MediaGridItem,
        onClick: ((MediaGridItem) -> Unit)?,
    ) {
        Box(
            modifier =
                // .clickable also merges the semantics of its descendants
                Modifier.testTag(CUSTOM_ITEM_TEST_TAG).clickable {
                    if (item is MediaGridItem.MediaItem) {onClick?.invoke(item)} }
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
                Modifier.semantics(mergeDescendants = true) {}.testTag(CUSTOM_ITEM_SEPARATOR_TAG),
        ) {
            Text(CUSTOM_ITEM_SEPARATOR_TEXT)
        }
    }

    /** Ensures the MediaGrid loads media with the correct semantic information */
    @Test
    fun testMediaGridDisplaysMedia() = runTest {
        val selection = Selection<Media>(scope = backgroundScope)

        composeTestRule.setContent {
            grid(
                /* selection= */ selection,
                /* onItemClick= */ {},
            )
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /** Ensures the AlbumGrid loads media with the correct semantic information */
    @Test
    fun testAlbumGridDisplaysMedia() = runTest {
        val selection = Selection<Media>(scope = backgroundScope)

        // Modify the pager and flow to get data from the FakeInMemoryAlbumPagingSource.

        // Normally this would be created in the view model that owns the paged data.
        val pagerForAlbums: Pager<MediaPageKey, Group.Album> =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryAlbumPagingSource() }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pagerForAlbums.flow.toMediaGridItemFromAlbum()

        composeTestRule.setContent {
            grid(
                /* selection= */ selection,
                /* onItemClick= */ {},
            )
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
        val selection = Selection<Media>(scope = backgroundScope)

        composeTestRule.setContent {
            grid(
                /* selection= */ selection,
                /* onItemClick= */ {},
            )
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
    fun testMediaGridClickItem() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val mediaItemString = resources.getString(R.string.photopicker_media_item)
        val selectedString = resources.getString(R.string.photopicker_item_selected)

        runTest {
            val selection = Selection<Media>(scope = backgroundScope)

            composeTestRule.setContent {
                grid(
                    /* selection= */ selection,
                    /* onItemClick= */ { item -> launch {
                        if (item is MediaGridItem.MediaItem) selection.toggle(item.media) } },
                )
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(hasContentDescription(mediaItemString))
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
    fun testMediaGridLongPressItem() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val mediaItemString = resources.getString(R.string.photopicker_media_item)

        runTest {
            val selection = Selection<Media>(scope = backgroundScope)

            composeTestRule.setContent {
                grid(
                    /* selection= */ selection,
                    /* onItemClick= */ {},
                    /* onItemLongPress=*/ { item -> launch {
                        if (item is MediaGridItem.MediaItem) selection.toggle(item.media) } }
                )
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(hasContentDescription(mediaItemString))
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
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val mediaItemString = resources.getString(R.string.photopicker_media_item)

        // Provide a custom PagingData that puts Separators in specific positions to reduce
        // test flakiness of having to scroll to find a separator.
        val customData = PagingData.from(dataWithSeparators)
        val dataFlow = flowOf(customData)

        runTest {
            val selection = Selection<Media>(scope = backgroundScope)

            composeTestRule.setContent {
                val items = dataFlow.collectAsLazyPagingItems()
                val selected by selection.flow.collectAsStateWithLifecycle()

                mediaGrid(
                    items = items,
                    selection = selected,
                    onItemClick = {},
                )
            }

            composeTestRule.onAllNodes(hasContentDescription(mediaItemString)).assertCountEquals(3)
            composeTestRule.onNode(hasText(FIRST_SEPARATOR_LABEL)).assertIsDisplayed()
            composeTestRule.onNode(hasText(SECOND_SEPARATOR_LABEL)).assertIsDisplayed()
        }
    }

    /** Ensures that the grid uses a custom content item factory when it is provided */
    @Test
    fun testMediaGridCustomContentItemFactory() {
        runTest {
            val selection = Selection<Media>(scope = backgroundScope)

            composeTestRule.setContent {
                val items = flow.collectAsLazyPagingItems()
                val selected by selection.flow.collectAsStateWithLifecycle()
                mediaGrid(
                    items = items,
                    selection = selected,
                    onItemClick = {},
                    onItemLongPress = {},
                    contentItemFactory = { item, _, onClick, _ ->
                        customContentItemFactory(item, onClick)
                    },
                )
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
            val selection = Selection<Media>(scope = backgroundScope)

            composeTestRule.setContent {
                val items = dataFlow.collectAsLazyPagingItems()
                val selected by selection.flow.collectAsStateWithLifecycle()
                mediaGrid(
                    items = items,
                    selection = selected,
                    onItemClick = {},
                    contentSeparatorFactory = { _ -> customContentSeparatorFactory() }
                )
            }

            composeTestRule
                .onAllNodes(hasTestTag(CUSTOM_ITEM_SEPARATOR_TAG))
                .assertAll(hasText(CUSTOM_ITEM_SEPARATOR_TEXT))
        }
    }
}
