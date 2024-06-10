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

package com.android.photopicker.core.features

import com.android.photopicker.data.model.Media
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Parameter interface for passing additional parameters to a [Location]'s implementer via
 * [FeatureManager#composeLocation].
 *
 * By default all Locations receive the None parameter, but this interface can be extended and then
 * location code can cast to the expected type with a pattern such as:
 * ```
 * val clickAction = params as? LocationParams.WithClickAction
 * clickAction?.onClick()
 * ```
 *
 * Or narrow the type using a `when` block. These interfaces can be combined into custom types to
 * ensure compile time type-checking of parameter types. `Any` should not be used to pass
 * parameters.
 */
sealed interface LocationParams {

    /** The default parameters, which represents no additional parameters provided. */
    object None : LocationParams

    /**
     * A generic click handler parameter. Including this as a parameter doesn't attach the click
     * handler to anything, the implementer must call this method in response to the click action.
     */
    fun interface WithClickAction : LocationParams {
        fun onClick()
    }

    /** Requirements for attaching a [MediaPreloader] to the compose UI. */
    interface WithMediaPreloader : LocationParams {

        // Method which can be called to obtain a deferred for the currently requested preload
        // operation.
        fun obtainDeferred(): CompletableDeferred<Boolean>

        // Flow to trigger the start of media preloads.
        val preloadMedia: Flow<Set<Media>>
    }
}
