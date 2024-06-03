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

import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.MediaGridItem.Companion.defaultBuildContentType
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.extensions.toMediaGridItemFromMedia

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW = 3

/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED = 4

/** The default (if not overridden) amount of content padding below the grid */
private val MEASUREMENT_DEFAULT_CONTENT_PADDING = 150.dp

/** The amount of padding to use around each cell in the grid. */
private val MEASUREMENT_CELL_SPACING = 1.dp

/** The size of the "push in" when an item in the grid is selected */
private val MEASUREMENT_SELECTED_INTERNAL_PADDING = 12.dp

/** The distance the mimetype icon is away from the edge */
private val MEASUREMENT_MIMETYPE_ICON_EDGE_PADDING = 4.dp

/** The size of the spacer between the duration text and the mimetype icon */
private val MEASUREMENT_DURATION_TEXT_SPACER_SIZE = 2.dp

/** The size of the "push in" when an item in the grid is not selected */
private val MEASUREMENT_NOT_SELECTED_INTERNAL_PADDING = 0.dp

/** The offset to apply to the selected icon to shift it over the corner of the image */
private val MEASUREMENT_SELECTED_ICON_OFFSET = 4.dp

/** Border width for the selected icon */
private val MEASUREMENT_SELECTED_ICON_BORDER = 2.dp

/** The radius to use for the corners of grid cells that are selected */
private val MEASUREMENT_SELECTED_CORNER_RADIUS = 16.dp

/** The padding to use around the default separator's content. */
private val MEASUREMENT_SEPARATOR_PADDING = 16.dp

/** The radius to use for the corners of grid cells that are selected */
private val MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS = 8.dp

/**
 * Composable for creating a MediaItemGrid from a [PagingData] source of data that implements
 * [Media] or [Album]
 *
 * The mediaGrid uses a custom wrapper class to distinguish between individual grid cells and
 * horizontal separators. In order to convert a [Media] into a [MediaGridItem] use the flow
 * extension method [toMediaGridItemFromMedia] and to convert an [Album] into a [MediaGridItem] use
 * the flow extension method [toMediaGridItemFromAlbum]. Additionally, to insert separators, the
 * [Flow] extension method [insertMonthSeparators] will separate list items by month.
 *
 * @param items The LazyPagingItems that have been collected. See [collectAsLazyPagingItems] to
 *   transform a PagingData flow into the correct format for this composable.
 * @param isExpandedScreen Whether the device is using an expanded screen size. This impacts the
 *   default number of cells shown per row. Has no effect if columns parameter is set directly.
 * @param columns number of cells per row.
 * @param gridCellPadding padding for the grid elements from the wall and individual items
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
    onItemClick: (item: MediaGridItem) -> Unit,
    onItemLongPress: (item: MediaGridItem) -> Unit = {},
    isExpandedScreen: Boolean = false,
    columns: GridCells =
        if (isExpandedScreen) GridCells.Fixed(CELLS_PER_ROW_EXPANDED)
        else GridCells.Fixed(CELLS_PER_ROW),
    gridCellPadding: Dp = MEASUREMENT_CELL_SPACING,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues =
        PaddingValues(bottom = MEASUREMENT_DEFAULT_CONTENT_PADDING),
    userScrollEnabled: Boolean = true,
    spanFactory: (item: MediaGridItem?, isExpandedScreen: Boolean) -> GridItemSpan =
        ::defaultBuildSpan,
    contentTypeFactory: (item: MediaGridItem?) -> Int = ::defaultBuildContentType,
    contentItemFactory:
        @Composable (
            item: MediaGridItem,
            isSelected: Boolean,
            onClick: ((item: MediaGridItem) -> Unit)?,
            onLongPress: ((item: MediaGridItem) -> Unit)?,
        ) -> Unit =
        { item, isSelected, onClick, onLongPress,
            ->
            when (item) {
                is MediaGridItem.MediaItem ->
                    defaultBuildMediaItem(item, isSelected, onClick, onLongPress)
                is MediaGridItem.AlbumItem -> defaultBuildAlbumItem(item, onClick)
                else -> {}
            }
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
        horizontalArrangement = Arrangement.spacedBy(gridCellPadding),
        verticalArrangement = Arrangement.spacedBy(gridCellPadding),
    ) {
        items(
            count = items.itemCount,
            key = { index -> MediaGridItem.keyFactory(items.peek(index), index) },
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
                    is MediaGridItem.AlbumItem ->
                        contentItemFactory(
                            item,
                            /* isSelected */ false,
                            onItemClick,
                            onItemLongPress,
                        )
                    is MediaGridItem.SeparatorItem -> contentSeparatorFactory(item)
                }
            }
        }
    }
}

/** Default builder for calculating the [GridItemSpan] of the provided [MediaGridItem]. */
private fun defaultBuildSpan(item: MediaGridItem?, isExpandedScreen: Boolean): GridItemSpan {
    return when (item) {
        is MediaGridItem.MediaItem -> GridItemSpan(1)
        is MediaGridItem.SeparatorItem ->
            if (isExpandedScreen) GridItemSpan(CELLS_PER_ROW_EXPANDED)
            else GridItemSpan(CELLS_PER_ROW)
        is MediaGridItem.AlbumItem -> GridItemSpan(1)
        else -> GridItemSpan(1)
    }
}

