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

import android.net.Uri
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF
import android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_MOTION_PHOTO
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.animations.emphasizedAccelerateFloat
import com.android.photopicker.core.animations.springDefaultEffectFloat
import com.android.photopicker.core.components.MediaGridItem.Companion.defaultBuildContentType
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.data.model.Group.Album
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.circleBackground
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.extensions.transferGridTouchesToHostInEmbedded
import com.android.photopicker.util.LocalLocalizationHelper
import com.android.photopicker.util.getMediaContentDescription
import java.text.DateFormat
import java.text.NumberFormat

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW: Int = 3

/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED: Int = 4

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

/** The font size of the selected position number */
private val MEASUREMENT_SELECTED_POSITION_FONT_SIZE = 14.sp

/** The offset to apply to the selected icon to shift it over the corner of the image */
private val MEASUREMENT_SELECTED_ICON_OFFSET = 8.dp

/** Border width for the selected icon */
private val MEASUREMENT_SELECTED_ICON_BORDER = 2.dp

/** The radius to use for the corners of grid cells that are selected */
private val MEASUREMENT_SELECTED_CORNER_RADIUS = 16.dp

/** The padding to use around the default separator's content. */
private val MEASUREMENT_SEPARATOR_PADDING = 16.dp

/** The radius to use for the corners of grid cells that are selected */
val MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS = 16.dp

/** The size for the icon used inside the default album thumbnails */
val MEASUREMENT_DEFAULT_ALBUM_THUMBNAIL_ICON_SIZE = 56.dp

/** The padding for the icon for the default album thumbnails */
val MEASUREMENT_DEFAULT_ALBUM_THUMBNAIL_ICON_PADDING = 16.dp

/** Additional padding between album items */
val MEASUREMENT_DEFAULT_ALBUM_BOTTOM_PADDING = 16.dp

