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

package src.com.android.photopicker.features.categorygrid.data

import android.os.CancellationSignal
import androidx.paging.PagingSource
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.paging.FakeInMemoryCategoryPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaSetPagingSource
import com.android.photopicker.features.categorygrid.data.CategoryDataService

class TestCategoryDataServiceImpl : CategoryDataService {

    // Overrides for CategoryPagingSource
    var categoryAlbumSize: Int = FakeInMemoryCategoryPagingSource.DEFAULT_SIZE
    var categoryAlbumList: List<Group>? = null

    // Overrides for AMediaPagingSource
    var mediaSetSize: Int = FakeInMemoryMediaSetPagingSource.DEFAULT_SIZE
    var mediaSetList: List<Group.MediaSet>? = null

    var mediaSetContentSize: Int = FakeInMemoryMediaPagingSource.DEFAULT_SIZE
    var mediaSetContentList: List<Media>? = null

    override fun getCategories(
        parentCategory: Group.Category?,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group> {
        return categoryAlbumList?.let { FakeInMemoryCategoryPagingSource(it) }
            ?: FakeInMemoryCategoryPagingSource(categoryAlbumSize)
    }

    override fun getMediaSets(
        category: Group.Category,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<GroupPageKey, Group.MediaSet> {
        return mediaSetList?.let { FakeInMemoryMediaSetPagingSource(it) }
            ?: FakeInMemoryMediaSetPagingSource(mediaSetSize)
    }

    override fun getMediaSetContents(
        mediaSet: Group.MediaSet,
        cancellationSignal: CancellationSignal?,
    ): PagingSource<MediaPageKey, Media> {
        return mediaSetContentList?.let { FakeInMemoryMediaPagingSource(it) }
            ?: FakeInMemoryMediaPagingSource(mediaSetContentSize)
    }
}
