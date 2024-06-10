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

package com.android.photopicker.features.albumgrid

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken.ALBUM_GRID
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumGridViewModelTest {

    val mediaItem =
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
                        authority("a")
                        path("id")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
        )

    @Test
    fun testAlbumGridItemClickedUpdatesSelection() {

        runTest {
            val selection =
                Selection<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope)
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                    coreEventsConsumed = setOf<RegisteredEventClass>(),
                    coreEventsProduced = setOf<RegisteredEventClass>(),
                )

            val events =
                Events(
                    scope = this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager = featureManager,
                )

            val viewModel =
                AlbumGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                    events,
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            viewModel.handleAlbumMediaGridItemSelection(mediaItem, "")

            // Wait for selection update.
            advanceTimeBy(100)

            assertWithMessage("Selection did not contain expected item")
                .that(selection.snapshot())
                .contains(mediaItem)

            // Toggle the item out of the selection
            viewModel.handleAlbumMediaGridItemSelection(mediaItem, "")

            advanceTimeBy(100)

            assertWithMessage("Selection contains unexpected item")
                .that(selection.snapshot())
                .doesNotContain(mediaItem)
        }
    }

    @Test
    fun testAlbumGridShowsToastWhenSelectionFull() {

        runTest {
            val selection =
                Selection<Media>(
                    scope = this.backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = this.backgroundScope,
                            defaultConfiguration =
                                PhotopickerConfiguration(
                                    action = "TEST_ACTION",
                                    intent = null,
                                    selectionLimit = 0
                                )
                        )
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                    coreEventsConsumed = setOf<RegisteredEventClass>(),
                    coreEventsProduced = setOf<RegisteredEventClass>(),
                )

            val events =
                Events(
                    scope = this.backgroundScope,
                    provideTestConfigurationFlow(scope = this.backgroundScope),
                    featureManager = featureManager,
                )

            val eventsDispatched = mutableListOf<Event>()
            backgroundScope.launch { events.flow.toList(eventsDispatched) }

            val viewModel =
                AlbumGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                    events,
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            val errorMessage = "test"
            viewModel.handleAlbumMediaGridItemSelection(mediaItem, errorMessage)

            // Wait for selection update.
            advanceTimeBy(100)

            assertWithMessage("Snackbar event was not dispatched when selection failed")
                .that(eventsDispatched)
                .contains(Event.ShowSnackbarMessage(ALBUM_GRID.token, errorMessage))
        }
    }
}