/** Size of the spacer between the album icon and the album display label */
val MEASUREMENT_DEFAULT_ALBUM_LABEL_SPACER_SIZE = 12.dp

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
    columns: GridCells = GridCells.Fixed(getCellsPerRow(isExpandedScreen)),
    gridCellPadding: Dp = MEASUREMENT_CELL_SPACING,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(bottom = MEASUREMENT_DEFAULT_CONTENT_PADDING),
    userScrollEnabled: Boolean = true,
    spanFactory: (item: MediaGridItem?, isExpandedScreen: Boolean) -> GridItemSpan =
        ::defaultBuildSpan,
    contentTypeFactory: (item: MediaGridItem?) -> Int = ::defaultBuildContentType,
    contentItemFactory:
        @Composable
        (
            item: MediaGridItem,
            isSelected: Boolean,
            onClick: ((item: MediaGridItem) -> Unit)?,
            onLongPress: ((item: MediaGridItem) -> Unit)?,
            dateFormat: DateFormat,
        ) -> Unit =
        { item, isSelected, onClick, onLongPress, dateFormat ->
            when (item) {
                is MediaGridItem.MediaItem ->
                    defaultBuildMediaItem(
                        item = item,
                        isSelected = isSelected,
                        selectedPosition = selection.indexOf(item.media),
                        onClick = onClick,
                        onLongPress = onLongPress,
                        dateFormat = dateFormat,
                    )

                is MediaGridItem.AlbumItem -> defaultBuildAlbumItem(item, onClick)
                else -> {}
            }
        },
    contentSeparatorFactory: @Composable (item: MediaGridItem.SeparatorItem) -> Unit = { item ->
        defaultBuildSeparator(item)
    },
    bannerContent: (@Composable () -> Unit)? = null,
) {
    // To know whether the request in coming from Embedded or PhotoPicker
    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val host = LocalEmbeddedState.current?.host
    val dateFormat =
        LocalLocalizationHelper.current.getLocalizedDateTimeFormatter(
            dateStyle = DateFormat.MEDIUM,
            timeStyle = DateFormat.SHORT,
        )

    /**
     * Bottom sheet current state in runtime Embedded Photopicker. This assignment is necessary to
     * get the regular updates of bottom sheet current state inside [LazyVerticalGrid]
     */
    val isExpanded = rememberUpdatedState(LocalEmbeddedState.current?.isExpanded ?: false)
    LazyVerticalGrid(
        columns = columns,
        modifier =
            if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                modifier.transferGridTouchesToHostInEmbedded(state, isExpanded, host)
            } else {
                modifier
            },
        state = state,
        contentPadding = contentPadding,
        userScrollEnabled = userScrollEnabled,
        horizontalArrangement = Arrangement.spacedBy(gridCellPadding),
        verticalArrangement = Arrangement.spacedBy(gridCellPadding),
    ) {

        // If banner content was passed add it to the grid as a full span item
        // so that it appears inside the scroll container.
        bannerContent?.let {
            item(
                span = {
                    if (isExpandedScreen) GridItemSpan(CELLS_PER_ROW_EXPANDED)
                    else GridItemSpan(CELLS_PER_ROW)
                }
            ) {
                it()
            }
        }

        // Add the media items from the LazyPagingItems
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
                            dateFormat,
                        )

                    is MediaGridItem.AlbumItem ->
                        contentItemFactory(
                            item,
                            /* isSelected */ false,
                            onItemClick,
                            onItemLongPress,
                            dateFormat,
                        )

                    is MediaGridItem.SeparatorItem -> contentSeparatorFactory(item)
                }
            }
        }
    }
    if (isEmbedded) {
        // Remember the previous value of isExpanded
        val wasPreviouslyExpanded = remember { mutableStateOf(!isExpanded.value) }

        // Any time isExpanded changes, check if grid animation is required.
        LaunchedEffect(isExpanded.value) {
            val isCollapsed = !isExpanded.value

            // Only animate if going from Expanded -> Collapsed
            if (wasPreviouslyExpanded.value && isCollapsed) {
                if (state.firstVisibleItemScrollOffset > 0) {
                    state.animateScrollBy(
                        value = -state.firstVisibleItemScrollOffset.toFloat(),
                        animationSpec = tween(durationMillis = 500),
                    )
                }
            }
            // Update the previous state as the current state
            wasPreviouslyExpanded.value = isExpanded.value
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
 * Return the number of cells in a row based on whether the current configuration has expanded
 * screen or not.
 */
public fun getCellsPerRow(isExpandedScreen: Boolean): Int {
    return if (isExpandedScreen) CELLS_PER_ROW_EXPANDED else CELLS_PER_ROW
}

/**
 * Default [MediaGridItem.MediaItem] builder that loads media into a square (1:1) aspect ratio
 * GridCell, and provides animations and an icon for the selected state.
 */
@Composable
private fun defaultBuildMediaItem(
    item: MediaGridItem,
    isSelected: Boolean,
    selectedPosition: Int,
    onClick: ((item: MediaGridItem) -> Unit)?,
    onLongPress: ((item: MediaGridItem) -> Unit)?,
    dateFormat: DateFormat,
) {
    when (item) {
        is MediaGridItem.MediaItem -> {
            // Padding is animated based on the selected state of the item. When the item is
            // selected, it should shrink in the cell and provide a surface background.

            val shouldIndicateSelected =
                isSelected && LocalPhotopickerConfiguration.current.selectionLimit > 1

            val padding by
                animateDpAsState(
                    if (shouldIndicateSelected) {
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

            val mediaDescription = getMediaContentDescription(item.media, dateFormat)

            // Wrap the entire Grid cell in a box for handling aspectRatio and clicks.
            Box(
                // Apply semantics for the click handlers
                Modifier.semantics(mergeDescendants = true) {
                        contentDescription = mediaDescription

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
                            onLongPress = { onLongPress?.invoke(item) },
                        )
                    }
            ) {
                // A background surface that is shown behind selected images.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    // Container for the image and it's mimetype icon
                    Box(
                        // Switch which modifier is getting applied based on if the item is
                        // selected or not.
                        modifier = if (shouldIndicateSelected) selectedModifier else baseModifier
                    ) {

                        // Load the media item through the Glide entrypoint.
                        loadMedia(
                            media = item.media,
                            resolution = Resolution.THUMBNAIL,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Scrim to separate the text and mimetypes from the image behind them.
                        Surface(
                            color = Color.Black.copy(alpha = 0.2f),
                            contentColor = Color.White,
                        ) {
                            MimeTypeOverlay(item)
                        } // Scrim
                    }

                    // This is outside the box that wraps the image so it doesn't get clipped
                    // by the shape. Internally, it positions itself with similar padding.
                    SelectedIconOverlay(isSelected, selectedPosition)
                } // Surface
            } // Grid cell box
        } // when MediaItem branch
        else -> {}
    } // when
}

/**
 * Generates a mimetype overlay for media items, if the mimetype is supported.
 *
 * @param item The MediaGridItem.MediaItem for the current grid cell.
 */
@Composable
private fun MimeTypeOverlay(item: MediaGridItem.MediaItem) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            Modifier.align(AbsoluteAlignment.TopRight)
                .padding(MEASUREMENT_MIMETYPE_ICON_EDGE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.media is Media.Video) {
                Text(
                    text = DateUtils.formatElapsedTime(item.media.duration / 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clearAndSetSemantics {},
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
                        Icon(Icons.Filled.MotionPhotosOn, contentDescription = null)
                    }

                    else -> {}
                }
            }
        }
    }
}

