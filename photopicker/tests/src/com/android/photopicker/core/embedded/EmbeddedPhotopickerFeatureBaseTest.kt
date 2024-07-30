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

package com.android.photopicker.core.embedded

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.android.photopicker.core.PhotopickerMain
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest

/**
 * A base test class that includes common utilities for starting a UI test with the Embedded
 * Photopicker compose UI.
 */
abstract class EmbeddedPhotopickerFeatureBaseTest : PhotopickerFeatureBaseTest() {

    /**
     * A helper method that calls into the [PhotopickerMain] composable in the UI stack and provides
     * the correct [CompositionLocalProvider]s required to bootstrap the UI for embedded.
     *
     * Always invoke this composable within the [Dispatchers.MAIN] context so that lifecycle is able
     * to set different states.
     */
    @Composable
    protected fun callEmbeddedPhotopickerMain(
        embeddedLifecycle: EmbeddedLifecycle,
        featureManager: FeatureManager,
        selection: Selection<Media>,
        events: Events,
        bannerManager: BannerManager,
    ) {
        CompositionLocalProvider(
            LocalEmbeddedLifecycle provides embeddedLifecycle,
            LocalViewModelStoreOwner provides embeddedLifecycle,
            LocalOnBackPressedDispatcherOwner provides embeddedLifecycle,
        ) {
            callPhotopickerMain(featureManager, selection, events, bannerManager)
        }
    }
}
