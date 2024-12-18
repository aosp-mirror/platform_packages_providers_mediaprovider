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

package com.android.photopicker.core.embedded

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [EmbeddedStateManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedStateManagerTest {

    @Test
    fun testEmitsEmbeddedState() = runTest {
        val embeddedStateManager = EmbeddedStateManager()

        val expectedEmbeddedState = EmbeddedState()

        backgroundScope.launch {
            val reportedEmbeddedState = embeddedStateManager.state.first()
            assertWithMessage("Reported embedded state is not correct")
                .that(reportedEmbeddedState)
                .isEqualTo(expectedEmbeddedState)
        }
    }

    @Test
    fun testEmitsExpandedStateChanged() = runTest {
        val embeddedStateManager = EmbeddedStateManager()

        val expectedEmbeddedState = EmbeddedState(isExpanded = false)

        val emissions = mutableListOf<EmbeddedState>()
        backgroundScope.launch { embeddedStateManager.state.toList(emissions) }

        advanceTimeBy(100)

        embeddedStateManager.setIsExpanded(isExpanded = true)

        advanceTimeBy(100)

        assertThat(emissions.size).isEqualTo(2)
        assertThat(emissions.first()).isEqualTo(expectedEmbeddedState)
        assertThat(emissions.last()).isEqualTo(expectedEmbeddedState.copy(isExpanded = true))
    }

    @Test
    fun testEmitsDarkThemeStateChanged() = runTest {
        val embeddedStateManager = EmbeddedStateManager()

        val expectedEmbeddedState = EmbeddedState(isDarkTheme = false)

        val emissions = mutableListOf<EmbeddedState>()
        backgroundScope.launch { embeddedStateManager.state.toList(emissions) }

        advanceTimeBy(100)

        embeddedStateManager.setIsDarkTheme(isDarkTheme = true)

        advanceTimeBy(100)

        assertThat(emissions.size).isEqualTo(2)
        assertThat(emissions.first()).isEqualTo(expectedEmbeddedState)
        assertThat(emissions.last()).isEqualTo(expectedEmbeddedState.copy(isDarkTheme = true))
    }

    @Test
    fun testTriggerRecomposeFlipsRecomposeToggle() = runTest {
        val embeddedStateManager = EmbeddedStateManager()

        val expectedEmbeddedState = EmbeddedState(recomposeToggle = false)

        val emissions = mutableListOf<EmbeddedState>()
        backgroundScope.launch { embeddedStateManager.state.toList(emissions) }

        advanceTimeBy(100)

        embeddedStateManager.triggerRecompose()

        advanceTimeBy(100)

        assertThat(emissions.size).isEqualTo(2)
        assertThat(emissions.first()).isEqualTo(expectedEmbeddedState)
        assertThat(emissions.last()).isEqualTo(expectedEmbeddedState.copy(recomposeToggle = true))
    }
}
