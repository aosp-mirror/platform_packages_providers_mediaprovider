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

package com.android.photopicker.features.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureToken.PREVIEW
import com.android.photopicker.core.glide.RESOLUTION_REQUESTED
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.navigateToPreviewSelection
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/* The minimum width for the selection toggle button */
private val MEASUREMENT_SELECTION_BUTTON_MIN_WIDTH = 150.dp

/* The amount of padding around the selection bar at the bottom of the layout. */
private val MEASUREMENT_SELECTION_BAR_PADDING = 12.dp

/** Padding between the bottom edge of the screen and the snackbars */
private val MEASUREMENT_SNACKBAR_BOTTOM_PADDING = 48.dp

/**
 * Entry point for the [PhotopickerDestinations.PREVIEW_SELECTION] route.
 *
 * This composable will snapshot the current selection when created so that photos are not removed
 * from the list of preview-able photos.
 */
@Composable
fun PreviewSelection(viewModel: PreviewViewModel = obtainViewModel()) {
    val selection by viewModel.selectionSnapshot.collectAsStateWithLifecycle()

    // Only snapshot the selection once when the composable is created.
    LaunchedEffect(Unit) { viewModel.takeNewSelectionSnapshot() }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier =
                // This is inside an edge-to-edge dialog, so apply padding to ensure the
                // UI buttons stay above the navigation bar.
                Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Vertical)
                )
        ) {
            when {
                selection.isEmpty() -> {}
                else -> Preview(selection)
            }
        }
    }
}

/**
 * Entry point for the [PhotopickerDestinations.PREVIEW_MEDIA] route.
 *
 * @param previewItemFlow - A [StateFlow] from the navBackStackEntry savedStateHandler which uses
 *   the [PreviewFeature.PREVIEW_MEDIA_KEY] to retrieve the passed [Media] item to preview.
 */
