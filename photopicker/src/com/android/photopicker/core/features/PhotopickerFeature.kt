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

import com.android.photopicker.core.PhotopickerConfiguration

/**
 * The base feature interface, that exposes hooks from the [FeatureManager] that is available to all
 * features. It's unlikely that features will want to implement this directly, but rather implement
 * a different interface, such as [PhotopickerUiFeature] which inherits from the base interface.
 */
interface PhotopickerFeature {

    /**
     * Notification hook that the [FeatureManager] is about to emit a new configuration, and
     * reinitialize the active Feature set.
     *
     * It is highly likely that shortly after this call completes the current instance of this class
     * will be de-referenced and (potentially) be re-created if the Registration check still
     * indicates it should be enabled with the new configuration.
     *
     * The UI tree will be re-composed shortly after, and persistent state should be saved in
     * associated view models to avoid state loss.
     */
    fun onConfigurationChanged(config: PhotopickerConfiguration) {}
}
