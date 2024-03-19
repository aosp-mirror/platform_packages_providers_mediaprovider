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

package com.android.photopicker.features.photogrid

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Media
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGridViewModelTest {

    val mediaItem =
        Media.Image(
            mediaId = "id",
            pickerId = 1000L,
            authority = "a",
            uri =
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
    fun testPhotoGridItemClickedUpdatesSelection() {

        runTest {
            val selection = Selection<Media>(scope = this.backgroundScope)

            val viewModel =
                PhotoGridViewModel(
                    this.backgroundScope,
                    selection,
                    TestDataServiceImpl(),
                )

            assertWithMessage("Unexpected selection start size")
                .that(selection.snapshot().size)
                .isEqualTo(0)

            // Toggle the item into the selection
            viewModel.handleGridItemSelection(mediaItem)

            // Wait for selection update.
            advanceTimeBy(100)

            assertWithMessage("Selection did not contain expected item")
                .that(selection.snapshot())
                .contains(mediaItem)

            // Toggle the item out of the selection
            viewModel.handleGridItemSelection(mediaItem)

            advanceTimeBy(100)

            assertWithMessage("Selection contains unexpected item")
                .that(selection.snapshot())
                .doesNotContain(mediaItem)
        }
    }
}
