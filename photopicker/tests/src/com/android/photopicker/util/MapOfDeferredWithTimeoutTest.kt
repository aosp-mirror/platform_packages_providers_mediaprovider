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

package com.android.photopicker.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertWithMessage
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MapOfDeferredWithTimeoutTest {

    @Test
    fun testTasksRunsInParallel() = runTest {
        val time = measureTime {
            val inputMap: MutableMap<Int, suspend (Unit) -> Any?> = mutableMapOf()
            for (i in 1..10) {
                inputMap[i] = {
                    delay(50)
                    i
                }
            }

            val resultMap: Map<Int, Deferred<Any?>> =
                mapOfDeferredWithTimeout(
                    inputMap,
                    Unit,
                    100,
                    backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                )

            for (i in 1..10) {
                val result: Any? = resultMap[i]?.await()
                assertWithMessage("Expected result type is not Int").that(result is Int).isTrue()
            }
        }

        // If the map operation was not run in parallel there would be a expected time of 50 * N
        // where N is the number of elements in the loop (10 in this case).
        assertWithMessage("Expected total time to be less that 500ms")
            .that(time.inWholeMilliseconds)
            .isLessThan(500)
    }

    @Test
    fun testMapTimeout() {
        val backgroundDispatcher = Dispatchers.IO
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val time = measureTime {
            runBlocking {
                val inputMap: Map<String, suspend (Unit) -> Any?> =
                    mapOf(
                        "key1" to
                            {
                                delay(5000)
                                true
                            },
                        "key2" to
                            {
                                delay(10)
                                true
                            },
                        "key3" to { throw RuntimeException() },
                    )

                val resultMap: Map<String, Deferred<Any?>> =
                    mapOfDeferredWithTimeout(
                        inputMap,
                        Unit,
                        50,
                        backgroundScope,
                        backgroundDispatcher,
                    )

                assertWithMessage("Task should be timed out. Expected result is null.")
                    .that(resultMap["key1"]?.await())
                    .isNull()

                assertWithMessage("Result type is not Boolean")
                    .that(resultMap["key2"]?.await() is Boolean)
                    .isTrue()

                assertWithMessage(
                        "Error thrown by task should be silently logged. " +
                            "Expected result is null."
                    )
                    .that(resultMap["key3"]?.await())
                    .isNull()
            }
        }

        // If the timeout didn't take effect, this would take more than 5 seconds to run.
        assertWithMessage("Expected total time to be less that 500ms")
            .that(time.inWholeMilliseconds)
            .isLessThan(500)
    }
}
