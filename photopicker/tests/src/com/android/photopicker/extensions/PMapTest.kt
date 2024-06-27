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

package com.android.photopicker.extensions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertWithMessage
import kotlin.time.measureTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [pmap] extension function */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PMapTest {

    /* Ensures that pmap runs it's execution in parallel. */
    @Test
    fun testPMapRunsInParallel() {
        val time = measureTime {
            runBlocking {
                var output =
                    (1..100).pmap {
                        delay(1000L)
                        it * 2
                    }
                // Ensure the map operation actually ran and that the first element in the output is
                // expected.
                assertWithMessage("Map block did not run").that(output[0]).isEqualTo(2)
            }
        }

        // If the map operation was not run in parallel there would be a expected time of 1000 * N
        // where N is the number of elements in the loop (100 in this case).
        assertWithMessage("Expected total time to be less that 2000ms")
            .that(time.inWholeMilliseconds)
            .isLessThan(2000L)
    }
}
