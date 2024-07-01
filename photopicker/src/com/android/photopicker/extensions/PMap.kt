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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map

/**
 * A parallel map function that inherits the current [CoroutineContext] and uses structured
 * concurrency to run each map iteration in parallel rather than running each block sequentially.
 *
 * @param <T> the incoming type of the map function
 * @param <R> the returned type of the map function
 * @param block the map block to run on each input to produce each output
 * @return a List<R> of the produced outputs
 */
suspend fun <A, B> Iterable<A>.pmap(block: suspend (A) -> B): List<B> = coroutineScope {
    map { async { block(it) } }.awaitAll()
}
