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

package com.android.photopicker.core.selection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A class which manages a ordered selection of data objects during a Photopicker session.
 *
 * [Selection] is the source of truth for the current selection state at all times. Features and
 * elements should not try to guess at selection state or maintain their own state related to
 * selection logic.
 *
 * To that end, Selection exposes its data in multiple ways, however UI elements should generally
 * collect and observe the provided flow since the APIs are subject to a [Mutex] and can
 * suspend until the lock can be acquired.
 *
 * Additionally, there is a flow exposed for classes that are interested in observing the selection
 * state as it changes over time. Since it is expected that UI elements will be listening to this
 * state, and changes to selection state will cause recomposition, it is highly recommended to use
 * the bulk APIs when updating more than one element in the selection to avoid multiple state
 * emissions since selection will emit immediately after a change is complete.
 *
 * Snapshot can be used to take a frozen state of the selection, as needed.
 *
 * @param T The type of object this selection holds.
 * @property scope A [CoroutineScope] that the flow is shared and updated in.
 * @property initialSelection A collection to include initial selection value.
 */
class Selection<T>(
    val scope: CoroutineScope,
    val initialSelection: Collection<T>? = null,
) {

    // An internal mutex is used to enforce thread-safe access of the selection set.
    private val mutex = Mutex()
    private val _selection: LinkedHashSet<T> = LinkedHashSet<T>()
    private val _flow: MutableStateFlow<Set<T>>
    val flow: StateFlow<Set<T>>

    init {
        if (initialSelection != null) {
            _selection.addAll(initialSelection)
        }
        _flow = MutableStateFlow(_selection.toSet())
        flow =
            _flow.stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = _flow.value,
            )
    }

    /**
     * Add the requested item to the selection.
     *
     * If the item is already present in the selection, a duplicate will not be added, and it's
     * relative position in the selection will not be affected. Afterwards, will emit the new
     * selection into the exposed flow.
     */
    suspend fun add(item: T) {
        mutex.withLock {
            _selection.add(item)
            updateFlow()
        }
    }

    /**
     * Adds all of the requested items to the selection. If one or more are already in the
     * selection, this will not add duplicate items. Afterwards, will emit the new selection into
     * the exposed flow.
     */
    suspend fun addAll(items: Collection<T>) {
        mutex.withLock {
            _selection.addAll(items)
            updateFlow()
        }
    }

    /** Empties the current selection of objects, returning the selection to an empty state. */
    suspend fun clear() {
        mutex.withLock {
            _selection.clear()
            updateFlow()
        }
    }

    /** @return Whether the selection contains the requested item. */
    suspend fun contains(item: T): Boolean {
        return mutex.withLock { _selection.contains(item) }
    }

    /** @return Whether the selection currently contains all of the requested items. */
    suspend fun containsAll(items: Collection<T>): Boolean {
        return mutex.withLock { _selection.containsAll(items) }
    }

    /**
     * Fetches the 0-based position of the item in the selection.
     *
     * @return The position (index) of the item in the selection list. Will return -1 if the item is
     *   not present in the selection.
     */
    suspend fun getPosition(item: T): Int {
        return mutex.withLock { _selection.indexOf(item) }
    }

    /**
     * Removes the requested item from the selection. If the item is not in the selection, this has
     * no effect. Afterwards, will emit the new selection into the exposed flow.
     */
    suspend fun remove(item: T) {
        mutex.withLock {
            _selection.remove(item)
            updateFlow()
        }
    }

    /**
     * Removes all of the items from the selection.
     *
     * If one or more items are not present in the selection, this has no effect. Afterwards, will
     * emit the new selection into the exposed flow.
     */
    suspend fun removeAll(items: Collection<T>) {
        mutex.withLock {
            _selection.removeAll(items)
            updateFlow()
        }
    }

    /**
     * Take an immutable copy of the current selection. This copy is a snapshot of the current
     * selection and is not updated if the selection changes.
     *
     * @return A frozen copy of the current selection set.
     */
    suspend fun snapshot(): Set<T> {
        return mutex.withLock { _selection.toSet() }
    }

    /**
     * Toggles the requested item in the selection.
     *
     * If the item is already in the selection, it is removed. If the item is not in the selection,
     * it is added. Afterwards, will emit the new selection into the exposed flow.
     */
    suspend fun toggle(item: T) {
        mutex.withLock {
            if (_selection.contains(item)) {
                _selection.remove(item)
            } else {
                _selection.add(item)
            }
            updateFlow()
        }
    }

    /**
     * Toggles all of the requested items in the selection. This is the same as calling toggle(item)
     * on each item in the set, it will act on each item in the provided list independently.
     * Afterwards, will emit the new selection into the exposed flow.
     */
    suspend fun toggleAll(items: Collection<T>) {
        mutex.withLock {
            for (item in items) {
                if (_selection.contains(item)) {
                    _selection.remove(item)
                } else {
                    _selection.add(item)
                }
            }
            updateFlow()
        }
    }

    /** Internal method that snapshots the current selection and emits it to the exposed flow. */
    private suspend fun updateFlow() {
        _flow.update { _selection.toSet() }
    }
}
