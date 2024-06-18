package com.android.photopicker.core.selection

import com.android.photopicker.core.configuration.PhotopickerConfiguration
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * A class which manages a ordered selection of data objects during a Photopicker session.
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
 */
interface Selection<T> {

    val flow: StateFlow<Set<T>>

    /**
     * Add the requested item to the selection.
     *
     * @param item the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition.
     */
    suspend fun add(item: T): SelectionModifiedResult

    /**
     * Adds all of the requested items to the selection.
     *
     * @param items the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition.
     */
    suspend fun addAll(items: Collection<T>): SelectionModifiedResult

    /** Empties the current selection of objects, returning the selection to an empty state. */
    suspend fun clear()

    /** @return Whether the selection contains the requested item. */
    suspend fun contains(item: T): Boolean

    /** @return Whether the selection currently contains all of the requested items. */
    suspend fun containsAll(items: Collection<T>): Boolean

    /**
     * Fetches the 0-based position of the item in the selection.
     *
     * @return The position (index) of the item in the selection list. Will return -1 if the item is
     *   not present in the selection.
     */
    suspend fun getPosition(item: T): Int

    /**
     * Removes the requested item from the selection. If the item is not in the selection, this has
     * no effect. Afterwards, will emit the new selection into the exposed flow.
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    suspend fun remove(item: T): SelectionModifiedResult

    /**
     * Removes all of the items from the selection.
     *
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    suspend fun removeAll(items: Collection<T>): SelectionModifiedResult

    /**
     * Take an immutable copy of the current selection. This copy is a snapshot of the current
     * selection and is not updated if the selection changes.
     *
     * @return A frozen copy of the current selection set.
     */
    suspend fun snapshot(): Set<T>

    /**
     * Toggles the requested item in the selection.
     *
     * If the item is already in the selection, it is removed. If the item is not in the selection,
     * it is added. Afterwards, will emit the new selection into the exposed flow.
     *
     * @param item the item to add
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggle.
     */
    suspend fun toggle(item: T): SelectionModifiedResult

    /**
     * Toggles all of the requested items in the selection.
     *
     * @param items to toggle in the selection
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggleAll operation.
     */
    suspend fun toggleAll(items: Collection<T>): SelectionModifiedResult

    /**
     * @return a ReadOnly object contains items which were preGranted but de-selected.
     */
    suspend fun getDeselection(): Collection<T>
}