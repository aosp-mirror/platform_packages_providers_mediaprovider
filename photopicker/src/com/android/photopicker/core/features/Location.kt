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

/**
 * Enumation class of all UI Locations in Photopicker. These are the locations that are usable by
 * [PhotopickerUiFeature]s to register to compose UI.
 *
 * See the [FeatureManager#composeLocation] API to request composition of a location by the
 * currently active list of features.
 *
 * Features may add additional locations to this enumeration group to expose feature specific
 * locations to allow other features to compose into their UI tree.
 *
 * Note: When adding new locations, please group them by feature area, alphabetically.
 */
enum class Location {

    /* CORE Locations */
    COMPOSE_TOP, // UI feature entrypoint at the top of the compose UI tree.

    /* SELECTION_BAR */
    SELECTION_BAR, // Location where the selection bar should be drawn (when it is active).
    SELECTION_BAR_SECONDARY_ACTION, // Location where the extra button on the selection bar.
}
