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

package com.android.photopicker.features.preparemedia

import com.android.photopicker.data.model.Media

/** Interface that represents the result of media preparation. */
sealed interface PrepareMediaResult {

    /** Indicates that media preparation failed. */
    data object PrepareMediaFailed : PrepareMediaResult

    /**
     * Represents a successful media preparation result, containing a set of prepared media objects.
     *
     * @property preparedMedia The set of prepared media objects.
     */
    data class PreparedMedia(val preparedMedia: Set<Media>) : PrepareMediaResult
}
