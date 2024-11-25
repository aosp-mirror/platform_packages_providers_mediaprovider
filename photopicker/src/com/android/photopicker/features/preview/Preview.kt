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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.glide.RESOLUTION_REQUESTED
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.SelectionStrategy
import com.android.photopicker.core.selection.SelectionStrategy.Companion.determineSelectionStrategy
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.core.theme.LocalFixedAccentColors
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.navigateToPreviewSelection
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    previewItemFlow: StateFlow<Media?>? = null,
) {
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    val previewSingleItem =
        when (previewItemFlow) {
            null -> false
            else -> true
        }

    val selection =
        when (previewSingleItem) {
            true -> {
                checkNotNull(previewItemFlow) { "Flow cannot be null for previewSingleItem" }
                val media by previewItemFlow.collectAsStateWithLifecycle()
                val localMedia = media
                if (localMedia != null) {
                    viewModel
                        .getPreviewMediaIncludingPreGrantedItems(
                            setOf(localMedia),
                            LocalPhotopickerConfiguration.current,
                            /* isSingleItemPreview */ true,
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
                        LocalPhotopickerConfiguration.current,
                        /* isSingleItemPreview */ false,
                    )
                    .collectAsLazyPagingItems()
            }
        }

    if (selection != null) {
        // Only snapshot the selection once when the composable is created.
        LaunchedEffect(Unit) { viewModel.takeNewSelectionSnapshot() }
        val navController = LocalNavController.current

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(
                modifier =
                    // This is inside an edge-to-edge dialog, so apply padding to ensure the
                    // UI buttons stay above the navigation bar.
                    Modifier.windowInsetsPadding(
                        WindowInsets.statusBars.only(WindowInsetsSides.Vertical)
                    )
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp, start = 8.dp)
                ) {
                    // back button
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // For accessibility
                            contentDescription = stringResource(R.string.photopicker_back_option),
                            tint = Color.White,
                        )
                    }
                }

                /** SnackbarHost api for launching Snackbars */
                val snackbarHostState = remember { SnackbarHostState() }

                // Page count equal to size of selection
                val state = rememberPagerState { selection.itemCount }

                Box(modifier = Modifier.weight(1f)) {
                    if (selection.itemCount > 0) {
                        // Add the pager to show the media.
                        PreviewPager(
                            Modifier.align(Alignment.Center),
                            selection,
                            state,
                            snackbarHostState,
                            /* singleItemPreview */ previewSingleItem,
                        )

                        // Only show the selection button if not in single select.
                        if (LocalPhotopickerConfiguration.current.selectionLimit > 1) {
                            IconButton(
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp),
                                onClick = {
                                    val media = selection.get(state.currentPage)
                                    media?.let { viewModel.toggleInSelection(it, {}) }
                                },
                            ) {
                                if (currentSelection.contains(selection.get(state.currentPage))) {
                                    val deselectActionLabel =
                                        stringResource(
                                            R.string.photopicker_deselect_action_description
                                        )
                                    Icon(
                                        ImageVector.vectorResource(
                                            R.drawable.photopicker_selected_media
                                        ),
                                        modifier =
                                            Modifier
                                                // Background is necessary because the icon has
                                                // negative
                                                // space.
                                                .background(
                                                    MaterialTheme.colorScheme.onPrimary,
                                                    CircleShape,
                                                )
                                                .semantics {
                                                    onClick(
                                                        label = deselectActionLabel,
                                                        action = null,
                                                    )
                                                },
                                        contentDescription =
                                            stringResource(R.string.photopicker_item_selected),
                                        tint =
                                            CustomAccentColorScheme.current
                                                .getAccentColorIfDefinedOrElse(
                                                    /* fallback */ MaterialTheme.colorScheme.primary
                                                ),
                                    )
                                } else {
                                    val selectActionLabel =
                                        stringResource(
                                            R.string.photopicker_select_action_description
                                        )
                                    Icon(
                                        Icons.Outlined.Circle,
                                        contentDescription =
                                            stringResource(R.string.photopicker_item_not_selected),
                                        tint = Color.White,
                                        modifier =
                                            Modifier.semantics {
                                                onClick(label = selectActionLabel, action = null)
                                            },
                                    )
                                }
                            }
                        }
                    }
                    // Photopicker is (generally) inside of a BottomSheet, and the preview route
                    // is inside a dialog, so this requires a custom [SnackbarHost] to draw on
                    // top of those elements that do not play nicely with snackbars. Peace was
                    // never an option.
                    SnackbarHost(
                        snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Bottom row of action buttons
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(bottom = 48.dp, start = 4.dp, end = 16.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val config = LocalPhotopickerConfiguration.current
                    val strategy = remember(config) { determineSelectionStrategy(config) }
                    if (previewSingleItem || strategy == SelectionStrategy.GRANTS_AWARE_SELECTION) {
                        Spacer(Modifier.size(8.dp))
                    } else {
                        SelectionButton(currentSelection = currentSelection)
                    }

                    FilledTonalButton(
                        onClick = {
                            if (config.selectionLimit == 1) {
                                val media = selection.get(state.currentPage)
                                media?.let { viewModel.toggleInSelection(it, {}) }
                            } else {
                                navController.popBackStack()
                            }
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
                            ),
                    ) {
                        Text(
                            text =
                                when (config.selectionLimit) {
                                    1 ->
                                        stringResource(
                                            R.string.photopicker_select_current_button_label
                                        )
                                    else -> stringResource(R.string.photopicker_done_button_label)
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionButton(
    currentSelection: Set<Media>,
    viewModel: PreviewViewModel = obtainViewModel(),
) {

    TextButton(
        onClick = {
            if (currentSelection.size > 0 && viewModel.selectionSnapshot.value.size > 0) {
                // Deselect All in current selection
                viewModel.toggleInSelection(currentSelection, {})
            } else {
                // Select All in snapshot
                viewModel.toggleInSelection(viewModel.selectionSnapshot.value, {})
            }
        },
        colors =
            ButtonDefaults.textButtonColors(
                contentColor =
                    // The background color for Preview is always fixed to Black, so when the
                    // custom accent color is defined, switch to a White color for this button
                    // so it doesn't clash with the custom color.
                    if (CustomAccentColorScheme.current.isAccentColorDefined()) Color.White
                    else LocalFixedAccentColors.current.primaryFixedDim
            ),
    ) {
        if (currentSelection.size > 0) {
            Icon(ImageVector.vectorResource(R.drawable.tab_close), contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.photopicker_deselect_button_label, currentSelection.size))
        } else {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(
                    R.string.photopicker_select_button_label,
                    viewModel.selectionSnapshot.value.size,
                )
            )
        }
    }
}

/**
 * Composable that creates a [HorizontalPager] and shows items in the provided selection set.
 *
 * @param modifier
 * @param selection selected items that should be included in the pager.
 * @param state
 * @param snackbarHostState
 */
@Composable
private fun PreviewPager(
    modifier: Modifier,
    selection: LazyPagingItems<Media>,
    state: PagerState,
    snackbarHostState: SnackbarHostState,
    singleItemPreview: Boolean,
) {
    // Preview session state to keep track if the video player's audio is muted.
    var audioIsMuted by remember { mutableStateOf(true) }

    HorizontalPager(state = state, modifier = modifier) { page ->
        val media = selection.get(page)
        if (media != null) {
            when (media) {
                is Media.Image -> ImageUi(media, singleItemPreview)
                is Media.Video ->
                    VideoUi(
                        media,
                        audioIsMuted,
                        { audioIsMuted = it },
                        snackbarHostState,
                        singleItemPreview,
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
private fun ImageUi(image: Media.Image, singleItemPreview: Boolean) {
    if (singleItemPreview) {
        val events = LocalEvents.current
        val scope = rememberCoroutineScope()
        val configuration = LocalPhotopickerConfiguration.current

        scope.launch {
            val mediaType =
                if (image.mimeType.contains("gif")) {
                    Telemetry.MediaType.GIF
                } else {
                    Telemetry.MediaType.PHOTO
                }
            // Mark entry into preview mode by long pressing on the media item
            events.dispatch(
                Event.LogPhotopickerPreviewInfo(
                    FeatureToken.PREVIEW.token,
                    configuration.sessionId,
                    Telemetry.PreviewModeEntry.LONG_PRESS,
                    previewItemCount = 1,
                    mediaType,
                    Telemetry.VideoPlayBackInteractions.UNSET_VIDEO_PLAYBACK_INTERACTION,
                )
            )
        }
    }
    loadMedia(
        media = image,
        resolution = Resolution.FULL,
        modifier = Modifier.fillMaxSize(),
        // by default loadMedia center crops, so use a custom request builder
        requestBuilderTransformation = { media, resolution, builder ->
            builder.set(RESOLUTION_REQUESTED, resolution).signature(media.getSignature(resolution))
        },
    )
}

/**
 * Composable for [Location.SELECTION_BAR_SECONDARY_ACTION] Creates a button that launches the
 * [PhotopickerDestinations.PREVIEW_SELECTION] route.
 */
@Composable
fun PreviewSelectionButton(modifier: Modifier) {
    val navController = LocalNavController.current
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()
    // TODO(b/353659535): Use Selection.size api when available
    val currentSelection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val previewItemCount = currentSelection.size
    val configuration = LocalPhotopickerConfiguration.current
    if (currentSelection.isNotEmpty()) {
        TextButton(
            onClick = {
                scope.launch {
                    logPreviewSelectionButtonClicked(configuration, previewItemCount, events)
                }
                navController.navigateToPreviewSelection()
            },
            modifier = modifier,
        ) {
            Text(
                stringResource(R.string.photopicker_preview_button_label),
                color =
                    CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                        /* fallback */ MaterialTheme.colorScheme.primary
                    ),
            )
        }
    }
}

/**
 * Dispatches all the relevant logging events for the picker's preview mode when the Preview button
 * is clicked
 */
private suspend fun logPreviewSelectionButtonClicked(
    configuration: PhotopickerConfiguration,
    previewItemCount: Int,
    events: Events,
) {
    // Log preview item details
    events.dispatch(
        Event.LogPhotopickerPreviewInfo(
            FeatureToken.PREVIEW.token,
            configuration.sessionId,
            Telemetry.PreviewModeEntry.VIEW_SELECTED,
            previewItemCount,
            Telemetry.MediaType.UNSET_MEDIA_TYPE,
            Telemetry.VideoPlayBackInteractions.UNSET_VIDEO_PLAYBACK_INTERACTION,
        )
    )

    // Log preview related UI events including clicking the 'preview' button
    events.dispatch(
        Event.LogPhotopickerUIEvent(
            FeatureToken.PREVIEW.token,
            configuration.sessionId,
            configuration.callingPackageUid ?: -1,
            Telemetry.UiEvent.ENTER_PICKER_PREVIEW_MODE,
        )
    )

    events.dispatch(
        Event.LogPhotopickerUIEvent(
            FeatureToken.PREVIEW.token,
            configuration.sessionId,
            configuration.callingPackageUid ?: -1,
            Telemetry.UiEvent.PICKER_CLICK_VIEW_SELECTED,
        )
    )
}
