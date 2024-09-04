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

package com.android.photopicker.core

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerNavGraph
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.transferTouchesToHostInEmbedded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val MEASUREMENT_BOTTOM_SHEET_EDGE_PADDING = 12.dp
private val MEASUREMENT_BANNER_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp)

/* Spacing around the selection bar and the edges of the screen */
private val SELECTION_BAR_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 48.dp)

/**
 * This is an entrypoint of the Photopicker Compose UI. This is called from the MainActivity and is
 * the top-most [@Composable] in the activity application. This should not be called except inside
 * an Activity's [setContent] block.
 *
 * @param onDismissRequest handler for when the BottomSheet is dismissed.
 * @param onMediaSelectionConfirmed A callback to pass to the [Location.SELECTION_BAR] to indicate
 *   the user has indicated the media selection is final.
 * @param preloadMedia A flow of Media that the [MEDIA_PRELOADER] should begin preloading.
 * @param obtainPreloaderDeferred A callback to obtain a deferred for the currently requested media
 *   preload.
 * @param disruptiveDataNotification The data disruption flow that emits when the underlying data
 *   the UI has been created with is invalid
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotopickerAppWithBottomSheet(
    onDismissRequest: () -> Unit,
    onMediaSelectionConfirmed: () -> Unit,
    preloadMedia: Flow<Set<Media>>,
    obtainPreloaderDeferred: () -> CompletableDeferred<Boolean>,
    disruptiveDataNotification: Flow<Int>,
) {
    // Initialize and remember the NavController. This needs to be provided before the call to
    // the NavigationGraph, so this is done at the top.
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current

    // Attach a BackHandler above the BottomSheet & PhotopickerNavGraph composables.
    // The NavHost composable attaches its own BackHandler (below this one) which will become
    // disabled when the backstack size is zero. At that point, Back navigation will reach this
    // handler.
    BackHandler(true) {
        // First try to pop the Backstack, but if that does not result in navigation, the user
        // is at the startDestination with no further location to go back to, so then we should
        // dismiss the Photopicker session.
        if (!navController.popBackStack()) {
            onDismissRequest()
        }
    }

    val state =
        rememberBottomSheetScaffoldState(
            bottomSheetState =
                rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    confirmValueChange = { sheetValue ->
                        when (sheetValue) {
                            // When the sheet is hidden, trigger the onDismissRequest
                            SheetValue.Hidden -> onDismissRequest()
                            // Log picker state change
                            SheetValue.Expanded ->
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.CORE.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.EXPAND_PICKER
                                        )
                                    )
                                }
                            SheetValue.PartiallyExpanded ->
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.CORE.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.COLLAPSE_PICKER
                                        )
                                    )
                                }
                        }
                        true // allow all value changes
                    },

                    // Allow a hidden state to close the bottom sheet.
                    skipHiddenState = false
                )
        )

    // Photopicker's BottomSheet peeks at 75% of screen height.
    val localConfig = LocalConfiguration.current
    val sheetPeekHeight = remember(localConfig) { (localConfig.screenHeightDp * .75).dp }

    // Provide the NavController to the rest of the Compose stack.
    CompositionLocalProvider(
        LocalNavController provides navController,
    ) {
        Column(
            modifier =
                // Apply WindowInsets to this wrapping column to prevent the Bottom Sheet
                // from drawing over the status bars.
                Modifier.windowInsetsPadding(
                    WindowInsets.statusBars.only(WindowInsetsSides.Vertical)
                )
        ) {
            BottomSheetScaffold(
                containerColor = Color.Transparent, // The color used behind the BottomSheet
                scaffoldState = state,
                sheetPeekHeight = sheetPeekHeight,
                sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                sheetContentColor = MaterialTheme.colorScheme.onSurface,
                sheetContent = {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        PhotopickerMain(disruptiveDataNotification)
                        Column(
                            modifier =
                                // Some elements needs to be drawn over the UI inside of the
                                // BottomSheet A negative y offset will move it from the bottom of
                                // the content to the bottom of the onscreen BottomSheet.
                                Modifier.offset {
                                    IntOffset(
                                        x = 0,
                                        y = -state.bottomSheetState.requireOffset().toInt()
                                    )
                                },
                        ) {
                            LocalFeatureManager.current.composeLocation(
                                Location.SNACK_BAR,
                                maxSlots = 1,
                            )
                            LocalFeatureManager.current.composeLocation(
                                Location.SELECTION_BAR,
                                maxSlots = 1,
                                modifier = Modifier.padding(SELECTION_BAR_PADDING),
                                params =
                                    LocationParams.WithClickAction { onMediaSelectionConfirmed() }
                            )
                        }
                    }
                    // If a [MEDIA_PRELOADER] is configured in the current session, attach it
                    // to the compose UI here, so that any dialogs it shows are drawn overtop
                    // of the application.
                    LocalFeatureManager.current.composeLocation(
                        Location.MEDIA_PRELOADER,
                        maxSlots = 1,
                        params =
                            object : LocationParams.WithMediaPreloader {
                                override fun obtainDeferred(): CompletableDeferred<Boolean> {
                                    return obtainPreloaderDeferred()
                                }

                                override val preloadMedia = preloadMedia
                            }
                    )
                }
            ) {
                // Intentionally empty, this is the background content behind the BottomSheet.
            }
        }
    }
}

/**
 * This is an entry point of the Photopicker Compose UI. This is called from a hosting View and is
 * the top-most [@Composable] in the view based application. This should not be called by any
 * Activity code, and should only be called inside of the ComposeView [setContent] block.
 *
 * @param disruptiveDataNotification The data disruption flow that emits when the underlying data
 *   the UI has been created with is invalid
 * @param onMediaSelectionConfirmed A callback to pass to the [Location.SELECTION_BAR] to indicate
 *   the user has indicated the media selection is final.
 */
