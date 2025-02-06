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

import android.os.UserHandle
import android.util.Log
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.extensions.pmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A production implementation of [BannerManager] */
class BannerManagerImpl(
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val configurationManager: ConfigurationManager,
    private val databaseManager: DatabaseManager,
    private val featureManager: FeatureManager,
    private val dataService: DataService,
    private val userMonitor: UserMonitor,
    private val processOwnerHandle: UserHandle,
) : BannerManager {

    companion object {
        val TAG = "PhotopickerBannerManager"
        const val GET_BANNER_PRIORITY_TIMEOUT_MS = 1000L
    }

    val _flow: MutableStateFlow<Banner?> = MutableStateFlow(null)
    override val flow: StateFlow<Banner?> = _flow

    /**
     * Keeps track of any banners with [DismissStrategy.SESSION] that were dismissed during the
     * current Photopicker session.
     */
    private val bannersDismissedInSession: MutableSet<BannerDeclaration> = mutableSetOf()

    init {
        // Observe Profile switches and always force banner refresh when the
        // user changes the active profile.
        scope.launch {
            userMonitor.userStatus
                .drop(1)
                .map { it.activeUserProfile }
                .distinctUntilChanged()
                .collect { refreshBanners() }
        }
    }

    /**
     * Attempt to show the requested banner.
     *
     * Unless a specific banner is needed, it is better to use [refreshBanners] to allow the banner
     * with the highest priority to be shown.
     */
    override suspend fun showBanner(banner: BannerDeclaration) {
        try {
            _flow.updateAndGet { generateBanner(banner) }
        } catch (ex: RuntimeException) {
            // Avoid a crash if the banner cannot be generated
            // Instead do nothing and return.
            Log.e(TAG, "Could now show banner: ${banner.id}", ex)
            return
        }
    }

    /** Hides the current banner, if any. (But does not mark it as dismissed) */
    override fun hideBanners() {
        _flow.updateAndGet { null }
    }

    /** Attempt to mark the banner as dismissed in current context. */
    override suspend fun markBannerAsDismissed(banner: BannerDeclaration) {

        if (banner.dismissable) {

            // For SESSION dismissableStrategy banners, rather than writing state to the database,
            // add the banner to the dismissed set that for this Photopicker session.
            if (banner.dismissableStrategy == DismissStrategy.SESSION) {
                bannersDismissedInSession.add(banner)
                return
            }

            // For all other strategies, update the Database state for the current banner.
            setBannerState(
                BannerState(
                    bannerId = banner.id,
                    uid =
                        when (banner.dismissableStrategy) {
                            DismissStrategy.PER_UID ->
                                configurationManager.configuration.value.callingPackageUid
                                    ?: run {
                                        // If there is no Uid set in the configuration, this
                                        // dismiss-able state can't actually be updated.
                                        Log.w(
                                            TAG,
                                            "Cannot mark ${banner.id} as dismissed for UID," +
                                                " no UID present in configuration.",
                                        )
                                        return@markBannerAsDismissed
                                    }
                            DismissStrategy.ONCE -> 0
                            DismissStrategy.SESSION -> {
                                return@markBannerAsDismissed
                            }
                            DismissStrategy.NONE -> {
                                Log.w(TAG, "Cannot mark non-dismissable banner as dismissed.")
                                return@markBannerAsDismissed
                            }
                        },
                    dismissed = true,
                )
            )
        }
    }

    /** Retrieve the requested banner state from the database */
    override suspend fun getBannerState(banner: BannerDeclaration): BannerState? {

        // No need to check the database if the banner cannot be dismissed.
        if (banner.dismissableStrategy == DismissStrategy.NONE) {
            return null
        }

        // For SESSION dismissal, rely on the dismissed map. If the Banner is
        // present in the already-dismissed set, mark it as dismissed.
        if (banner.dismissableStrategy == DismissStrategy.SESSION) {
            return BannerState(
                bannerId = banner.id,
                uid = configurationManager.configuration.value.callingPackageUid ?: 0,
                dismissed = bannersDismissedInSession.contains(banner),
            )
        }

        return withContext(backgroundDispatcher) {
            try {
                databaseManager
                    .acquireDao(BannerStateDao::class.java)
                    .getBannerState(
                        bannerId = banner.id,
                        uid =
                            when (banner.dismissableStrategy) {
                                DismissStrategy.PER_UID ->
                                    checkNotNull(
                                        configurationManager.configuration.value.callingPackageUid
                                    ) {
                                        "No callingPackageUid"
                                    }
                                DismissStrategy.ONCE -> 0
                                else -> 0
                            },
                    )
            } catch (ex: IllegalStateException) {
                Log.w(
                    TAG,
                    "Attempted to retrieve a PER_UID banner state and no uid was present in the" +
                        " configuration.",
                    ex,
                )
                null
            }
        }
    }

    /** Persist the banner state to the database */
    override suspend fun setBannerState(bannerState: BannerState) {
        withContext(backgroundDispatcher) {
            databaseManager.acquireDao(BannerStateDao::class.java).setBannerState(bannerState)
        }
    }

    override suspend fun refreshBanners() {
        Log.d(TAG, "Refresh of banners was requested.")

        // [BannerState] is not accessible cross-profile, so any time the [activeUserProfile]
        // is not the Process owner's profile, banners need to be hidden to avoid showing
        // banner content that is not relevant to the active profile.
        if (
            userMonitor.userStatus.value.activeUserProfile.handle.identifier !=
                processOwnerHandle.getIdentifier()
        ) {
            Log.d(
                TAG,
                "User profile has been changed and is no longer owner, banners will be cleared.",
            )
            _flow.updateAndGet { null }
            return
        }

        // Force this work to the background
        withContext(backgroundDispatcher) {

            // Acquire all possible active banners and their relative priority from
            // the enabled ui features.
            val allAvailableBanners: MutableList<Pair<BannerDeclaration, Int>> =
                featureManager.enabledUiFeatures
                    // FlatMap from List<List<Pair<PhotopickerUiFeature,BannerDeclaration>>> to a
                    // single Iterable list so the work can be run in parallel in the next step.
                    .flatMap { feature -> feature.ownedBanners.map { Pair(feature, it) } }
                    // Use [pmap] to launch these in parallel, so each banner checked
                    // does not accumulate the total time of this call.
                    .pmap { (feature, banner) ->
                        val priority =
                            try {
                                // Calls to acquire the banner's priority. This call has a time
                                // limit as the feature may be requesting external data that may
                                // take too long to respond. Calls that exceed the time limit will
                                // be assigned a -1 priority.
                                withTimeout(GET_BANNER_PRIORITY_TIMEOUT_MS) {
                                    feature.getBannerPriority(
                                        banner,
                                        getBannerState(banner),
                                        configurationManager.configuration.value,
                                        dataService,
                                        userMonitor,
                                    )
                                }
                            } catch (_: TimeoutCancellationException) {
                                Log.v(TAG, "getBannerPriority timed out for ${banner.id}")
                                // In the event of a timeout, return a negative number so
                                // that the banner will be skipped
                                -1
                            }

                        Pair(banner, priority)
                    }
                    .filter { it.second >= 0 } // Skip any banners with a priority below zero
                    .toMutableList()

            // Finally, sort by the reported priorities.
            // This is a stable sort that will order elements by Priority first, feature
            // banner registration order second, and overall feature registration order third.
            allAvailableBanners.sortByDescending { it.second }

            val banner = allAvailableBanners.firstOrNull()
            banner?.let {
                Log.d(TAG, "Banner refresh completed, ${banner.first.id} will be shown")
                showBanner(banner.first)
            }
                ?: run {
                    Log.d(TAG, "Banner refresh completed, no banner was selected.")
                    _flow.updateAndGet { null }
                }
        }
    }

    /**
     * Locates the [PhotopickerUiFeature] responsible for building the [BannerDeclaration] and calls
     * the factory builder.
     *
     * @param [BannerDeclaration] to acquire an implementation for.
     * @return a [Banner] implementation for the provided [BannerDeclaration]
     */
    private suspend fun generateBanner(banner: BannerDeclaration): Banner {
        val feature: PhotopickerUiFeature? =
            featureManager.enabledUiFeatures
                .filter { it.ownedBanners.contains(banner) }
                .firstOrNull()
        checkNotNull(feature) { "Could not find an enabled builder for $banner" }
        return feature.buildBanner(
            checkNotNull(banner as? BannerDefinitions) {
                "Could not cast declaration to valid banner definition"
            },
            dataService,
            userMonitor,
        )
    }
}
