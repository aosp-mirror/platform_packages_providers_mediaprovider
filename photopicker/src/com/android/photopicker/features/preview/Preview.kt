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
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/* The minimum width for the selection toggle button */
private val MEASUREMENT_SELECTION_BUTTON_MIN_WIDTH = 150.dp

/* The amount of padding around the selection bar at the bottom of the layout. */
private val MEASUREMENT_SELECTION_BAR_PADDING = 12.dp

/** Padding between the bottom edge of the screen and the snackbars */
private val MEASUREMENT_SNACKBAR_BOTTOM_PADDING = 48.dp

/**
 * Entry point for the [PhotopickerDestinations.PREVIEW_SELECTION] and
 * [PhotopickerDestinations.PREVIEW_MEDIA]route.
 *
 * This composable will snapshot the current selection when created so that photos are not removed
 * from the list of preview-able photos.
 */
@Composable
fun PreviewSelection(
    viewModel: PreviewViewModel = obtainViewModel(),
    previewItemFlow: StateFlow<Media?>? = null
) {
    val selection =
        when (previewItemFlow != null) {
            true -> {
                val media by previewItemFlow.collectAsStateWithLifecycle()
                val localMedia = media
                if (localMedia != null) {
                    viewModel
                        .getPreviewMediaIncludingPreGrantedItems(
                            setOf(localMedia),
                            LocalPhotopickerConfiguration.current,
                            /* isSingleItemPreview */ true
                        )
                        .collectAsLazyPagingItems()
                } else {
                    null
                }
            }
            false -> {
                val selectionSnapshot by viewModel.selectionSnapshot.collectAsStateWithLifecycle()
                viewModel
                    .getPreviewMediaIncludingPreGrantedItems(
                        selectionSnapshot,
                        LocalPhotopickerConfiguration.current
                    )
                    .collectAsLazyPagingItems()
            }
        }

    if (selection != null) {
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
                if (selection.itemCount > 0) {
                    // When previewItemFlow is populated, it suggests that the code has reached here
                    // by the single item preview (usually by long press on the item). In this case
                    // only the select/deselect option needs to be shown. Add button should not be
                    // displayed. Hence This information is used to create the UI for preview.
                    Preview(selection, /* shouldShowAddButton */ previewItemFlow == null)
                }
            }
        }
    }
}

/**
 * Composable that creates a [HorizontalPager] and shows items in the provided selection set.
 *
 * @param selection selected items that should be included in the pager.
 * @param shouldShowAddButton flags if the add button should be displayed on the UI or not.
 */
@Composable
private fun Preview(selection: LazyPagingItems<Media>, shouldShowAddButton: Boolean = false) {
    val viewModel: PreviewViewModel = obtainViewModel()
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Preview session state to keep track if the video player's audio is muted.
    var audioIsMuted by remember { mutableStateOf(true) }

    /** SnackbarHost api for launching Snackbars */
    val snackbarHostState = remember { SnackbarHostState() }

    // Page count equal to size of selection
    val state = rememberPagerState { selection.itemCount }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selection.itemCount > 0) {
            HorizontalPager(
                state = state,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val media = selection.get(page)
                if (media != null) {
                    when (media) {
                        is Media.Image -> ImageUi(media)
                        is Media.Video ->
                            VideoUi(media, audioIsMuted, { audioIsMuted = it }, snackbarHostState)
                    }
                }
            }

            // Photopicker is (generally) inside of a BottomSheet, and the preview route is inside a
            // dialog, so this requires a custom [SnackbarHost] to draw on top of those elements
            // that do not play nicely with snackbars. Peace was never an option.
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
                horizontalArrangement =
                    if (shouldShowAddButton) Arrangement.SpaceBetween else Arrangement.Center,
            ) {
                selection.get(state.currentPage)?.let {
                    ItemSelectionStatusButton(
                        viewModel,
                        snackbarHostState,
                        scope,
                        it,
                        currentSelection
                    )
                }

                if (shouldShowAddButton) {
                    addButton(currentSelection, scope)
                }
            }
        }
    }
}

/**
 * Based on current selection, displays the current status of the media. Also enables toggle for the
 * selection state of the item.
 *
 * @param viewModel viewmodel for the current preview session
 * @param snackbarHostState state for the snackbar host
 * @param scope scope used to launch snackbar
 * @param media item that is under preview, for which the selection status needs to be displayed
 * @param currentSelection represents the set of current selection, used to toggle selection status
 */
@Composable
private fun ItemSelectionStatusButton(
    viewModel: PreviewViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    media: Media,
    currentSelection: Set<Media>
) {
    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val selectionLimitExceededMessage =
        stringResource(R.string.photopicker_selection_limit_exceeded_snackbar, selectionLimit)
    FilledTonalButton(
        modifier =
            Modifier.widthIn(
                // Apply a min width to prevent the button re-sizing when the label changes.
                min = MEASUREMENT_SELECTION_BUTTON_MIN_WIDTH,
            ),
        onClick = {
            viewModel.toggleInSelection(
                media = media,
                onSelectionLimitExceeded = {
                    scope.launch { snackbarHostState.showSnackbar(selectionLimitExceededMessage) }
                }
            )
        },
    ) {
        Text(
            if (currentSelection.contains(media))
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
}

/**
 * Displays an add button containing the count of selection and clickable action that dispatches the
 * event signifying that selection has been confirmed.
 *
 * @param currentSelection represents the set of current selection, used to toggle selection status
 * @param scope scope used to launch events for confitmation of selection
 */
@Composable
private fun addButton(currentSelection: Set<Media>, scope: CoroutineScope) {
    val events = LocalEvents.current
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
                    CustomAccentColorScheme.current.getTextColorForAccentComponentsIfDefinedOrElse(
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