@Composable
fun PhotopickerApp(
    disruptiveDataNotification: Flow<Int>,
    onMediaSelectionConfirmed: () -> Unit,
) {
    // Initialize and remember the NavController. This needs to be provided before the call to
    // the NavigationGraph, so this is done at the top.
    val navController = rememberNavController()

    // Provide the NavController to the rest of the Compose stack.
    CompositionLocalProvider(LocalNavController provides navController) {
        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
            PhotopickerMain(disruptiveDataNotification)
            Column {
                LocalFeatureManager.current.composeLocation(
                    Location.SNACK_BAR,
                    maxSlots = 1,
                )
                hideWhenState(StateSelector.EmbeddedAndCollapsed) {
                    LocalFeatureManager.current.composeLocation(
                        Location.SELECTION_BAR,
                        maxSlots = 1,
                        modifier = Modifier.padding(SELECTION_BAR_PADDING),
                        params = LocationParams.WithClickAction { onMediaSelectionConfirmed() }
                    )
                }
            }
        }
    }
}

/**
 * This is the shared entry point for the Photopicker compose-UI. Composables above this function
 * must provide the required dependencies to the compose UI before calling this entry point.
 *
 * It is presumed after this composable the compose UI can either be running inside of a wrapped
 * View or an Activity lifecycle.
 *
 * By this entrypoint, the expected CompositionLocals should already exist:
 * - LocalEvents
 * - LocalFeatureManager
 * - LocalNavController
 * - LocalPhotopickerConfiguration
 * - LocalSelection
 * - PhotopickerTheme
 *
 * @param disruptiveDataNotification The data disruption flow that emits when the underlying data
 *   the UI has been created with is invalid
 */
@Composable
fun PhotopickerMain(disruptiveDataNotification: Flow<Int>) {

    // Collect the data disrupt flow so that Photopicker will navigate on disruptive data changes.
    // The data service can detect when the providers that are supplying grid data have changed
    // in such a way that the grid should immediately be cleared as the new list of providers
    // does not include the providers that have populated the currently displayed view. When
    // this DisruptionSignal is sent, we collect the value here to force recomposition to rebuild
    // the view immediately.
    val disruptCounter by disruptiveDataNotification.collectAsStateWithLifecycle(initialValue = 0)
    watchForDataDisruptions(disruptCounter)
    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val host = LocalEmbeddedState.current?.host
    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            // The navigation bar and banners are drawn above the navigation graph
            hideWhenState(selector = StateSelector.EmbeddedAndCollapsed) {
                LocalFeatureManager.current.composeLocation(
                    Location.NAVIGATION_BAR,
                    maxSlots = 1,
                    modifier =
                        if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                            Modifier.fillMaxWidth()
                                .transferTouchesToHostInEmbedded(
                                    host = host,
                                )
                        } else {
                            Modifier.fillMaxWidth()
                        }
                )
            }

            // Initialize the navigation graph.
            PhotopickerNavGraph()
        }
    }
}

/**
 * Attaches a [LaunchedEffect] to the compose hierarchy that runs whenever the disruptionCounter is
 * changed. This will attempt to navigate the session back to the navigation graph's starting route
 * since a Data Disruption means that the current view is unstable and likely stale / obsolete.
 *
 * To prevent showing data that is irrelevant to the user in a route that may no longer exist (i.e
 * inside of an Album in a provider that is no longer attached), the session is force-navigated to
 * the main route.
 *
 * @param disruptionCounter the current disruptionCounter value from the data service.
 */
@Composable
private fun watchForDataDisruptions(disruptionCounter: Int) {

    val navController = LocalNavController.current
    val selection = LocalSelection.current
    LaunchedEffect(disruptionCounter) {
        if (disruptionCounter > 0) {
            Log.d("Photopicker", "DisruptiveData notification received.")

            // The selection may contain items from the provider that was removed, since this is
            // a very unlikely event, the entire selection will be cleared to prevent the user
            // from selecting any media from a provider that may no longer exist, or may be in a
            // bad state.
            selection.clear()

            try {
                val startDestination =
                    checkNotNull(navController.graph.startDestinationRoute) {
                        "startDestination was Null"
                    }
                if (navController.currentBackStackEntry?.destination?.route != startDestination) {

                    // Try to return to the start destination for the data reload, by attempting to
                    // move backwards via the backstack.
                    val navigated =
                        navController.popBackStack(
                            startDestination,
                            /* inclusive= */ false,
                            /* saveState = */ false,
                        )

                    // The start route is not on the backstack, so as a last resort, navigate
                    // directly.
                    if (!navigated) {
                        navController.navigate(startDestination, /* navOptions= */ null)
                    }
                }
            } catch (e: IllegalStateException) {
                Log.e(
                    "Photopicker",
                    "disruptiveDataNotification was received, but unable to resolve the graph.",
                    e
                )
            }
        }
    }
}
