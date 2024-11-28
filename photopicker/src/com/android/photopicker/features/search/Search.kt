/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.search

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.search.model.SearchEnabledState
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import com.android.photopicker.util.rememberBitmapFromUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

private val MEASUREMENT_SEARCH_BAR_HEIGHT = 56.dp
private val MEASUREMENT_SEARCH_BAR_PADDING =
    PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)

private val FETCH_SUGGESTION_DEBOUNCE_DELAY = 300L // in milliseconds

private val SUGGESTION_TITLE_PADDING =
    PaddingValues(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 12.dp)
private val MEASUREMENT_LARGE_PADDING = 16.dp
private val MEASUREMENT_ITEM_GAP_PADDING = 12.dp
private val MEASUREMENT_MEDIUM_PADDING = 8.dp
private val MEASUREMENT_SMALL_PADDING = 4.dp
private val MEASUREMENT_EXTRA_SMALL_PADDING = 2.dp

private val MEASUREMENT_SUGGESTION_ITEM_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)

private val CARD_CORNER_RADIUS_LARGE = 28.dp
private val CARD_CORNER_RADIUS_SMALL = 4.dp
private val TOP_SUGGESTION_CARD_SHAPE =
    RoundedCornerShape(
        topStart = CARD_CORNER_RADIUS_LARGE,
        topEnd = CARD_CORNER_RADIUS_LARGE,
        bottomStart = CARD_CORNER_RADIUS_SMALL,
        bottomEnd = CARD_CORNER_RADIUS_SMALL,
    )
private val BOTTOM_SUGGESTION_CARD_SHAPE =
    RoundedCornerShape(
        topStart = CARD_CORNER_RADIUS_SMALL,
        topEnd = CARD_CORNER_RADIUS_SMALL,
        bottomStart = CARD_CORNER_RADIUS_LARGE,
        bottomEnd = CARD_CORNER_RADIUS_LARGE,
    )
private val MIDDLE_SUGGESTION_CARD_SHAPE = RoundedCornerShape(CARD_CORNER_RADIUS_SMALL)
private val SINGLE_SUGGESTION_CARD_SHAPE = RoundedCornerShape(CARD_CORNER_RADIUS_LARGE)

private val MEASUREMENT_FACE_SUGGESTION_ICON = 48.dp
private val MEASUREMENT_FACE_RESULT_ICON = 32.dp
private val MEASUREMENT_OTHER_ICON = 40.dp

/** A composable function that displays a SearchBar. */
@Composable
fun Search(
    modifier: Modifier = Modifier,
    params: LocationParams,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    val searchEnabled by viewModel.searchEnabled.collectAsStateWithLifecycle()
    when {
        searchEnabled == SearchEnabledState.ENABLED -> {
            SearchBarEnabled(params, viewModel, modifier)
        }
        else -> {
            SearchBarWithTooltip(modifier)
        }
    }
}

