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

package com.android.photopicker.test.utils

import androidx.compose.ui.test.IdlingResource
import java.util.concurrent.atomic.AtomicInteger

/**
 * A test helper that ensures that the ComposeTestRule can be made aware of Glide's asynchronous
 * operations.
 *
 * This is a very simple implementation of a Counting based IdlingResource. Each new Glide load that
 * is started should call [loadStarted], and then call [loadFinished] when it either fails, or
 * completes.
 *
 * Any time that the number of pending loads is equal to Zero, this IdlingResource will represent
 * itself as idle.
 */
class GlideLoadableIdlingResource : IdlingResource {

    // Are there any currently pending loads?
    override val isIdleNow: Boolean
        get() = _isIdleNow()

    override fun getDiagnosticMessageIfBusy(): String? {
        return when (_isIdleNow()) {
            true -> "Glide has $pending loads that are still pending."
            false -> null
        }
    }

    // The number of pending loads
    private var pending = AtomicInteger(0)

    /** Considered to be idle when there are no pending loads going on. */
    private fun _isIdleNow(): Boolean {
        return pending.get() == 0
    }

    /**
     * Increases the number of pending loads. Be sure that
     * [GlideLoadableIdlingResource#loadFinished] is eventually called, or this IdlingResource will
     * never register as idle.
     */
    fun loadStarted() {
        pending.incrementAndGet()
    }

    /**
     * Decrease the number of pending loads.
     *
     * This is the equivalent of marking one pending load as completed.
     */
    fun loadFinished() {
        pending.decrementAndGet()
    }

    /** Ignore all pending loads, and reset state to initialization. */
    fun reset() {
        pending.set(0)
    }
}
