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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
                modifier
            )
        },
        expanded = focused.value,
        onExpandedChange = { focused.value = it },
        colors =
            SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchInput(
    searchQuery: String,
    focused: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier
) {
    SearchBarDefaults.InputField(
        query = searchQuery,
        placeholder = {
            Text(
                text = stringResource(R.string.photopicker_search_placeholder_text),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        colors =
            TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
        onQueryChange = onSearchQueryChanged,
        onSearch = { onFocused(true) },
        expanded = focused,
        onExpandedChange = onFocused,
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        modifier = modifier.height(MEASUREMENT_SEARCH_BAR_HEIGHT),
    )
}
