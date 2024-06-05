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

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provider for fetching [FeatureManager] inside of composables. [staticCompositionLocalOf] is used
 * here because the FeatureManager is unlikely to change or be reassigned, and there are performance
 * benefits for not having to keep track of consumers to apply recompositions. The drawback is that
 * the content this CompositionLocal wraps will be recalculated if the value is changed. Since it's
 * not expected for the FeatureManager reference to be updated (this is being provided by the
 * Activity at the top of the UI tree), and is injected via Hilt, this value should be very stable.
 */
val LocalFeatureManager =
    staticCompositionLocalOf<FeatureManager> { error("No FeatureManager provided") }
