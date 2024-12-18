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

import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A wrapper class responsible for managing search jobs and their cancellation.
 *
 * This class encapsulates the logic for starting a new search operation, canceling any ongoing
 * search.
 *
 * @param scope The CoroutineScope in which to launch the search job. This is typically a ViewModel
 *   scope or a scope that is tied to the lifecycle of the component that uses this class.
 */
class SearchJob(private val scope: CoroutineScope) {
    private var searchJob: Job? = null
    val cancellationSignal: CancellationSignal = CancellationSignal()

    /** Cancels the current search job. */
    fun cancel() {
        searchJob?.cancel()
        cancellationSignal.cancel()
    }

    /**
     * Launches a coroutine to execute a provided search action.
     *
     * This function starts a new coroutine within the defined scope to perform the provided
     * `searchAction`.
     *
     * @param searchAction A suspend function that represents the search operation to be executed.
     */
    fun startSearch(searchAction: suspend () -> Unit) {
        searchJob = scope.launch { searchAction() }
    }
}
