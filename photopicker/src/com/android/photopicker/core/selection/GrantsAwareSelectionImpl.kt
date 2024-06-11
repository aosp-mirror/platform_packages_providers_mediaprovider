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

import androidx.annotation.GuardedBy
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED
import com.android.photopicker.core.selection.SelectionModifiedResult.SUCCESS
import com.android.photopicker.data.model.Grantable
import com.android.photopicker.data.model.Media
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A class which manages a ordered selection of data objects during a Photopicker session and also
 * handles actions on preGranted media.
 *
 * [Selection] is the source of truth for the current selection state at all times. Features and
 * elements should not try to guess at selection state or maintain their own state related to
 * selection logic.
 *
 * To that end, Selection exposes its data in multiple ways, however UI elements should generally
 * collect and observe the provided flow since the APIs are subject to a [Mutex] and can suspend
 * until the lock can be acquired.
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
 * @property configuration a collectable [StateFlow] of configuration changes
 * @property preGrantedItemsCount represents the total number of grants help by the current package.
 */
class GrantsAwareSelectionImpl<T : Grantable>(
    val scope: CoroutineScope,
    val initialSelection: Collection<T>? = null,
    private val configuration: StateFlow<PhotopickerConfiguration>,
    val preGrantedItemsCount: Int = 0,
) : Selection<T> {

    // An internal mutex is used to enforce thread-safe access of the selection set.
    private val mutex = Mutex()
    private val _deSelection: LinkedHashSet<T> = LinkedHashSet()
    private val _selection: LinkedHashSet<T> = LinkedHashSet()

    private val _flow: MutableStateFlow<GrantsAwareSet<T>>
    override val flow: StateFlow<GrantsAwareSet<T>>

    init {
        if (initialSelection != null) {
            _selection.addAll(initialSelection)
        }
        _flow = MutableStateFlow(GrantsAwareSet(_selection, _deSelection, preGrantedItemsCount))
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
     * For preGranted Media items, reaching here would mean that the item was deselected and now is
     * being selected again, in this case it needs to be removed from the de-selection set.
     *
     * For non preGranted Media items, if the item is already present in the selection, a duplicate
     * will not be added, and it's relative position in the selection will not be affected.
     *
     * Afterwards, will emit the new selection into the exposed flow.
     *
     * @param item the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition.
     */
    @GuardedBy("mutex")
    override suspend fun add(item: T): SelectionModifiedResult {
        mutex.withLock {
            if (item.isPreGranted) {
                _deSelection.remove(item)
                updateFlow()
                return SUCCESS
            }
            val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
            if (itemCanFit) {
                _selection.add(item)
                updateFlow()
                return SUCCESS
            } else {
                return FAILURE_SELECTION_LIMIT_EXCEEDED
            }
        }
    }

    /**
     * Adds all of the requested items to the selection. If one or more are already in the
     * selection, this will not add duplicate items. Afterwards, will emit the new selection into
     * the exposed flow.
     *
     * This method only succeeds if all of the items will fit in the current selection.
     *
     * @param items the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition.
     */
    @GuardedBy("mutex")
    override suspend fun addAll(items: Collection<T>): SelectionModifiedResult {
        mutex.withLock {
            val itemsWithPregrants = LinkedHashSet<T>()
            val itemsToAdd = LinkedHashSet<T>()

            for (item in items){
                if (item.isPreGranted){
                    itemsWithPregrants.add(item)
                } else {
                    itemsToAdd.add(item)
                }
            }
            val itemsCanFit = ensureSelectionLimitLocked(itemsToAdd.size)
            if (itemsCanFit) {
                _selection.addAll(itemsToAdd)
                _deSelection.removeAll(itemsWithPregrants)
                updateFlow()
                return SUCCESS
            } else {
                return FAILURE_SELECTION_LIMIT_EXCEEDED
            }
        }
    }

    /** Empties the current selection of objects, returning the selection to an empty state.
     *
     * Also, any pre-granted item that was de-selected will now reset i.e. no grants will be
     * revoked.
     */
    @GuardedBy("mutex")
    override suspend fun clear() {
        mutex.withLock {
            _selection.clear()
            _deSelection.clear()
            updateFlow()
        }
    }

    /** @return Whether the selection contains the requested item. */
    @GuardedBy("mutex")
    override suspend fun contains(item: T): Boolean {
        return mutex.withLock {
            _selection.contains(item) ||
                    (item.isPreGranted && !_deSelection.contains(item))
        }
    }

    /** @return Whether the selection currently contains all of the requested items. */
    @GuardedBy("mutex")
    override suspend fun containsAll(items: Collection<T>): Boolean {
        return mutex.withLock {
            for (item in items) {
                if (!contains(item)) {
                    return@withLock false
                }
            }
            true
        }
    }

    /**
     * Fetches the 0-based position of the item in the selection.
     *
     * @return The position (index) of the item in the selection list. Will return -1 if the item is
     *   not present in the selection.
     */
    @GuardedBy("mutex")
    override suspend fun getPosition(item: T): Int {
        return mutex.withLock { _selection.indexOf(item) }
    }

    /**
     * Removes the requested item from the selection. If the item is not in the selection, this has
     * no effect. Afterwards, will emit the new selection into the exposed flow.
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    @GuardedBy("mutex")
    override suspend fun remove(item: T): SelectionModifiedResult {
        return mutex.withLock {
            if (item.isPreGranted) {
                _deSelection.add(item)
                updateFlow()
            } else {
                _selection.remove(item)
                updateFlow()
            }
            SUCCESS
        }
    }

    /**
     * Removes all of the items from the selection.
     *
     * If one or more items are not present in the selection, this has no effect. Afterwards, will
     * emit the new selection into the exposed flow.
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    @GuardedBy("mutex")
    override suspend fun removeAll(items: Collection<T>): SelectionModifiedResult {
        return mutex.withLock {
            _selection.removeAll(items)
            for (item in items) {
                if (item.isPreGranted)
                    _deSelection.add(item)
            }
            updateFlow()
            SUCCESS
        }
    }

    /**
     * Take an immutable copy of the current selection. This copy is a snapshot of the current
     * selection and is not updated if the selection changes.
     *
     * @return A frozen copy of the current selection set.
     */
    @GuardedBy("mutex")
    override suspend fun snapshot(): Set<T> {
        return mutex.withLock {
            // Create a new [grantsSet] to emit updated values.
            GrantsAwareSet(_selection.toSet(), _deSelection.toSet(), preGrantedItemsCount)
        }
    }

    /**
     * Toggles the requested item in the selection.
     *
     * If the item is of type [Media] and is preGranted i.e. [Media.isPreGranted] is true then when
     * such an item is toggled, if it is not part of _deSelection then it is added to _deselection
     * otherwise removed from it.
     *
     * For non preGranted items: if the item is already in the selection, it is removed.
     * If the item is not in the selection, it is added.
     *
     * Afterwards, will emit the new selection into the exposed flow.
     *
     * @param item the item to add
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggle.
     */
    @GuardedBy("mutex")
    override suspend fun toggle(item: T): SelectionModifiedResult {
        mutex.withLock {
            if (item.isPreGranted) {
                if (_deSelection.contains(item)) {
                    _deSelection.remove(item)
                } else {
                    _deSelection.add(item)
                }
            } else {
                when (_selection.contains(item)) {
                    true -> _selection.remove(item) // if item present in selection then remove it.
                    false -> { // if item is not present in selection then add it.
                        val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
                        if (itemCanFit) {
                            _selection.add(item)
                        } else {
                            // When the max limit for the number of items in selection is exceeded
                            // then return back with a FAILURE_SELECTION_LIMIT_EXCEEDED result.
                            return FAILURE_SELECTION_LIMIT_EXCEEDED
                        }
                    }
                }
            }

            // update the flow and return back result as SUCCESS.
            updateFlow()
            return SUCCESS
        }
    }

    /**
     * Toggles all of the requested items in the selection. This is the same as calling toggle(item)
     * on each item in the set, it will act on each item in the provided list independently.
     * Afterwards, will emit the new selection into the exposed flow.
     *
     * Note: Since this toggle acts on items individually, it may fail part way through if the
     * selection becomes full.
     *
     * @param items to toggle in the selection
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggleAll operation.
     */
    @GuardedBy("mutex")
    override suspend fun toggleAll(items: Collection<T>): SelectionModifiedResult {
        mutex.withLock {
            for (item in items) {
                if (item.isPreGranted) {
                    if (_deSelection.contains(item)) {
                        _deSelection.remove(item)
                    } else {
                        _deSelection.add(item)
                    }
                } else {
                    when (_selection.contains(item)) {
                        // if item present in selection then remove it.
                        true -> _selection.remove(item)
                        // if item is not present in selection then add it.
                        false -> {
                            val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
                            if (itemCanFit) {
                                _selection.add(item)
                            } else {
                                // When the max limit for the number of items in selection is
                                // exceeded then return back with a FAILURE_SELECTION_LIMIT_EXCEEDED
                                // result.
                                return FAILURE_SELECTION_LIMIT_EXCEEDED
                            }
                        }
                    }
                }
            }
            updateFlow()
            return SUCCESS
        }
    }

    /**
     * Returns a ReadOnly object contains items which were preGranted but de-selected by the user in
     * the current session.
     */
    @GuardedBy("mutex")
    override suspend fun getDeselection(): Collection<T> {
        return mutex.withLock {
            _deSelection.toSet()
        }
    }

    /** Internal method that snapshots the current selection and emits it to the exposed flow. */
    private suspend fun updateFlow() {
        _flow.update { GrantsAwareSet(_selection, _deSelection, preGrantedItemsCount) }
    }

    /**
     * Method for checking if the given [size] will fit in the current selection, considering the
     * [PhotopickerConfiguration.selectionLimit].
     *
     * IMPORTANT: This method should always be checked after acquiring the [Mutex] selection lock
     * but prior adding any items to the selection.
     *
     * @return true if the item can fit in the selection. false otherwise.
     */
    private suspend fun ensureSelectionLimitLocked(size: Int): Boolean {
        return _selection.size + size <= configuration.value.selectionLimit
    }
}
