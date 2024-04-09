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

package com.android.photopicker.core.glide

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.CloudMediaProviderContract
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.data.DataFetcher.DataCallback
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * The worker unit of the [GlideLoadable] pipeline.
 *
 * Each load receives its own worker which is responsible for fetching data related to that specific
 * load operation.
 *
 * @property resolver The [ContentResolver] that will be used for fetching.
 * @property model The model this worker is responsible for loading.
 * @property width The requested width of the image.
 * @property height The requested height of the image.
 * @property options Any additional loading options.
 */
class PhotopickerMediaFetcher(
    private val resolver: ContentResolver,
    private val model: GlideLoadable,
    private val width: Int,
    private val height: Int,
    private val options: Options,
) : DataFetcher<InputStream> {

    companion object {
        val TAG: String = "PhotopickerMediaFetcher"
    }

    private val cancellationSignal = CancellationSignal()

    // Retain a reference to loaded resources this worker opens. These need to be held open for
    // Glide
    // and will be closed during [cleanup].
    private var afd: AssetFileDescriptor? = null
    private var inputStream: InputStream? = null

    /**
     * The load request that is issued by Glide so this worker can begin to load it's data. The
     * callback should be used to notify Glide of the outcome.
     */
    override fun loadData(priority: Priority, callback: DataCallback<in InputStream>) {
        val options =
            Bundle().apply {
                putParcelable(ContentResolver.EXTRA_SIZE, Point(width, height))

                // Force the CloudProvider to return an image, even for videos.
                putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true)

                if (options.get(RESOLUTION_REQUESTED)?.equals(Resolution.THUMBNAIL) ?: false) {
                    putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true)
                }
            }

        try {
            afd =
                resolver.openTypedAssetFile(
                    /* uri= */ model.getLoadableUri(),
                    /* mimeType=*/ model.getMimeTypeForGlide(),
                    /* opts= */ options,
                    /* signal= */ cancellationSignal,
                )
            if (afd == null) {
                callback.onLoadFailed(
                    FileNotFoundException("Failed to load data for ${model.getLoadableUri()}")
                )
                return
            }
            inputStream = afd?.createInputStream()
            callback.onDataReady(inputStream)
        } catch (ex: IOException) {
            callback.onLoadFailed(ex)
        }
    }

    /**
     * Glide will call this when the resources this worker holds are no longer needed, so they can
     * be safely closed.
     */
    override fun cleanup() {
        try {
            inputStream?.close()
            afd?.close()
        } catch (ex: IOException) {
            Log.d(TAG, "Unexpected error during media fetcher cleanup", ex)
        }
    }

    /**
     * If this load hasn't been completed, and is no longer needed, Glide will request a
     * cancellation here. (Maybe the image is no longer in view, etc..)
     */
    override fun cancel() {
        cancellationSignal.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return model.getDataSource()
    }
}
