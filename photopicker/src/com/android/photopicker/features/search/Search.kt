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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration

private val MEASUREMENT_SEARCH_BAR_HEIGHT = 56.dp
private val MEASUREMENT_SEARCH_BAR_PADDING =
    PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)

/** A composable function that displays a SearchBar. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Search(modifier: Modifier = Modifier) {
    val searchTerm = remember { mutableStateOf("") }
    val focused = remember { mutableStateOf(false) }

    SearchBar(
        inputField = {
            SearchInput(
                searchQuery = searchTerm.value,
                focused = focused.value,
                onSearchQueryChanged = { searchTerm.value = it },
                onFocused = { focused.value = it },
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
        content = {},
    )
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
    modifier: Modifier,
) {
    SearchBarDefaults.InputField(
        query = searchQuery,
        placeholder = { SearchBarPlaceHolder(focused) },
        colors =
            TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        onQueryChange = onSearchQueryChanged,
        onSearch = { onFocused(true) },
        expanded = focused,
        onExpandedChange = onFocused,
        leadingIcon = { SearchBarIcon(focused, onFocused, onSearchQueryChanged) },
        modifier = modifier.height(MEASUREMENT_SEARCH_BAR_HEIGHT),
    )
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
 */
@Composable
private fun SearchBarIcon(
    focused: Boolean,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
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
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.photopicker_search_placeholder_text),
        )
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