/**
 * Generates a Icon that will show and hide itself based on the [isSelected] property.
 *
 * @param isSelected if the current item is currently selected by the user.
 * @param selectedIndex the index of the item in the selection set.
 */
@Composable
private fun SelectedIconOverlay(isSelected: Boolean, selectedIndex: Int) {

    Box(modifier = Modifier.fillMaxSize().padding(MEASUREMENT_SELECTED_INTERNAL_PADDING)) {
        // Animate the visibility of the selected icon based on the [isSelected]
        // attribute.
        AnimatedVisibility(
            modifier =
                Modifier.align(AbsoluteAlignment.TopLeft)
                    // This offset moves the icon in each axis from the corner
                    // origin. (So that the center of the icon is closer to the
                    // actual visual corner). The offset is applied to the animation
                    // wrapper so the animation origin moves with the icon itself.
                    .offset(
                        x = -MEASUREMENT_SELECTED_ICON_OFFSET,
                        y = -MEASUREMENT_SELECTED_ICON_OFFSET,
                    ),
            visible = isSelected,
            enter = scaleIn(animationSpec = springDefaultEffectFloat),
            exit = scaleOut(animationSpec = emphasizedAccelerateFloat),
        ) {
            val configuration = LocalPhotopickerConfiguration.current
            val shouldIndicateSelected = configuration.selectionLimit > 1
            if (shouldIndicateSelected) {
                when (configuration.pickImagesInOrder) {
                    true -> {
                        val numberFormatter = remember { NumberFormat.getInstance() }
                        var rememberedIndex by remember { mutableStateOf(selectedIndex) }

                        LaunchedEffect(isSelected, selectedIndex) {
                            if (isSelected) {
                                rememberedIndex = selectedIndex
                            }
                        }
                        Text(
                            // Since this is a 0-based index, increment it by 1 for displaying
                            // to the user.
                            text = numberFormatter.format(rememberedIndex + 1),
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier.circleBackground(
                                    color =
                                        CustomAccentColorScheme.current
                                            .getAccentColorIfDefinedOrElse(
                                                /* fallback */ MaterialTheme.colorScheme.primary
                                            ),
                                    padding = 1.dp,
                                    borderColor = MaterialTheme.colorScheme.surfaceVariant,
                                    borderWidth = MEASUREMENT_SELECTED_ICON_BORDER,
                                ),
                            style =
                                LocalTextStyle.current.copy(
                                    fontSize = MEASUREMENT_SELECTED_POSITION_FONT_SIZE
                                ),
                            color =
                                CustomAccentColorScheme.current
                                    .getTextColorForAccentComponentsIfDefinedOrElse(
                                        MaterialTheme.colorScheme.onPrimary
                                    ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    false ->
                        Icon(
                            ImageVector.vectorResource(R.drawable.photopicker_selected_media),
                            modifier =
                                Modifier
                                    // Background is necessary because the icon has negative
                                    // space.
                                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
                                    // Border color should match the surface that is behind
                                    // the image.
                                    .border(
                                        MEASUREMENT_SELECTED_ICON_BORDER,
                                        MaterialTheme.colorScheme.surfaceContainerHighest,
                                        CircleShape,
                                    ),
                            contentDescription = stringResource(R.string.photopicker_item_selected),
                            tint =
                                CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                                    /* fallback */ MaterialTheme.colorScheme.primary
                                ),
                        )
                }
            }
        } // Image + Icon Container
    }
}

/**
 * Default [MediaGridItem.AlbumItem] builder that loads album into a square (1:1) aspect ratio
 * GridCell, and provides a text title for it just below the thumbnail.
 */
@Composable
private fun defaultBuildAlbumItem(item: MediaGridItem, onClick: ((item: MediaGridItem) -> Unit)?) {
    when (item) {
        is MediaGridItem.AlbumItem -> {

            Column(
                // Apply semantics for the click handlers
                Modifier.semantics(mergeDescendants = true) {
                        onClick(
                            action = {
                                onClick?.invoke(item)
                                /* eventHandled= */ true
                            }
                        )
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClick?.invoke(item) }) }
                    .padding(bottom = MEASUREMENT_DEFAULT_ALBUM_BOTTOM_PADDING)
            ) {
                // In the current implementation for AlbumsGrid, favourites and videos are
                // 2 mandatory albums and are shown even when they contain no data. For this
                // case they have special thumbnails associated with them.
                with(item.album) {
                    val modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS))
                            .aspectRatio(1f)
                    when {
                        id.equals(ALBUM_ID_FAVORITES) && coverUri.equals(Uri.EMPTY) -> {
                            DefaultAlbumIcon(/* icon */ Icons.Outlined.StarOutline, modifier)
                        }

                        id.equals(ALBUM_ID_VIDEOS) && coverUri.equals(Uri.EMPTY) -> {
                            DefaultAlbumIcon(/* icon */ Icons.Outlined.Videocam, modifier)
                        }

                        id.equals(ALBUM_ID_CAMERA) && coverUri.equals(Uri.EMPTY) -> {
                            DefaultAlbumIcon(/* icon */ Icons.Outlined.PhotoCamera, modifier)
                        }
                        // Load the media item through the Glide entrypoint.
                        else -> {
                            loadMedia(
                                media = item.album,
                                resolution = Resolution.THUMBNAIL,
                                // Modifier for album thumbnail
                                modifier = modifier,
                            )
                        }
                    }
                }

                Spacer(Modifier.size(MEASUREMENT_DEFAULT_ALBUM_LABEL_SPACER_SIZE))
                // Album title shown below the album thumbnail.
                Text(
                    text = item.album.displayName,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } // Album cell column
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
        Text(item.label, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Creates an image which can be used as a default thumbnail, this image is creates using the
 * provided [ImageVector].
 *
 * These image vectors a part of androidx androidx.compose.material.icons library.
 */
@Composable
private fun DefaultAlbumIcon(icon: ImageVector, modifier: Modifier) {

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS),
    ) {
        Box(
            // Modifier for album thumbnail
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // Or provide a suitable content description
                modifier =
                    Modifier
                        // Equivalent to layout_width and layout_height
                        .size(MEASUREMENT_DEFAULT_ALBUM_THUMBNAIL_ICON_SIZE)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainer, // Background color
                            shape = CircleShape, // Circular background
                        )
                        // Padding inside the circle
                        .padding(MEASUREMENT_DEFAULT_ALBUM_THUMBNAIL_ICON_PADDING)
                        .clip(CircleShape), // Clip the image to a circle
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
