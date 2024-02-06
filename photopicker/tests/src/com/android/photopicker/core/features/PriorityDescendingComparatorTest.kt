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

package com.android.photopicker.core.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [PriorityDescendingComparator] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PriorityDescendingComparatorTest {

    val pairOne = Pair("pairOne", 1)
    val pairTwo = Pair("pairOne", 2)
    val pairThree = Pair("pairOne", 3)
    val pairFour = Pair("pairOne", 4)
    val pairFive = Pair("pairOne", 5)
    val pairSix = Pair("pairOne", 6)
    val pairSeven = Pair("pairOne", 7)
    val pairEight = Pair("pairOne", 8)

    val allPairs =
        mutableListOf(
            pairOne,
            pairTwo,
            pairThree,
            pairFour,
            pairFive,
            pairSix,
            pairSeven,
            pairEight
        )

    /* Ensures that elements added in Ascending order are correctly sorted */
    @Test
    fun testInsertedAscendingElementsAreOrderedDescending() {

        val set = sortedSetOf<Pair<String, Int>>(PriorityDescendingComparator())
        set.addAll(allPairs)

        assertThat(set.size).isEqualTo(8)

        val iterator = set.iterator()
        val expectedOrderIterator = allPairs.reversed().iterator()
        while (iterator.hasNext()) {
            assertThat(iterator.next()).isEqualTo(expectedOrderIterator.next())
        }

        // Ensure both iterators are empty.
        assertThat(iterator.hasNext()).isFalse()
        assertThat(expectedOrderIterator.hasNext()).isFalse()
    }

    /* Ensures that elements added in shuffled order are correctly sorted */
    @Test
    fun testInsertedElementsShuffledAreOrderedDescending() {

        val shuffledPairs = mutableListOf(*allPairs.toTypedArray())
        shuffledPairs.shuffle()

        val set = sortedSetOf<Pair<String, Int>>(PriorityDescendingComparator())
        set.addAll(shuffledPairs)

        assertThat(set.size).isEqualTo(8)

        val iterator = set.iterator()
        val expectedOrderIterator = allPairs.reversed().iterator()
        while (iterator.hasNext()) {
            assertThat(iterator.next()).isEqualTo(expectedOrderIterator.next())
        }

        // Ensure both iterators are empty.
        assertThat(iterator.hasNext()).isFalse()
        assertThat(expectedOrderIterator.hasNext()).isFalse()
    }

    /* Ensures that elements added in descending order are correctly sorted */
    @Test
    fun testInsertedElementsDescendingAreOrderedDescending() {

        val set = sortedSetOf<Pair<String, Int>>(PriorityDescendingComparator())
        set.addAll(allPairs.reversed())

        assertThat(set.size).isEqualTo(8)

        val iterator = set.iterator()
        val expectedOrderIterator = allPairs.reversed().iterator()
        while (iterator.hasNext()) {
            assertThat(iterator.next()).isEqualTo(expectedOrderIterator.next())
        }

        // Ensure both iterators are empty.
        assertThat(iterator.hasNext()).isFalse()
        assertThat(expectedOrderIterator.hasNext()).isFalse()
    }
}
