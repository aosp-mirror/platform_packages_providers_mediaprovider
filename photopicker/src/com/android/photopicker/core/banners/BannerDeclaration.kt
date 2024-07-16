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

package com.android.photopicker.core.banners

internal typealias DismissStrategy = BannerDeclaration.DismissStrategy

/**
 * The data required to declare a unique Photopicker Banner implementation.
 *
 * @property id A unique string id of the banner
 * @property dismissable Whether the banner is dismiss-able by the user
 * @property dismissableStrategy How to track the banner's dismiss state.
 */
interface BannerDeclaration {
    val id: String
    val dismissable: Boolean
    val dismissableStrategy: DismissStrategy

    /** Various logic for how banner dismissal is tracked between invocations of Photopicker. */
    enum class DismissStrategy {

        /**
         * The banner dismissal is tracked per uid (caller's uid). Each UID will have its own
         * dismissal state for each banner using this strategy.
         *
         * @see [PhotopickerConfiguration#callingPackageUid]
         */
        PER_UID,

        /**
         * The banner dismissal is tracked globally across all Photopicker configurations. This
         * banner can only be dismissed once across all types of Photopicker sessions. Note that
         * this is ONCE per [UserProfile].
         */
        ONCE,
    }
}
