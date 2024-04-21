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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The view model for the Preview routes.
 *
 * This view model manages snapshotting the session's selection so that items can observe a slice of
 * state rather than the mutable selection state.
 */
@HiltViewModel
class PreviewViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
) : ViewModel() {

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    /**
     * A flow which exposes a snapshot of the selection. Initially this is an empty set and will not
     * automatically update with the current selection, snapshots must be explicitly requested.
     */
    val selectionSnapshot = MutableStateFlow<Set<Media>>(emptySet())

    /** Trigger a new snapshot of the selection. */
    fun takeNewSelectionSnapshot() {
        scope.launch { selectionSnapshot.update { selection.snapshot() } }
    }

    /**
     * Toggle the media item into the current session's selection.
     *
     * @param media
     */
    fun toggleInSelection(media: Media) {
        scope.launch { selection.toggle(media) }
    }
}
