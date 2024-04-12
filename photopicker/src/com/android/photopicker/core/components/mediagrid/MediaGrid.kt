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

package com.android.photopicker.core.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItem

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW = 3
/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED = 4

/** The default (if not overridden) amount of content padding inside the grid */
private val MEASUREMENT_DEFAULT_CONTENT_PADDING = 0.dp

/** The amount of padding to use around each cell in the grid. */
private val MEASUREMENT_CELL_SPACING = 1.dp

/** The size of the "push in" when an item in the grid is selected */
private val MEASUREMENT_SELECTED_INTERNAL_PADDING = 12.dp

/** The size of the "push in" when an item in the grid is not selected */
private val MEASUREMENT_NOT_SELECTED_INTERNAL_PADDING = 0.dp

/** The offset to apply to the selected icon to shift it over the corner of the image */
private val MEASUREMENT_SELECTED_ICON_OFFSET = 4.dp

/** Border width for the selected icon */
private val MEASUREMENT_SELECTED_ICON_BORDER = 2.dp

/** The radius to use for the corners of grid cells that are selected */
private val MEASUREMENT_SELECTED_CORDER_RADIUS = 16.dp

/** The padding to use around the default separator's content. */
private val MEASUREMENT_SEPARATOR_PADDING = 16.dp

/**
 * Composable for creating a MediaGrid from a [PagingData] source of data that implements [Media]
 *
 * The mediaGrid uses a custom wrapper class to distinguish between individual grid cells and
 * horizontal separators. In order to convert a [Media] into a [MediaGridItem] use the flow
 * extension method [toMediaGridItem]. Additionally, to insert separators, the [Flow] extension
 * method [insertMonthSeparators] will separate list items by month.
 *
 * @param items The LazyPagingItems that have been collected. See [collectAsLazyPagingItems] to
 *   transform a PagingData flow into the correct format for this composable.
 * @param isExpandedScreen Whether the device is using an expanded screen size. This impacts the
 *   default number of cells shown per row. Has no effect if columns parameter is set directly.
 * @param columns An override to use a provided number of cells per row.
 * @param modifier A modifier to apply to the top level [LazyVerticalGrid] this composable creates.
 * @param state the [LazyGridState] to use with this Lazy resource.
 * @param contentPadding [ContentPadding] values that will be applies to the [LazyVerticalGrid].
 * @param userScrollEnabled Whether the user is able to scroll the grid
 * @param spanFactory Optional custom implementation for how mediaGrid will decide span sizes.
 * @param contentTypeFactory Optional custom implementation for how mediaGrid will decide
 *   contentType for its items
 * @param contentItemFactory Optional custom implementation for composing individual grid items.
 * @param contentSeparatorFactory Optional custom implementation for composing individual grid
 *   separators.
 */
@Composable
fun mediaGrid(
    items: LazyPagingItems<MediaGridItem>,
    selection: Set<Media>,
    onItemClick: (item: Media) -> Unit,
    onItemLongPress: (item: Media) -> Unit = {},
    isExpandedScreen: Boolean = false,
    columns: GridCells =
        if (isExpandedScreen) GridCells.Fixed(CELLS_PER_ROW_EXPANDED)
        else GridCells.Fixed(CELLS_PER_ROW),
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(MEASUREMENT_DEFAULT_CONTENT_PADDING),
    userScrollEnabled: Boolean = true,
    spanFactory: (item: MediaGridItem?, isExpandedScreen: Boolean) -> GridItemSpan =
        ::defaultBuildSpan,
    contentTypeFactory: (item: MediaGridItem?) -> Int = ::defaultBuildContentType,
    contentItemFactory:
        @Composable (
            item: MediaGridItem.MediaItem,
            isSelected: Boolean,
            onClick: ((item: Media) -> Unit)?,
            onLongPress: ((item: Media) -> Unit)?,
        ) -> Unit =
        { item, isSelected, onClick, onLongPress,
            ->
            defaultBuildItem(item.media, isSelected, onClick, onLongPress)
        },
    contentSeparatorFactory: @Composable (item: MediaGridItem.SeparatorItem) -> Unit = { item ->
        defaultBuildSeparator(item)
    },
) {

    LazyVerticalGrid(
        columns = columns,
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        userScrollEnabled = userScrollEnabled,
        horizontalArrangement = Arrangement.spacedBy(MEASUREMENT_CELL_SPACING),
        verticalArrangement = Arrangement.spacedBy(MEASUREMENT_CELL_SPACING),
    ) {
        items(
            count = items.itemCount,
            key = { index -> keyFactory(items.peek(index), index) },
            span = { index -> spanFactory(items.peek(index), isExpandedScreen) },
            contentType = { index -> contentTypeFactory(items.peek(index)) },
        ) { index ->
            val item: MediaGridItem? = items.get(index)
            item?.let {
                when (item) {
                    is MediaGridItem.MediaItem ->
                        contentItemFactory(
                            item,
                            selection.contains(item.media),
                            onItemClick,
                            onItemLongPress,
                        )
                    is MediaGridItem.SeparatorItem -> contentSeparatorFactory(item)
                }
            }
        }
    }
}

/**
 * Assembles a key for a [MediaGridItem]. This key must be always be stable and unique in the grid.
 *
 * @return a Unique, stable key that represents one item in the grid.
 */