/**
 * This composable displays an enabled search bar that allows users to enter search queries.
 *
 * @param params A [LocationParams] relevant to the search functionality.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 * @param modifier The modifier to be applied to the composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchBarEnabled(params: LocationParams, viewModel: SearchViewModel, modifier: Modifier) {
    val focused = rememberSaveable { mutableStateOf(false) }
    val searchTerm = rememberSaveable { mutableStateOf("") }
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val suggestionLists by viewModel.suggestionLists.collectAsStateWithLifecycle()
    SearchBar(
        inputField = {
            SearchInputContent(
                viewModel = viewModel,
                focused = focused.value,
                searchTerm = searchTerm.value,
                onFocused = {
                    if (it) {
                        val clickAction = params as? LocationParams.WithClickAction
                        clickAction?.onClick()
                    }
                    focused.value = it
                },
                onSearchQueryChanged = {
                    searchTerm.value = it
                    viewModel.clearSearch()
                },
                searchState = searchState,
                modifier,
            )
        },
        expanded = focused.value,
        onExpandedChange = { focused.value = it },
        colors =
            SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            if (focused.value) {
                Modifier.fillMaxWidth()
            } else {
                modifier.padding(MEASUREMENT_SEARCH_BAR_PADDING)
            },
        content = {
            when (searchState) {
                is SearchState.Active -> {
                    val searchResults =
                        remember(searchState) {
                            try {
                                viewModel.getSearchResults()
                            } catch (e: IllegalStateException) {
                                // If search state is inactive fetching search results would throw
                                // an IllegalStateException but this is not expected to ever happen
                                // as search results will be called only for active search states.
                                Log.e(
                                    SearchFeature.TAG,
                                    " Cannot show search results in inactive search state",
                                    e,
                                )
                                flow { PagingData.from(emptyList()) }
                            }
                        }
                    ResultMediaGrid(searchResults)
                }

                SearchState.Inactive -> {
                    if (suggestionLists.totalSuggestions > 0) {
                        val focusManager = LocalFocusManager.current
                        ShowSuggestions(
                            suggestionLists = suggestionLists,
                            isZeroSearchState = searchTerm.value.isEmpty(),
                            onSuggestionClick = { suggestion ->
                                focusManager.clearFocus()
                                searchTerm.value = suggestion.displayText ?: ""
                                viewModel.performSearch(suggestion = suggestion)
                            },
                            modifier = modifier,
                        )
                    }
                }
            }
        },
    )
    LaunchedEffect(key1 = searchTerm.value) { // Trigger when searchTerm changes
        delay(FETCH_SUGGESTION_DEBOUNCE_DELAY)
        viewModel.fetchSuggestions(searchTerm.value)
    }
}

/**
 * This composable displays a disabled search bar with a tooltip that appears when the user clicks
 * over it.
 *
 * @param modifier The modifier to be applied to the composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchBarWithTooltip(modifier: Modifier) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider =
            remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        return IntOffset(
                            x =
                                anchorBounds.left +
                                    (anchorBounds.width - popupContentSize.width) / 2,
                            y = anchorBounds.bottom - popupContentSize.height,
                        )
                    }
                }
            },
        tooltip = {
            PlainTooltip { Text(text = stringResource(R.string.photopicker_search_disabled_hint)) }
        },
        state = tooltipState,
    ) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = "",
                    enabled = false,
                    placeholder = { SearchBarPlaceHolder(false) },
                    colors = TextFieldDefaults.colors(MaterialTheme.colorScheme.surface),
                    onQueryChange = {},
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    leadingIcon = { SearchBarIcon(false, {}, {}, searchDisabled = true) },
                    modifier =
                        modifier.height(MEASUREMENT_SEARCH_BAR_HEIGHT).clickable {
                            scope.launch { tooltipState.show() }
                        },
                )
            },
            expanded = false,
            onExpandedChange = {},
            colors =
                SearchBarDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    dividerColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            modifier = modifier.padding(MEASUREMENT_SEARCH_BAR_PADDING),
            content = {},
        )
    }
}

/**
 * Renders the appropriate search input field based on the current search state.
 *
 * This composable function determines which type of search input to display depending on whether
 * the user has selected a "FACE" type suggestion or is performing a regular text search.
 *
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 * @param focused A boolean value indicating if the search input field is currently focused.
 * @param searchTerm Current text entered in search input field.
 * @param onFocused A callback function to be invoked when the focus state of the search field
 *   changes.
 * @param onSearchQueryChanged A callback function to be invoked when the search query text changes.
 * @param searchState The current state of the search, determining the input field type.
 * @param modifier A Modifier that can be applied to the SearchInputContent composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchInputContent(
    viewModel: SearchViewModel,
    focused: Boolean,
    searchTerm: String,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchState: SearchState,
    modifier: Modifier,
) {
    when (
        searchState is SearchState.Active.SuggestionSearch &&
            searchState.suggestion.type == SearchSuggestionType.FACE
    ) {
        true -> {
            ShowSearchInputWithCustomIcon(
                searchState.suggestion,
                onFocused,
                onSearchQueryChanged,
                viewModel,
            )
        }
        else -> {
            SearchInput(
                searchQuery = searchTerm,
                focused = focused,
                onSearchQueryChanged = { onSearchQueryChanged(it) },
                onFocused = {
                    onFocused(it)
                    if (it) {
                        viewModel.clearSearch()
                    }
                },
                onSearch = { viewModel.performSearch(query = searchTerm) },
                modifier = modifier,
            )
        }
    }
}

/**
 * A composable function that displays a search input field within a SearchBar.
 *
 * This component provides a text field for entering search queries It also handles focus state and
 * provides callbacks for search query changes and focus changes.
 *
 * @param searchQuery The current text entered in search bar input field.
 * @param focused A boolean value indicating whether the search input field is currently focused.
 * @param onSearchQueryChanged A callback function that is invoked when the search query text
 *   changes.
 *     * This function receives the updated search query as a parameter.
 *
 * @param onFocused A callback function that is invoked when the focus state of the search field
 *   changes.
 *     * This function receives a boolean value indicating the new focus state.
 *
 * @param onSearch A callback function to be invoked when a text is searched.
 * @param modifier A Modifier that can be applied to the SearchInput composable to customize its
 *     * appearance and behavior.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchInput(
    searchQuery: String,
    focused: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onFocused: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    SearchBarDefaults.InputField(
        query = searchQuery,
        placeholder = { SearchBarPlaceHolder(focused) },
        colors =
            TextFieldDefaults.colors(
                unfocusedContainerColor =
                    if (focused) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        onQueryChange = onSearchQueryChanged,
        onSearch = {
            onFocused(true)
            focusManager.clearFocus()
            onSearch()
        },
        expanded = focused,
        onExpandedChange = onFocused,
        leadingIcon = { SearchBarIcon(focused, onFocused, onSearchQueryChanged) },
        modifier = modifier.height(MEASUREMENT_SEARCH_BAR_HEIGHT),
    )
}

/**
 * Composable that shows an Icon and Title if present in Search Results page on selecting Face type
 * from suggestions list.
 */

