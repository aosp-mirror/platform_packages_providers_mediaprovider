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

package com.android.photopicker.data

import android.content.ContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.model.Provider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionInfoStateTest {
    private lateinit var testContentProvider: TestMediaProvider
    private lateinit var mediaProviderClient: MediaProviderClient
    private lateinit var testContentResolverFlow: MutableStateFlow<ContentResolver>
    private lateinit var availableProvidersFlow: MutableStateFlow<List<Provider>>

    @Before
    fun setup() {
        testContentProvider = TestMediaProvider()
        mediaProviderClient = MediaProviderClient()
        testContentResolverFlow = MutableStateFlow(ContentResolver.wrap(testContentProvider))
        availableProvidersFlow = MutableStateFlow(listOf(testContentProvider.providers[0]))
    }

    @Test
    fun testUpdateCollectionInfo() = runTest {
        val collectionInfoState =
            CollectionInfoState(
                mediaProviderClient,
                testContentResolverFlow,
                availableProvidersFlow
            )

        collectionInfoState.updateCollectionInfo(testContentProvider.collectionInfos)

        val expectedProvider = testContentProvider.providers[0]
        val expectedCollectionInfo = testContentProvider.collectionInfos[0]
        val cachedCollectionInfo = collectionInfoState.getCachedCollectionInfo(expectedProvider)
        assertThat(cachedCollectionInfo?.authority).isEqualTo(expectedCollectionInfo.authority)
        assertThat(cachedCollectionInfo?.accountName).isEqualTo(expectedCollectionInfo.accountName)
        assertThat(cachedCollectionInfo?.collectionId)
            .isEqualTo(expectedCollectionInfo.collectionId)
    }

    @Test
    fun testClearCollectionInfo() = runTest {
        val collectionInfoState =
            CollectionInfoState(
                mediaProviderClient,
                testContentResolverFlow,
                availableProvidersFlow
            )

        collectionInfoState.updateCollectionInfo(testContentProvider.collectionInfos)

        val expectedProvider = testContentProvider.providers[0]
        val expectedCollectionInfo = testContentProvider.collectionInfos[0]
        val cachedCollectionInfo = collectionInfoState.getCachedCollectionInfo(expectedProvider)
        assertThat(cachedCollectionInfo?.authority).isEqualTo(expectedCollectionInfo.authority)
        assertThat(cachedCollectionInfo?.accountName).isEqualTo(expectedCollectionInfo.accountName)
        assertThat(cachedCollectionInfo?.collectionId)
            .isEqualTo(expectedCollectionInfo.collectionId)

        collectionInfoState.clear()
        val clearedCollectionInfo = collectionInfoState.getCachedCollectionInfo(expectedProvider)
        assertThat(clearedCollectionInfo).isNull()
    }

    @Test
    fun testGetCollectionInfo() = runTest {
        val collectionInfoState =
            CollectionInfoState(
                mediaProviderClient,
                testContentResolverFlow,
                availableProvidersFlow
            )

        val expectedProvider = testContentProvider.providers[0]
        val expectedCollectionInfo = testContentProvider.collectionInfos[0]

        collectionInfoState.clear()
        val clearedCollectionInfo = collectionInfoState.getCachedCollectionInfo(expectedProvider)
        assertThat(clearedCollectionInfo).isNull()

        val actualCollectionInfo = collectionInfoState.getCollectionInfo(expectedProvider)
        assertThat(actualCollectionInfo.authority).isEqualTo(expectedCollectionInfo.authority)
        assertThat(actualCollectionInfo.accountName).isEqualTo(expectedCollectionInfo.accountName)
        assertThat(actualCollectionInfo.collectionId).isEqualTo(expectedCollectionInfo.collectionId)
    }
}