private fun keyFactory(item: MediaGridItem?, index: Int): String {
    return when (item) {
        is MediaGridItem.MediaItem -> "${item.media.pickerId}"
        is MediaGridItem.SeparatorItem -> "${item.label}_$index"
        null -> "$index"
    }
}

/**
 * Default builder for generating a contentType signature for a grid item.
 *
 * ContentType is used to signify re-use of containers to increase the efficiency of the Grid
 * loading. Each subtype of MediaGridItem should return a distinct value to ensure optimal re-use.
 *
 * @return The contentType signature of the provided item.
 */
private fun defaultBuildContentType(item: MediaGridItem?): Int {
    return when (item) {
        is MediaGridItem.MediaItem -> 1
        is MediaGridItem.SeparatorItem -> 2
        null -> 0
    }
}

/** Default builder for calculating the [GridItemSpan] of the provided [MediaGridItem]. */
private fun defaultBuildSpan(item: MediaGridItem?, isExpandedScreen: Boolean): GridItemSpan {
    return when (item) {
        is MediaGridItem.MediaItem -> GridItemSpan(1)
        is MediaGridItem.SeparatorItem ->
            if (isExpandedScreen) GridItemSpan(CELLS_PER_ROW_EXPANDED)
            else GridItemSpan(CELLS_PER_ROW)
        null -> GridItemSpan(1)
    }
}

/**
 * Default [MediaGridItem.MediaItem] builder that loads media into a square (1:1) aspect ratio
 * GridCell, and provides animations and an icon for the selected state.
 */
@Composable
private fun defaultBuildItem(
    item: Media,
    isSelected: Boolean,
    onClick: ((item: Media) -> Unit)?,
    onLongPress: ((item: Media) -> Unit)?,
) {

    // Padding is animated based on the selected state of the item. When the item is selected,
    // it should shrink in the cell and provide a surface background.
    val padding by
        animateDpAsState(
            if (isSelected) {
                MEASUREMENT_SELECTED_INTERNAL_PADDING
            } else {
                MEASUREMENT_NOT_SELECTED_INTERNAL_PADDING
            }
        )

    // Modifier for the image itself, which uses the animated padding defined above.
    val baseModifier = Modifier.fillMaxSize().padding(padding)

    // Additionally, selected items get rounded corners, so that is added to the baseModifier
    val selectedModifier = baseModifier.clip(RoundedCornerShape(MEASUREMENT_SELECTED_CORDER_RADIUS))

    // Wrap the entire Grid cell in a box for handling aspectRatio and clicks.
    Box(
        // Apply semantics for the click handlers
        Modifier.semantics(mergeDescendants = true) {
                onClick(
                    action = {
                        onClick?.invoke(item)
                        /* eventHandled= */ true
                    }
                )
                onLongClick(
                    action = {
                        onLongPress?.invoke(item)
                        /* eventHandled= */ true
                    }
                )
            }
            .aspectRatio(1f)
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick?.invoke(item) },
                    onLongPress = { onLongPress?.invoke(item) }
                )
            }
    ) {

        // A background surface that is shown behind selected images.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {

            // Container for the image and selected icon
            Box {

                // Load the media item through the Glide entrypoint.
                loadMedia(
                    media = item,
                    resolution = Resolution.THUMBNAIL,
                    // Switch which modifier is getting applied based on if the item is selected or
                    // not.
                    modifier = if (isSelected) selectedModifier else baseModifier,
                )

                // Wrap the icon in a full size box with the same internal padding that
                // selected images use to ensure it is positioned correctly, relative to the image
                // it is drawing on top of.
                Box(
                    modifier = Modifier.fillMaxSize().padding(MEASUREMENT_SELECTED_INTERNAL_PADDING)
                ) {

                    // Animate the visibility of the selected icon based on the [isSelected]
                    // attribute.
                    AnimatedVisibility(
                        modifier =
                            // This offset moves the icon in each axis from the corner
                            // origin. (So that the center of the icon is closer to the
                            // actual visual corner). The offset is applied to the animation wrapper
                            // so the animation origin moves with the icon itself.
                            Modifier.offset(
                                x = -MEASUREMENT_SELECTED_ICON_OFFSET,
                                y = -MEASUREMENT_SELECTED_ICON_OFFSET,
                            ),
                        visible = isSelected,
                        enter = scaleIn(),
                        // No exit transition so it disappears on the next frame.
                        exit = ExitTransition.None,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            modifier =
                                Modifier
                                    // Background is necessary because the icon has negative space.
                                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                                    // Border color should match the surface that is behind the
                                    // image.
                                    .border(
                                        MEASUREMENT_SELECTED_ICON_BORDER,
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    ),
                            contentDescription = stringResource(R.string.photopicker_item_selected),
                            // For now, this is a lovely shade of dark green to match the mocks.
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } // Icon Container
            } // Image + Icon Container
        } // Surface
    } // Box for GridCell
}

/**
 * Default [MediaGridItem.SeparatorItem] that creates a full width divider using the provided text
 * label.
 */
@Composable
private fun defaultBuildSeparator(item: MediaGridItem.SeparatorItem) {
    Box(Modifier.padding(MEASUREMENT_SEPARATOR_PADDING).semantics(mergeDescendants = true) {}) {
        Text(item.label)
    }
}