/**
 * Composable that displays a search input field with a custom icon and text.
 *
 * This composable function renders a search input field in a row that includes:
 * - A leading icon
 * - A custom icon representing a search suggestion.
 * - Text displaying the suggestion's display text.
 *
 * @param suggestion The `SearchSuggestion` object to be displayed.
 * @param onFocused A callback function to be invoked when the focus state of the search field
 *   changes.
 * @param onSearchQueryChanged A callback function to be invoked when the search query text changes.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 */
@Composable
fun ShowSearchInputWithCustomIcon(
    suggestion: SearchSuggestion,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    Row(
        modifier = Modifier.padding(MEASUREMENT_SMALL_PADDING).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBarIcon(
            true,
            onFocused = onFocused,
            onSearchQueryChanged = { onSearchQueryChanged("") },
        )
        val imageBitmap =
            suggestion.iconUri?.let { rememberBitmapFromUri(it, viewModel.backgroundDispatcher) }
        ShowSuggestionIcon(
            suggestion,
            imageBitmap,
            modifier = Modifier.clip(CircleShape).size(MEASUREMENT_FACE_RESULT_ICON),
        )
        Text(
            text = suggestion.displayText ?: "",
            modifier = Modifier.padding(start = MEASUREMENT_MEDIUM_PADDING),
        )
    }
}

/**
 * A composable function that displays the leading icon in a SearchBar. The icon changes based on
 * the focused state of the SearchBar.
 *
 * @param focused A boolean value indicating whether search input field of search bar is currently
 *   focused.
 * @param onFocused A callback function that is invoked when the focus state of the search field
 *   changes.
 *     * This function receives a boolean value indicating the new focus state.
 *
 * @param onSearchQueryChanged A callback function that is invoked when the search query text
 *   changes.
 *     * This function receives the updated search query as a parameter.
 *
 * @param searchDisabled A boolean value indicating whether the search bar is disabled.
 */
@Composable
private fun SearchBarIcon(
    focused: Boolean,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchDisabled: Boolean = false,
) {
    if (focused) {
        IconButton(
            onClick = {
                onFocused(false)
                onSearchQueryChanged("")
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.photopicker_back_option),
            )
        }
    } else {
        val description =
            when (searchDisabled) {
                true -> {
                    stringResource(R.string.photopicker_search_disabled_hint)
                }
                else -> {
                    stringResource(R.string.photopicker_search_placeholder_text)
                }
            }
        Icon(imageVector = Icons.Outlined.Search, contentDescription = description)
    }
}

/**
 * Composable function that displays a placeholder text in search bar.
 *
 * The placeholder text changes depending on whether the search bar is focused or not. When focused,
 * it also considers the allowed MIME types from the `LocalPhotopickerConfiguration` to display a
 * more specific placeholder.
 *
 * @param focused Boolean value indicating whether the search bar is currently focused.
 */
