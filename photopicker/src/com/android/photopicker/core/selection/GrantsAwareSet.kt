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

import com.android.photopicker.data.model.Grantable

/**
 * A specialized set implementation that is aware of both user selections and pre-granted elements.
 *
 * This class extends the behavior of a standard set by incorporating pre-granted elements
 * into its logic. An element is considered to be part of the set if either:
 *
 * 1. It has been explicitly selected by the user.
 * 2. It is pre-granted and hasn't been explicitly de-selected by the user.
 *
 * **This class should be used for the following custom implementations :**
 * - Provides an accurate `size` reflecting both selected and pre-granted elements.
 * - `contains()` method which checks if the element should be reflected as selected on the UI.
 *
 * @property selection The set of elements explicitly selected by the user.
 * @property deSelection The set of pre-granted elements that have been explicitly de-selected.
 * @property preGrantedelementsCount The number of pre-granted elements (not including those in `deSelection`).
 */
class GrantsAwareSet<T : Grantable>(
    val selection: Set<T>,
    val deSelection: Set<T>,
    val preGrantedelementsCount: Int = 0,
) : Set<T> {

    /**
     * Size of the set based on current selection and preGranted elements.
     */
    override val size: Int = selection.size - deSelection.size + preGrantedelementsCount

    /**
     * Checks if the set contains a specific element.
     *
     * This implementation considers two scenarios:
     *
     * 1. **Direct Presence in the Selection:**
     *    - Returns `true` if the `element` is directly present in the current user selection.
     *
     * 2. **Pre-Granted Media:**
     *    - If the `element` is a `Media` object:
     *        - Returns `true` if the `Media` is pre-granted (via `isPreGranted()`) AND
     *          it is not present in the deSelection set (i.e., the user has not explicitly
     *          de-selected it).
     *
     * @param element The element to check for.
     * @return `true` if the element is considered to be in the set, `false` otherwise.
     */
    override fun contains(element: T): Boolean {
        // Return true if the element to be checked is part of the selection list.
        if (selection.contains(element)) {
            return true
        }
        // If the element is preGranted and is not present in the deSelection set i.e. the element
        // has not been de-selected by the user then return true.
        if (element.isPreGranted && !deSelection.contains(element)) {
            return true
        }
        return false
    }

    /**
     * Returns true when:
     * - No element has been selected by the user.
     * - No preGranted elements are present for the current package and user.
     * - If preGrants are present, all of them have been de-selected by the user.
     *
     * Returns false is all other cases.
     */
    override fun isEmpty(): Boolean {
        return size == 0
    }

    /**
     * Provides an iterator to iterated only over the current user selection.
     *
     * Does not include preGrants.
     */
    override fun iterator(): Iterator<T> {
        return selection.iterator()
    }

    /**
     * Checks if all elements provided in the input are present in the set.
     */
    override fun containsAll(elements: Collection<T>): Boolean {
        for (element in elements) {
            if (!contains(element)) {
                return false
            }
        }
        return true
    }
}