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

import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.load.engine.executor.MockGlideExecutor
import org.junit.rules.ExternalResource

/**
 * A JUnit rule that configures Glide for use in tests.
 *
 * ```
 * @get:Rule val glideRule = GlideTestRule()
 * ```
 */
class GlideTestRule : ExternalResource() {

    override fun before() {

        // For tests, force Glide onto the main thread, rather than it's private executor pool.
        val glideExecutor: GlideExecutor = MockGlideExecutor.newMainThreadExecutor()
        Glide.init(
            ApplicationProvider.getApplicationContext(),
            GlideBuilder()
                .setDiskCacheExecutor(glideExecutor)
                .setAnimationExecutor(glideExecutor)
                .setSourceExecutor(glideExecutor),
        )
    }

    override fun after() {
        Glide.tearDown()
    }
}
