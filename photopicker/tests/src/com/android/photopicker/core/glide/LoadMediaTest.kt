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

import android.content.ContentProvider
import android.content.ContentResolver
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.CloudMediaProviderContract
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.test.utils.GlideLoadableIdlingResource
import com.android.photopicker.test.utils.MockContentProviderWrapper
import com.android.photopicker.tests.utils.mockito.capture
import com.android.photopicker.tests.utils.mockito.whenever
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Unit tests for the main [loadMedia] composable.
 *
 * This class does not test all of Glide's individual internals, but rather tests the start and end
 * points of the Glide pipeline. All of the ContentResolver calls are intercepted and verified to
 * ensure the pipeline is passing the correct data when fetching bytes from a [ContentResolver].
 *
 * These tests do not guarantee loading success; Glide's internals can still fail, and any
 * exceptions that are thrown there are not propagated to the test thread. This just ensures that
 * the entrypoint into Glide produces the expected exit values to the ContentProvider.
 *
 * This test will replace the bindings in [ApplicationModule], so the module is uninstalled.
 */
@UninstallModules(ApplicationModule::class)
@HiltAndroidTest
class LoadMediaTest {

    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createComposeRule()

    private val glideIdlingResource: GlideLoadableIdlingResource = GlideLoadableIdlingResource()
    private lateinit var provider: MockContentProviderWrapper

    /** Replace the injected ContentResolver binding in [ApplicationModule] with this test value. */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver

    @Mock lateinit var mockContentProvider: ContentProvider
    @Captor lateinit var uri: ArgumentCaptor<Uri>
    @Captor lateinit var mimeType: ArgumentCaptor<String>
    @Captor lateinit var options: ArgumentCaptor<Bundle>

    /** Simple implementation of a GlideLoadable */
    private val loadable =
        object : GlideLoadable {

            override fun getSignature(resolution: Resolution): ObjectKey {
                return ObjectKey("${getLoadableUri()}_$resolution")
            }

            override fun getLoadableUri(): Uri {
                return Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority(provider.AUTHORITY)
                        path("${CloudMediaProviderContract.URI_PATH_MEDIA}/1234")
                    }
                    .build()
            }

            override fun getDataSource(): DataSource {
                return DataSource.LOCAL
            }

            override fun getTimestamp(): Long {
                return 100L
            }
        }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        provider = MockContentProviderWrapper(mockContentProvider)
        contentResolver = ContentResolver.wrap(provider)

        /** Make compose aware of the async Glide pipeline */
        composeTestRule.registerIdlingResource(glideIdlingResource)
    }

    @After()
    fun teardown() {
        composeTestRule.unregisterIdlingResource(glideIdlingResource)
        glideIdlingResource.reset()

        // It is important to tearDown glide after every test to ensure it picks up the updated
        // mocks from Hilt and mocks aren't leaked between tests.
        Glide.tearDown()
    }

    /** Ensures that a [GlideLoadable] can be loaded via the [loadMedia] composable using Glide. */
    @Test
    fun testLoadMediaGenericThumbnailResolution() {

        // Return a resource png so the request is actually backed by something.
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .openRawResourceFd(R.drawable.android)
        }

        composeTestRule.setContent {
            loadMedia(
                // Set an explicit size for the placeholder measurement
                modifier = Modifier.size(100.dp),
                media = loadable,
                resolution = Resolution.THUMBNAIL,
                requestBuilderTransformation = ::setupRequestListener,
            )
        }

        // Wait for the [GlideLoadableIdlingResource] to indicate the glide loading
        // pipeline is idle.
        composeTestRule.waitForIdle()

        verify(mockContentProvider)
            .openTypedAssetFile(
                capture(uri),
                capture(mimeType),
                capture(options),
                any(CancellationSignal::class.java)
            )

        assertThat(uri.getValue()).isEqualTo(loadable.getLoadableUri())

        // Glide can only load images, so ensure we're requesting the correct mimeType.
        assertThat(mimeType.getValue()).isEqualTo(DEFAULT_IMAGE_MIME_TYPE)

        // Ensure the CloudProvider is being told to return a preview thumbnail, in case the
        // loadable is a video.
        assertThat(
                options.getValue().getBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL)
            )
            .isTrue()

        // This is a request for thumbnail, this needs to be set to get cached thumbnails from
        // MediaProvider.
        assertThat(options.getValue().getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB))
            .isTrue()

        // Ensure the object is a Point, but the actual size doesn't matter in this context.
        assertThat(options.getValue().getParcelable(ContentResolver.EXTRA_SIZE, Point::class.java))
            .isNotNull()
    }

    /** Ensures that a [GlideLoadable] can be loaded via the [loadMedia] composable using Glide. */
    @Test
    fun testLoadMediaGenericFullResolution() {

        // Return a resource png so the request is actually backed by something.
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .openRawResourceFd(R.drawable.android)
        }

        composeTestRule.setContent {
            loadMedia(
                // Set an explicit size for the placeholder measurement
                modifier = Modifier.size(100.dp),
                media = loadable,
                resolution = Resolution.FULL,
                requestBuilderTransformation = ::setupRequestListener,
            )
        }

        // Wait for the [GlideLoadableIdlingResource] to indicate the glide loading
        // pipeline is idle.
        composeTestRule.waitForIdle()

        verify(mockContentProvider)
            .openTypedAssetFile(
                capture(uri),
                capture(mimeType),
                capture(options),
                any(CancellationSignal::class.java)
            )

        assertThat(uri.getValue()).isEqualTo(loadable.getLoadableUri())

        // Glide can only load images, so ensure we're requesting the correct mimeType.
        assertThat(mimeType.getValue()).isEqualTo(DEFAULT_IMAGE_MIME_TYPE)

        // Ensure the CloudProvider is being told to return a preview thumbnail, in case the
        // loadable is a video.
        assertThat(
                options.getValue().getBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL)
            )
            .isTrue()

        // This should not be in the bundle, but the default value returned will be false.
        assertThat(
                options.getValue().containsKey(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB)
            )
            .isFalse()
        assertThat(options.getValue().getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB))
            .isFalse()

        // Ensure the object is a Point, but the actual size doesn't matter in this context.
        assertThat(options.getValue().getParcelable(ContentResolver.EXTRA_SIZE, Point::class.java))
            .isNotNull()
    }

    /**
     * This uses glides internal [RequestListener] to add test hooks into the Glide loading cycle.
     * This registers a listener which will notify the [GlideLoadableIdlingResource] when the
     * configured image load has either completed or failed.
     *
     * Note: This doesn't actually care if the load fails or succeeds, it just unblocks the idling
     * resource so the test can proceed. Any exceptions that are thrown inside of the listener will
     * get swallowed by Glide's internals.
     */
    private fun setupRequestListener(
        media: GlideLoadable,
        resolution: Resolution,
        builder: RequestBuilder<Drawable>
    ): RequestBuilder<Drawable> {

        glideIdlingResource.loadStarted()

        val listener =
            object : RequestListener<Drawable> {

                override fun onLoadFailed(
                    ex: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    glideIdlingResource.loadFinished()
                    // Return false to indicate the target hasn't been modified by the listener.
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    datasource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    glideIdlingResource.loadFinished()
                    // Return false to indicate the target hasn't been modified by the listener.
                    return false
                }
            }

        return builder
            .listener(listener)
            .set(RESOLUTION_REQUESTED, resolution)
            // Ensure that for tests we skip all possible caching options
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .signature(media.getSignature(resolution))
    }
}
