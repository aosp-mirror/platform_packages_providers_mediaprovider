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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.MULTI_SELECT_CONFIG
import com.android.photopicker.core.configuration.SINGLE_SELECT_CONFIG
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.data.model.Grantable
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GrantsAwareSelectionTest {

    /** A sample data class used only for testing. */
    private data class SelectionData(val id: Int, val isPreGrantedParam: Boolean = false) :
        Grantable {
        override val isPreGranted: Boolean = isPreGrantedParam
    }

    private val INITIAL_SELECTION =
        buildSet<SelectionData> {
            for (i in 1..10) {
                add(SelectionData(id = i))
            }
        }

    /** Ensures the selection is initialized as empty when no items are provided. */
    @Test
    fun testSelectionIsEmptyByDefault() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = SINGLE_SELECT_CONFIG
                )
            )
        val snapshot = selection.snapshot()

        assertWithMessage("Snapshot was expected to be empty.").that(snapshot).isEmpty()
        assertWithMessage("Emitted flow was expected to be empty.")
            .that(selection.flow.first())
            .isEmpty()
    }

    /** Ensures the selection is initialized with the provided items. */
    @Test
    fun testSelectionIsInitialized() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = INITIAL_SELECTION
            )

        val snapshot = selection.snapshot()
        val flow = selection.flow.first()

        assertWithMessage("Snapshot was expected to contain the initial selection")
            .that(snapshot)
            .isEqualTo(INITIAL_SELECTION)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(10)

        assertWithMessage("Emitted flow was expected to contain the initial selection")
            .that(flow)
            .isEqualTo(INITIAL_SELECTION)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(10)
    }

    @Test
    fun testSelectionReturnsSuccess() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
            )


        assertWithMessage("Selection addition was expected to be successful: item 1")
            .that(selection.add(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
        assertWithMessage("Selection addition was expected to be successful: item 2")
            .that(selection.toggle(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
        assertWithMessage("Selection addition was expected to be successful: item 3")
            .that(selection.toggleAll(setOf(SelectionData(3))))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    @Test
    fun testSelectionReturnsSelectionLimitExceededWhenFull() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = SINGLE_SELECT_CONFIG
                ),
                initialSelection = setOf(SelectionData(1))
            )


        assertWithMessage("Snapshot was expected to contain the initial selection")
            .that(selection.add(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED)
    }

    /** Ensures a single item can be added to the selection. */
    @Test
    fun testSelectionCanAddSingleItem() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                )
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val testItem = SelectionData(id = 999)
        selection.add(testItem)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot does not contain the added item")
            .that(snapshot)
            .contains(testItem)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(1)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value does not contain the added item.")
            .that(flow)
            .contains(testItem)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(1)
    }

    /** Ensures bulk additions. */
    @Test
    fun testSelectionCanAddMultipleItems() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                )
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val values =
            setOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )
        selection.addAll(values)

        advanceTimeBy(100)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot does not contain the added items")
            .that(snapshot)
            .containsExactly(*values.toTypedArray())
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(6)

        assertWithMessage("Emitted flow does not contain the added items")
            .that(emissions.last())
            .containsExactly(*values.toTypedArray())
        assertWithMessage("Emitted flow has an unexpected size").that(emissions.last()).hasSize(6)
    }

    /** Ensures a selection can be reset. */
    @Test
    fun testSelectionCanBeCleared() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = INITIAL_SELECTION
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        assertWithMessage("Initial snapshot state does not match expected size")
            .that(selection.snapshot())
            .hasSize(10)

        selection.clear()

        assertWithMessage("Resulting snapshot does not match expected size")
            .that(selection.snapshot())
            .isEmpty()

        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(10)

        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .isEmpty()
    }

    /** Ensures a single item can be removed. */
    @Test
    fun testSelectionCanRemoveSingleItem() = runTest {
        val testItem = SelectionData(id = 999)
        val anotherTestItem = SelectionData(id = 1000)
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = setOf(testItem, anotherTestItem)
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val initialSnapshot = selection.snapshot()
        assertWithMessage("Initial Snapshot does not contain the expected item")
            .that(initialSnapshot)
            .isEqualTo(setOf(testItem, anotherTestItem))
        assertWithMessage("Initial Snapshot has an unexpected size")
            .that(initialSnapshot)
            .hasSize(2)

        selection.remove(testItem)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot contains the removed item.")
            .that(snapshot)
            .doesNotContain(testItem)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(1)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value contains the removed item.")
            .that(flow)
            .doesNotContain(testItem)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(1)
    }

    /** Ensures bulk removals. */
    @Test
    fun testSelectionCanRemoveMultipleItems() = runTest {
        val values =
            setOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )

        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = values
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val initialSnapshot = selection.snapshot()
        assertWithMessage("Initial Snapshot has an unexpected size")
            .that(initialSnapshot)
            .hasSize(6)

        val removedValues = values.take(3)
        selection.removeAll(removedValues)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot contains a removed item.")
            .that(snapshot)
            .containsNoneIn(removedValues.toTypedArray())
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(3)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value contains the removed item.")
            .that(flow)
            .containsNoneIn(removedValues.toTypedArray())
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(3)
    }

    /** Ensures a single item can be toggled in and out of the selected set. */
    @Test
    fun testSelectionCanToggleSingleItem() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = INITIAL_SELECTION
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val item = INITIAL_SELECTION.first()

        selection.toggle(item)

        assertWithMessage("Snapshot contained an item that should have been removed")
            .that(selection.snapshot())
            .doesNotContain(item)

        advanceTimeBy(100)
        assertWithMessage("Flow emission contained an item that should have been removed")
            .that(emissions.last())
            .doesNotContain(item)

        selection.toggle(item)

        assertWithMessage("Snapshot does not contain an item that should have been added")
            .that(selection.snapshot())
            .contains(item)

        advanceTimeBy(100)
        assertWithMessage("Flow emission does not contain an item that should have been added")
            .that(emissions.last())
            .contains(item)
    }

    /** Ensures multiple items can be toggled in and out of the selected set. */
    @Test
    fun testSelectionCanToggleMultipleItems() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = INITIAL_SELECTION
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val items = INITIAL_SELECTION.take(3)

        selection.toggleAll(items)

        assertWithMessage("Snapshot contained an item that should have been removed")
            .that(selection.snapshot())
            .containsNoneIn(items)

        advanceTimeBy(100)
        assertWithMessage("Flow emission contained an item that should have been removed")
            .that(emissions.last())
            .containsNoneIn(items)

        selection.toggleAll(items)

        assertWithMessage("Snapshot does not contain an item that should have been added")
            .that(selection.snapshot())
            .containsAtLeastElementsIn(items)

        advanceTimeBy(100)
        assertWithMessage("Flow emission does not contain an item that should have been added")
            .that(emissions.last())
            .containsAtLeastElementsIn(items)
    }

    /** Ensures selection returns the correct position for selected items. */
    @Test
    fun testSelectionCanReturnItemPosition() = runTest {
        val values =
            listOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )

        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = values
            )

        assertWithMessage("Received unexpected position for item.")
            .that(selection.getPosition(values.get(2)))
            .isEqualTo(2)
    }

    /** Ensures selection returns -1 for items not present in the selection. */
    @Test
    fun testSelectionGetPositionForMissingItem() = runTest {
        val selection: Selection<SelectionData> =
            GrantsAwareSelectionImpl(
                scope = backgroundScope,
                configuration =
                provideTestConfigurationFlow(
                    scope = backgroundScope,
                    defaultConfiguration = MULTI_SELECT_CONFIG
                ),
                initialSelection = INITIAL_SELECTION
            )

        val missingElement = SelectionData(id = 999)

        assertWithMessage("Received unexpected position for item.")
            .that(selection.getPosition(missingElement))
            .isEqualTo(-1)
    }

    /** Ensures a single preGranted item can be removed and added again. */
    @Test
    fun testSelectionCanRemoveSinglePreGrantedItem() =
        runTest {
            // mock a test item to return isPreGranted as true.
            val testItem = SelectionData(id = 999, isPreGrantedParam = true)

            val selection =
                GrantsAwareSelectionImpl<SelectionData>(
                    scope = backgroundScope,
                    configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                    preGrantedItemsCount = 1, // corresponding to testItem
                )

            val emissions = mutableListOf<Set<SelectionData>>()
            backgroundScope.launch { selection.flow.toList(emissions) }

            val initialSnapshot = selection.snapshot()
            // There is only one preGranted item
            assertWithMessage("Initial Snapshot has an unexpected size")
                .that(initialSnapshot)
                .hasSize(1)

            // remove preGranted item
            selection.remove(testItem)

            val snapshot = selection.snapshot()
            advanceTimeBy(100)
            val flow = emissions.last()

            assertWithMessage("Deselection should contain test item").that(
                selection.getDeselection()
            ).contains(testItem)

            assertWithMessage("Snapshot contains the removed item.")
                .that(snapshot).doesNotContain(testItem)

            assertWithMessage("Emitted flow value contains the removed item.")
                .that(flow)
                .doesNotContain(testItem)
            assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(0)

            // Now add the preGranted item again and verify that it was removed from deselection.
            selection.add(testItem)

            val snapshot2 = selection.snapshot()
            advanceTimeBy(100)
            val flow2 = emissions.last()
            assertWithMessage("Deselection should not contain test item").that(
                selection
                    .getDeselection(),
            )
                .doesNotContain(testItem)

            assertWithMessage("Snapshot contains the added item.")
                .that(snapshot2).contains(testItem)
            assertWithMessage("Snapshot has an unexpected size").that(snapshot2).hasSize(1)

            assertWithMessage("Emitted flow value contains the removed item.")
                .that(flow2)
                .contains(testItem)
            assertWithMessage("Emitted flow has an unexpected size").that(flow2).hasSize(1)
        }
}