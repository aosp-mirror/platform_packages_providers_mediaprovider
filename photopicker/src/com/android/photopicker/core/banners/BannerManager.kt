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

import kotlinx.coroutines.flow.StateFlow

/**
 * The [BannerManager] is responsible for managing the global state of banners across various
 * Photopicker activities, recalling that state, and providing a [Banner] implementation to the
 * compose UI for each banner declared in [BannerDeclaration].
 *
 * Banners must be declared in a [PhotopickerUiFeature] and the implementation is provided by the
 * owning feature. BannerManager coordinates the implementation with each active feature at runtime,
 * and provides access to the persisted [BannerState] for each [BannerDeclaration] in the current
 * [PhotopickerConfiguration] context. Individual features fully control their respective banner's
 * implementation, and display priority. BannerManager just provides persisted state and
 * orchestrates / enforces the correct call structure to generate banners during runtime.
 *
 * Additionally, a set of APIs to show, hide and mark banners as dismissed in the persisted state
 * are available for use. Individual [BannerState] can also be set and retrieved.
 *
 * @see [Banner] and [BannerDeclaration] for implementing banners.
 * @see [PhotopickerUiFeature] for adding a banner to a feature's registration.
 */
interface BannerManager {

    /** A flow of the currently active Banner. NULL if no banner is currently active. */
    val flow: StateFlow<Banner?>

    /**
     * Set the currently shown banner to a banner which implements the provided [BannerDeclaration]
     *
     * This method will attempt to locate a factory for the provided [BannerDeclaration]
     *
     * @param banner The [BannerDeclaration] to build.
     */
    suspend fun showBanner(banner: BannerDeclaration)

    /**
     * Immediately hides any shown banners.
     *
     * Calling this while no banner is active will have no effect.
     */
    fun hideBanners()

    /**
     * Mark the [BannerDeclaration] as dismissed in the current runtime context.
     *
     * This will be handled differently based on the [BannerDeclaration.DismissStrategy] of the
     * provided BannerDeclaration. If the [BannerDeclaration.dismissable] is FALSE, this has no
     * effect on internal [BannerState].
     *
     * @param banner The BannerDeclaration to mark as dismissed.
     */
    suspend fun markBannerAsDismissed(banner: BannerDeclaration)

    /**
     * Refresh the current banner state by evaluating all enabled banners again. The banner with the
     * highest returned priority will be shown when this method is complete. Priorities below zero
     * are ignored. This method is time-limited, but can result in external data calls depending on
     * the enabled banners implementation.
     *
     * If no BannerDeclaration has a valid priority, this method clears the existing banner.
     */
    suspend fun refreshBanners()

    /**
     * Retrieve the persisted [BannerState] for the requested [BannerDeclaration].
     *
     * Note: This will only return a [BannerState] that matches the current
     * [PhotopickerConfiguration] constraints, specifically the callingPackageUid in the case of
     * banners that are using the [BannerDeclaration.DismissStrategy.PER_UID].
     *
     * @return The persisted [BannerState] for the [BannerDeclaration] in the current runtime
     *   context. This returns null when there is no persisted [BannerState] for the current runtime
     *   context.
     */
    suspend fun getBannerState(banner: BannerDeclaration): BannerState?

    /**
     * Persists a [BannerState] to be retrieved later. This persistence out lives any individual
     * activity.
     */
    suspend fun setBannerState(bannerState: BannerState)
}
