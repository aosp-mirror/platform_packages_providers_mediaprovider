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
package com.android.photopicker.testcloudmediaproviderapp

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import java.io.FileNotFoundException

/**
 * Implements a placeholder {@link CloudMediaProvider}.
 */
class TestCloudProvider : CloudMediaProvider() {

    companion object {
        const val AUTHORITY = "android.com.photopicker.testcloudmediaproviderapp" +
                ".test_cloud_provider"
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        throw UnsupportedOperationException("onQueryMedia not supported")
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        throw UnsupportedOperationException("onQueryDeletedMedia not supported")
    }

    @Throws(FileNotFoundException::class)
    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        throw UnsupportedOperationException("onOpenPreview not supported")
    }

    @Throws(FileNotFoundException::class)
    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        throw UnsupportedOperationException("onOpenMedia not supported")
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        throw UnsupportedOperationException("onGetMediaCollectionInfo not supported")
    }
}