@Composable
fun PreviewMedia(
    previewItemFlow: StateFlow<Media?>,
) {
    val media by previewItemFlow.collectAsStateWithLifecycle()
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    // create a local variable for the when block so the compiler doesn't complain about the
    // delegate.
    val localMedia = media

    /** SnackbarHost api for launching Snackbars */
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                modifier = Modifier.padding(vertical = 50.dp),
                contentAlignment = Alignment.Center
            ) {
                // Preview session state to keep track if the video player's audio is muted.
                var audioIsMuted by remember { mutableStateOf(true) }
                when (localMedia) {
                    is Media.Image -> ImageUi(localMedia)
                    is Media.Video ->
                        VideoUi(localMedia, audioIsMuted, { audioIsMuted = it }, snackbarHostState)
                    null -> {}
                }
            }
        }

        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    // This is inside an edge-to-edge dialog, so apply padding to ensure the
                    // selection button stays above the navigation bar.
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // Photopicker is (generally) inside of a BottomSheet, and the preview route is inside a
            // dialog, so this requires a custom [SnackbarHost] to draw on top of those elements
            // that do not play nicely with snackbars. Peace was never an option.
            SnackbarHost(snackbarHostState)

            // Once a media item is loaded, display the selection toggles at the bottom.
            if (localMedia != null) {
                val viewModel: PreviewViewModel = obtainViewModel()
                Row {
                    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
                    val selectionLimitExceededMessage =
                        stringResource(
                            R.string.photopicker_selection_limit_exceeded_snackbar,
                            selectionLimit
                        )

                    FilledTonalButton(
                        modifier = Modifier.widthIn(min = MEASUREMENT_SELECTION_BUTTON_MIN_WIDTH),
                        onClick = {
                            viewModel.toggleInSelection(
                                media = localMedia,
                                onSelectionLimitExceeded = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            selectionLimitExceededMessage
                                        )
                                    }
                                }
                            )
                        },
                    ) {
                        Text(
                            if (selection.contains(localMedia))
                                stringResource(R.string.photopicker_deselect_button_label)
                            else stringResource(R.string.photopicker_select_button_label)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composable that creates a [HorizontalPager] and shows items in the provided selection set.
 *
 * @param selection selected items that should be included in the pager.
 */
@Composable
private fun Preview(selection: Set<Media>) {
    val viewModel: PreviewViewModel = obtainViewModel()
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()

    // Preview session state to keep track if the video player's audio is muted.
    var audioIsMuted by remember { mutableStateOf(true) }

    /** SnackbarHost api for launching Snackbars */
    val snackbarHostState = remember { SnackbarHostState() }

    // Page count equal to size of selection
    val state = rememberPagerState { selection.size }
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = selection.elementAt(page)

            when (media) {
                is Media.Image -> ImageUi(media)
                is Media.Video ->
                    VideoUi(media, audioIsMuted, { audioIsMuted = it }, snackbarHostState)
            }
        }

        // Photopicker is (generally) inside of a BottomSheet, and the preview route is inside a
        // dialog, so this requires a custom [SnackbarHost] to draw on top of those elements that do
        // not play nicely with snackbars. Peace was never an option.
        SnackbarHost(
            snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = MEASUREMENT_SNACKBAR_BOTTOM_PADDING)
        )

        // Bottom row of action buttons
        Row(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(MEASUREMENT_SELECTION_BAR_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
            val selectionLimitExceededMessage =
                stringResource(
                    R.string.photopicker_selection_limit_exceeded_snackbar,
                    selectionLimit
                )
            FilledTonalButton(
                modifier =
                    Modifier.widthIn(
                        // Apply a min width to prevent the button re-sizing when the label changes.
                        min = MEASUREMENT_SELECTION_BUTTON_MIN_WIDTH,
                    ),
                onClick = {
                    viewModel.toggleInSelection(
                        media = selection.elementAt(state.currentPage),
                        onSelectionLimitExceeded = {
                            scope.launch {
                                snackbarHostState.showSnackbar(selectionLimitExceededMessage)
                            }
                        }
                    )
                },
            ) {
                Text(
                    if (currentSelection.contains(selection.elementAt(state.currentPage)))
                    // Label: Deselect
                    stringResource(R.string.photopicker_deselect_button_label)
                    // Label: Select
                    else stringResource(R.string.photopicker_select_button_label),
                    color =
                        CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                            MaterialTheme.colorScheme.primary
                        ),
                )
            }

            // Similar button to the Add button on the Selection bar. Clicking this will confirm
            // the current selection and end the session.
            FilledTonalButton(
                onClick = {
                    scope.launch { events.dispatch(Event.MediaSelectionConfirmed(PREVIEW.token)) }
                },
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor =
                            CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                                /* fallback */ MaterialTheme.colorScheme.primary
                            ),
                        contentColor =
                            CustomAccentColorScheme.current
                                .getTextColorForAccentComponentsIfDefinedOrElse(
                                    /* fallback */ MaterialTheme.colorScheme.onPrimary
                                ),
                    )
            ) {
                Text(
                    stringResource(
                        // Label: Add (N)
                        R.string.photopicker_add_button_label,
                        currentSelection.size,
                    )
                )
            }
        }
    }
}

/**
 * Composable that loads a [Media.Image] in [Resolution.FULL] for the user to preview.
 *
 * @param image
 */
@Composable
private fun ImageUi(image: Media.Image) {
    loadMedia(
        media = image,
        resolution = Resolution.FULL,
        modifier = Modifier.fillMaxWidth(),
        // by default loadMedia center crops, so use a custom request builder
        requestBuilderTransformation = { media, resolution, builder ->
            builder.set(RESOLUTION_REQUESTED, resolution).signature(media.getSignature(resolution))
        }
    )
}

/**
 * Composable for [Location.SELECTION_BAR_SECONDARY_ACTION] Creates a button that launches the
 * [PhotopickerDestinations.PREVIEW_SELECTION] route.
 */
@Composable
fun PreviewSelectionButton(modifier: Modifier) {
    val navController = LocalNavController.current

    TextButton(
        onClick = navController::navigateToPreviewSelection,
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.photopicker_preview_button_label),
            color =
                CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                    /* fallback */ MaterialTheme.colorScheme.primary
                )
        )
    }
}
