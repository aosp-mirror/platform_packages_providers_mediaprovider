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

package com.android.photopicker.extensions

import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Intent] extension functions */
@SmallTest
@RunWith(AndroidJUnit4::class)
class IntentTest {
    @Test
    fun testGetMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetMimeTypeFromIntentActionPickImagesWithWildcards() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { setType("*/*") }

        val mimeTypes: List<String> = mutableListOf("*/*")
        val intent2 = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent2.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val expectedMimeTypes = arrayListOf("image/*", "video/*")
        assertThat(intent.getPhotopickerMimeTypes()).isEqualTo(expectedMimeTypes)
        assertThat(intent2.getPhotopickerMimeTypes()).isEqualTo(expectedMimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        assertThrows(IllegalIntentExtraException::class.java) { intent.getPhotopickerMimeTypes() }
    }

    @Test
    fun testGetMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isNull()
    }

    @Test
    fun testGetTypeFromIntent() {
        val mimeType: String = "image/gif"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mutableListOf(mimeType))
    }

    @Test
    fun testGetInvalidTypeFromIntent() {
        val mimeType: String = "application/binary"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        assertThrows(IllegalIntentExtraException::class.java) { intent.getPhotopickerMimeTypes() }
    }
}