@Composable
private fun SearchBarPlaceHolder(focused: Boolean) {
    val placeholderText =
        when (focused) {
            true -> {
                if (LocalPhotopickerConfiguration.current.hasOnlyVideoMimeTypes()) {
                    stringResource(R.string.photopicker_search_videos_placeholder_text)
                } else {
                    stringResource(R.string.photopicker_search_photos_placeholder_text)
                }
            }
            false -> stringResource(R.string.photopicker_search_placeholder_text)
        }
    Text(text = placeholderText, style = MaterialTheme.typography.bodyLarge)
}

/**
 * This composable displays an empty state screen when there are no results.
 *
 * @param modifier Modifier used to adjust the layout or styling of the composable.
 */
@Composable
fun EmptySearchResult(modifier: Modifier = Modifier) {
    val localConfig = LocalConfiguration.current
    val emptyStatePadding = remember(localConfig) { (localConfig.screenHeightDp * .20).dp }

    EmptyState(
        modifier = modifier.fillMaxWidth().padding(top = emptyStatePadding),
        icon = Icons.Outlined.HideImage,
        title = stringResource(R.string.photopicker_search_result_empty_state_title),
        body = stringResource(R.string.photopicker_search_result_empty_state_body),
    )
}

/**
 * Composable function that shows suggestion in the search view.
 *
 * @param suggestionLists A `SuggestionLists` object containing the search suggestions to display.
 * @param isZeroSearchState A boolean value indicating if the search query is empty.
 * @param modifier A Modifier that can be applied to the suggestions list.
 * @param onSuggestionClick A callback function to be invoked when a suggestion is clicked.
 */
@Composable
private fun ShowSuggestions(
    suggestionLists: SuggestionLists,
    isZeroSearchState: Boolean,
    modifier: Modifier,
    onSuggestionClick: (SearchSuggestion) -> Unit,
) {
    val historySuggestions = suggestionLists.history
    val faceSuggestions = suggestionLists.face
    val otherSuggestions = suggestionLists.other
    Box(modifier = modifier.padding(MEASUREMENT_LARGE_PADDING)) {
        LazyColumn {
            item { Spacer(modifier = Modifier.height(MEASUREMENT_MEDIUM_PADDING)) }
            items(historySuggestions.take(SearchViewModel.HISTORY_SUGGESTION_MAX_LIMIT)) {
                suggestion ->
                val size =
                    minOf(historySuggestions.size, SearchViewModel.HISTORY_SUGGESTION_MAX_LIMIT)
                ShowSuggestionCard(
                    suggestion,
                    historySuggestions.indexOf(suggestion),
                    size,
                    faceSuggestions.size,
                    otherSuggestions.size,
                    onSuggestionClick,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.photopicker_search_suggestions_text),
                    modifier = Modifier.padding(SUGGESTION_TITLE_PADDING),
                )
            }
            if (faceSuggestions.size > 0) {
                item {
                    ShowFaceSuggestions(faceSuggestions, onSuggestionClick, otherSuggestions.size)
                }
            }
            items(otherSuggestions.take(SearchViewModel.ALL_SUGGESTION_MAX_LIMIT)) { suggestion ->
                val size = minOf(otherSuggestions.size, SearchViewModel.ALL_SUGGESTION_MAX_LIMIT)
                ShowSuggestionCard(
                    suggestion,
                    otherSuggestions.indexOf(suggestion),
                    size,
                    faceSuggestions.size,
                    otherSuggestions.size,
                    onSuggestionClick,
                    isZeroSearchState,
                )
            }
        }
    }
}

/**
 * Composable that displays a suggestion card within a search suggestion list.
 *
 * @param suggestion The search suggestion data to display in the card.
 * @param index The index of this suggestion within the list of suggestions.
 * @param size The total number of suggestions in the list.
 * @param faceTypeCount The number of suggestions of type FACE in the list.
 * @param otherTypeCount The number of suggestions of other type in the list.
 * @param onSuggestionClick Callback function to be invoked when the suggestion card is clicked. It
 *   receives the clicked [SearchSuggestion] as a parameter.
 * @param isZeroSearchState A boolean flag indicating whether the search is in a "zero state" (e.g.,
 *   no search term entered yet). Defaults to `false`.
 */
@Composable
private fun ShowSuggestionCard(
    suggestion: SearchSuggestion,
    index: Int,
    size: Int,
    faceTypeCount: Int,
    otherTypeCount: Int,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    isZeroSearchState: Boolean = false,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(MEASUREMENT_EXTRA_SMALL_PADDING)
                .clickable(onClick = { onSuggestionClick(suggestion) }),
        shape =
            getCardShape(
                index,
                size,
                suggestion.type,
                faceTypeCount,
                otherTypeCount,
                isZeroSearchState,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        SuggestionItem(suggestion)
    }
}

