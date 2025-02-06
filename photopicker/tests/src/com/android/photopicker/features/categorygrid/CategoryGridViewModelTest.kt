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

package com.android.photopicker.features.categorygrid

import android.net.Uri
import android.os.Build
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import src.com.android.photopicker.features.categorygrid.data.TestCategoryDataServiceImpl

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
class CategoryGridViewModelTest {

    @get:Rule var setFlagsRule = SetFlagsRule()

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

    val album =
        Group.Album(
            id = ALBUM_ID_VIDEOS,
            pickerId = 1234L,
            authority = "a",
            displayName = "Videos",
            coverUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("1234")
                    }
                    .build(),
            dateTakenMillisLong = 12345678L,
            coverMediaSource = MediaSource.LOCAL,
        )

    val updatedMediaItem =
        mediaItem.copy(mediaItemAlbum = album, selectionSource = Telemetry.MediaLocation.ALBUM)

    @Test
    fun testCategoryGridItemClickedUpdatesSelection() {

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = this.backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            val featureManager =
                FeatureManager(
                    configuration = provideTestConfigurationFlow(scope = this.backgroundScope),
                    scope = this.backgroundScope,
                    prefetchDataService = TestPrefetchDataService(),
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
                CategoryGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestCategoryDataServiceImpl(),
                    TestDataServiceImpl(),
                    events,
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            viewModel.handleAlbumMediaGridItemSelection(mediaItem, "", album)

            // Wait for selection update.
            advanceTimeBy(100)

            // The selected media item gets updated with the Selectable interface values
            assertWithMessage("Selection did not contain expected item")
                .that(selection.snapshot())
                .contains(updatedMediaItem)

            // Toggle the item out of the selection
            viewModel.handleAlbumMediaGridItemSelection(mediaItem, "", album)

            advanceTimeBy(100)

            assertWithMessage("Selection contains unexpected item")
                .that(selection.snapshot())
                .doesNotContain(updatedMediaItem)
        }
    }
}
