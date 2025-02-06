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

/**
 * An registry of all supported [Banner]s in Photopicker. Any Banner that must be shown via
 * [BannerManager] needs to first define itself in this class.
 *
 * Banners are essentially defined by three values, the id, if the banner can be dismissed by the
 * user, and the strategy for tracking it's dismissal. Actual banner implementations rely on the
 * [Banner] interface.
 *
 * @property id A unique (to this enum) string id of the banner
 * @property dismissable Whether the banner is dismiss-able by the user
 * @property dismissableStrategy How to track the banner's dismiss state.
 * @see [Banner] for details for actually implementing a banner that can be displayed.
 * @see [BannerDeclaration.DismissStrategy] for details about how dismiss state can be tracked.
 */
enum class BannerDefinitions(
    override val id: String,
    override val dismissableStrategy: DismissStrategy,
) : BannerDeclaration {

    // keep-sorted start
    CLOUD_CHOOSE_ACCOUNT("cloud_choose_account", DismissStrategy.ONCE),
    CLOUD_CHOOSE_PROVIDER("cloud_choose_provider", DismissStrategy.ONCE),
    CLOUD_MEDIA_AVAILABLE("cloud_media_available", DismissStrategy.ONCE),
    CLOUD_UPDATED_ACCOUNT("cloud_updated_account", DismissStrategy.ONCE),
    PRIVACY_EXPLAINER("privacy_explainer", DismissStrategy.PER_UID),
    SWITCH_PROFILE("switch_profile", DismissStrategy.PER_UID);

    // keep-sorted end

    override val dismissable: Boolean =
        when (dismissableStrategy) {
            DismissStrategy.ONCE,
            DismissStrategy.PER_UID,
            DismissStrategy.SESSION -> true
            DismissStrategy.NONE -> false
        }
}
