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

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

private const val TAG = "MapOfDeferredWithTimeout"

/**
 * A Suspend function that accepts a (key -> lambda) map and a timeout.
 *
 * Each lambda runs in parallel using [async] and a (key -> [Deferred] result) map is returned by
 * this method.
 *
 * If any [async] task runs for longer than the provided timeout, it will automatically be marked as
 * cancelled and the result will be set to null. However, the task should periodically check if it
 * was cancelled and terminate processing on its own. This is because coroutine cancellation is
 * cooperative.
 *
 * If any async task throws an error, it will be swallowed and the result will be set to null.
 */
suspend fun <A, B> mapOfDeferredWithTimeout(
    inputMap: Map<A, suspend (B) -> Any?>,
    input: B,
    timeoutMillis: Long,
    backgroundScope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Map<A, Deferred<Any?>> = coroutineScope {
    inputMap
        .map<A, suspend (B) -> Any?, Pair<A, Deferred<Any?>>> { (key, block) ->
            key to
                async {
                    try {
                        withTimeout(timeoutMillis) {
                            // Coroutine cancellations are cooperative. This means that if the block
                            // running inside [withTimeout] is non-cooperative, the timeout will
                            // not be enforced. In order to ensure that the timeout is enforced,
                            // wrap the call in an [async] block which is cancellation aware.
                            Log.d(TAG, "Fetching result for : $key")
                            val result = backgroundScope.async(dispatcher) { block(input) }.await()
                            Log.d(TAG, "Finished fetching result for : $key val: $result")
                            result
                        }
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "An error occurred in fetching result for key: $key", e)
                        null
                    }
                }
        }
        .toMap()
}