/**
 * Composable that displays the actual suggestion item within a suggestion card
 *
 * @param suggestion The search suggestion item to display.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 */
@Composable
fun SuggestionItem(suggestion: SearchSuggestion, viewModel: SearchViewModel = obtainViewModel()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(MEASUREMENT_SUGGESTION_ITEM_PADDING),
    ) {
        Box(
            modifier =
                Modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(6.dp)
        ) {
            Icon(
                imageVector = getImageVector(suggestion.type),
                contentDescription = suggestion.displayText ?: "",
            )
        }
        val text = suggestion.displayText ?: ""
        Text(text = text, modifier = Modifier.padding(start = MEASUREMENT_LARGE_PADDING).weight(1f))
        if (suggestion.type != SearchSuggestionType.FACE && suggestion.iconUri != null) {
            rememberBitmapFromUri(suggestion.iconUri, viewModel.backgroundDispatcher)?.let {
                imageBitmap ->
                ShowSuggestionIcon(
                    suggestion,
                    imageBitmap,
                    modifier = Modifier.size(MEASUREMENT_OTHER_ICON),
                )
            }
        }
    }
}

/**
 * Composable for showing Face suggestion type in the list of search suggestions.
 *
 * @param list The list of `SearchSuggestion` objects of type FACE to be displayed.
 * @param onSuggestionClick A callback function to be invoked when a suggestion is clicked.
 * @param otherTypeCount The number of suggestions of other type in search suggestions list.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 */
@Composable
fun ShowFaceSuggestions(
    list: List<SearchSuggestion>,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    otherTypeCount: Int,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(MEASUREMENT_EXTRA_SMALL_PADDING),
        shape =
            getCardShape(
                0,
                list.size,
                SearchSuggestionType.FACE,
                faceTypeCount = list.size,
                otherTypeCount = otherTypeCount,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(MEASUREMENT_LARGE_PADDING).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MEASUREMENT_ITEM_GAP_PADDING),
        ) {
            list.take(SearchViewModel.FACE_SUGGESTION_MAX_LIMIT).forEach { suggestion ->
                val imageBitmap =
                    suggestion.iconUri?.let {
                        rememberBitmapFromUri(it, viewModel.backgroundDispatcher)
                    }
                ShowSuggestionIcon(
                    suggestion,
                    imageBitmap,
                    modifier =
                        Modifier.size(MEASUREMENT_FACE_SUGGESTION_ICON)
                            .clip(CircleShape)
                            .clickable { onSuggestionClick(suggestion) },
                )
            }
        }
    }
}

/**
 * Composable that displays an icon for a search suggestion
 *
 * Tries to load an image from the `iconUri` provided in the SearchSuggestion` object. If the image
 * loading is successful displays the image as a bitmap else falls back to displaying default vector
 * icon.
 *
 * @param suggestion The `SearchSuggestion` object containing the icon URI and suggestion type.
 * @param imageBitmap Bitmap for the suggestion icon to be shown.
 * @param modifier Modifiers to be applied to the Icon composable.
 */
@Composable
fun ShowSuggestionIcon(
    suggestion: SearchSuggestion,
    imageBitmap: ImageBitmap?,
    modifier: Modifier,
) {
    val imageDescription = suggestion.displayText ?: ""
    when {
        imageBitmap != null -> {
            Icon(bitmap = imageBitmap, contentDescription = imageDescription, modifier = modifier)
        }
        else -> {
            Icon(
                imageVector = getImageVector(suggestion.type),
                contentDescription = imageDescription,
                modifier = modifier,
            )
        }
    }
}