/**
 * Default [MediaGridItem.MediaItem] builder that loads media into a square (1:1) aspect ratio
 * GridCell, and provides animations and an icon for the selected state.
 */
@Composable
private fun defaultBuildMediaItem(
    item: MediaGridItem,
    isSelected: Boolean,
    onClick: ((item: MediaGridItem) -> Unit)?,
    onLongPress: ((item: MediaGridItem) -> Unit)?,
) {
    when (item) {
        is MediaGridItem.MediaItem -> {
            // Padding is animated based on the selected state of the item. When the item is
            // selected, it should shrink in the cell and provide a surface background.
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

            // Additionally, selected items get rounded corners, so that is added to the
            // baseModifier
            val selectedModifier =
                baseModifier.clip(RoundedCornerShape(MEASUREMENT_SELECTED_CORNER_RADIUS))

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

                        // Container for the image and it's mimetype icon
                        Box(
                            // Switch which modifier is getting applied based on if the item is
                            // selected or not.
                            modifier = if (isSelected) selectedModifier else baseModifier,
                        ) {

                            // Load the media item through the Glide entrypoint.
                            loadMedia(
                                media = item.media,
                                resolution = Resolution.THUMBNAIL,
                            )
                            // Mimetype indicators
                            Row(
                                Modifier.align(Alignment.TopEnd)
                                    .padding(MEASUREMENT_MIMETYPE_ICON_EDGE_PADDING),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.media is Media.Video) {
                                    Text(
                                        text =
                                            DateUtils.formatElapsedTime(
                                                item.media.duration / 1000L
                                            ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Spacer(Modifier.size(MEASUREMENT_DURATION_TEXT_SPACER_SIZE))
                                    Icon(Icons.Filled.PlayCircle, contentDescription = null)
                                } else {
                                    when (item.media.standardMimeTypeExtension) {
                                        _SPECIAL_FORMAT_GIF -> {
                                            Icon(Icons.Filled.Gif, contentDescription = null)
                                        }
                                        _SPECIAL_FORMAT_MOTION_PHOTO,
                                        _SPECIAL_FORMAT_ANIMATED_WEBP -> {
                                            Icon(
                                                Icons.Filled.MotionPhotosOn,
                                                contentDescription = null
                                            )
                                        }
                                        else -> {}
                                    }
                                }
                            } // Mimetype row
                        } // Image + Mimetype box

                        // Wrap the icon in a full size box with the same internal padding that
                        // selected images use to ensure it is positioned correctly, relative to the
                        // image it is drawing on top of.
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .padding(MEASUREMENT_SELECTED_INTERNAL_PADDING)
                        ) {

                            // Animate the visibility of the selected icon based on the [isSelected]
                            // attribute.
                            AnimatedVisibility(
                                modifier =
                                    // This offset moves the icon in each axis from the corner
                                    // origin. (So that the center of the icon is closer to the
                                    // actual visual corner). The offset is applied to the animation
                                    // wrapper so the animation origin moves with the icon itself.
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
                                            // Background is necessary because the icon has negative
                                            // space.
                                            .background(
                                                MaterialTheme.colorScheme.onPrimary,
                                                CircleShape
                                            )
                                            // Border color should match the surface that is behind
                                            // the
                                            // image.
                                            .border(
                                                MEASUREMENT_SELECTED_ICON_BORDER,
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                CircleShape
                                            ),
                                    contentDescription =
                                        stringResource(R.string.photopicker_item_selected),
                                    // For now, this is a lovely shade of dark green to match
                                    // the mocks.
                                    tint = CustomAccentColorScheme.current
                                        .getAccentColorIfDefinedOrElse(
                                            /* fallback */ MaterialTheme.colorScheme.primary
                                        ),
                                )
                            }
                        } // Icon Container
                    } // Image + Icon Container
                } // Surface
            } // Box for GridCell
        }

        else -> {}
    }
}

/**
 * Default [MediaGridItem.AlbumItem] builder that loads album into a square (1:1) aspect ratio
 * GridCell, and provides a text title for it just below the thumbnail.
 */
@Composable
private fun defaultBuildAlbumItem(
    item: MediaGridItem,
    onClick: ((item: MediaGridItem) -> Unit)?,
) {
    when (item) {
        is MediaGridItem.AlbumItem -> {
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
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClick?.invoke(item) },
                        )
                    }
            ) {
                // A background surface that is shown behind albums grid.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    // Container for albums and their title
                    Column {
                        // Load the media item through the Glide entrypoint.
                        loadMedia(
                            media = item.album,
                            resolution = Resolution.THUMBNAIL,
                            // Modifier for album thumbnail
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(
                                        RoundedCornerShape(
                                            MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS
                                        )
                                    )
                                    .aspectRatio(1f),
                        )
                        // Album title shown below the album thumbnail.
                        Box {
                            Text(
                                text = item.album.displayName,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                        }
                    } // Album Container
                } // Album cell surface
            } // Box for the grid cell
        }
        else -> {}
    }
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