/** Composable for drawing the search results Grid */
@Composable
private fun ResultMediaGrid(
    resultItems: Flow<PagingData<MediaGridItem>>,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    val navController = LocalNavController.current
    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }
    val selectionLimitExceededMessage =
        stringResource(R.string.photopicker_selection_limit_exceeded_snackbar, selectionLimit)
    val items = resultItems.collectAsLazyPagingItems()

    // Collect the selection to notify the mediaGrid of selection changes.
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val state = rememberLazyGridState()
    val isEmptyAndNoMorePages =
        items.itemCount == 0 &&
            items.loadState.source.append is LoadState.NotLoading &&
            items.loadState.source.append.endOfPaginationReached

    when {
        isEmptyAndNoMorePages -> {
            EmptySearchResult()
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                mediaGrid(
                    items = items,
                    isExpandedScreen = isExpandedScreen,
                    selection = selection,
                    onItemClick = { item ->
                        if (item is MediaGridItem.MediaItem) {
                            viewModel.handleGridItemSelection(
                                item = item.media,
                                selectionLimitExceededMessage = selectionLimitExceededMessage,
                            )
                        }
                    },
                    onItemLongPress = { item ->
                        // If the [PreviewFeature] is enabled, launch the preview route.
                        if (isPreviewEnabled) {
                            // TODO Log entry into the photopicker preview mode for search
                            if (item is MediaGridItem.MediaItem) {
                                navController.navigateToPreviewMedia(item.media)
                            }
                        }
                    },
                    state = state,
                )
            }
        }
    }
}

/**
 * Returns Icon based on the suggestion type.
 *
 * @param suggestionType The type of search suggestion
 */
private fun getImageVector(suggestionType: SearchSuggestionType): ImageVector {
    return when (suggestionType) {
        SearchSuggestionType.HISTORY -> {
            Icons.Outlined.History
        }
        SearchSuggestionType.FAVORITES_ALBUM -> {
            Icons.Outlined.StarBorder
        }
        SearchSuggestionType.LOCATION -> {
            Icons.Outlined.LocationOn
        }
        SearchSuggestionType.DATE -> {
            Icons.Filled.Today
        }
        SearchSuggestionType.VIDEOS_ALBUM -> {
            Icons.Outlined.PlayCircle
        }
        SearchSuggestionType.ALBUM -> {
            Icons.Outlined.PhotoAlbum
        }
        SearchSuggestionType.TEXT -> {
            Icons.Outlined.Search
        }
        SearchSuggestionType.SCREENSHOTS_ALBUM -> {
            Icons.Outlined.Smartphone
        }
        else -> {
            Icons.Outlined.Search
        }
    }
}

/**
 * Determines the shape of a card based on its index, size, and suggestion type.
 *
 * This function calculates the appropriate `Shape` for a card in a list, considering:
 * - **index:** The position of the card in the list.
 * - **size:** The total number of cards in the list.
 * - **type:** The type of suggestion the card represents.
 * - **isZeroState:** Indicates whether the search is in a "zero state"
 *
 * @param index The index of the card in the list.
 * @param size The total number of cards in the list.
 * @param type The type of suggestion.
 * @param faceTypeCount The number of suggestions of type FACE in the list.
 * @param otherTypeCount The number of suggestions of other type in the suggestions list.
 * @param isZeroState A boolean flag to indicate whether the search is in a "zero state"
 * @return The calculated `Shape` for the card.
 */
@Composable
private fun getCardShape(
    index: Int,
    size: Int,
    type: SearchSuggestionType,
    faceTypeCount: Int,
    otherTypeCount: Int,
    isZeroState: Boolean = true,
): Shape {
    return when {
        type == SearchSuggestionType.FACE && isZeroState -> {
            if (otherTypeCount > 0) {
                TOP_SUGGESTION_CARD_SHAPE
            } else {
                SINGLE_SUGGESTION_CARD_SHAPE
            }
        }
        type == SearchSuggestionType.HISTORY -> getRoundedCornerShape(index, size)
        else -> {
            if (faceTypeCount == 0) {
                getRoundedCornerShape(index, size)
            } else {
                when (index) {
                    size - 1 -> BOTTOM_SUGGESTION_CARD_SHAPE
                    else -> MIDDLE_SUGGESTION_CARD_SHAPE
                }
            }
        }
    }
}

/**
 * Helper function to calculate the rounded corner shape based on index and size.
 *
 * @param index The position of the item in the list.
 * @param size The total number of items in the list.
 * @return A `Shape` object with the appropriate rounded corners.
 */
private fun getRoundedCornerShape(index: Int, size: Int): Shape {
    return when {
        index == 0 && size == 1 -> SINGLE_SUGGESTION_CARD_SHAPE
        index == 0 -> TOP_SUGGESTION_CARD_SHAPE
        index == size - 1 -> BOTTOM_SUGGESTION_CARD_SHAPE
        else -> MIDDLE_SUGGESTION_CARD_SHAPE
    }
}
