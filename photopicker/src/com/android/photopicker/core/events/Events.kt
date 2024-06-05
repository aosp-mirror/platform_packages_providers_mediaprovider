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

package com.android.photopicker.core.events

import android.util.Log
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.features.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

/**
 * A session-global event bus for Photopicker.
 *
 * This event bus is a buffered channel which buffers at most 100 elements, and suspends all
 * subsequent dispatch requests until the buffer has free space.
 *
 * Events are not dispatched synchronously. Calling dispatch will enqueue the event in a background
 * coroutine, and does not block the dispatch method. If synchronous events are required, the event
 * bus should not be used.
 *
 * There are no visibility restrictions imposed on any events. All [Event] emissions are observable
 * by all current collectors, and this is by design. For feature code that needs to prevent leaking
 * internal signals to unwanted listeners, the [Event] bus should not be used.
 *
 * Events are delivered to all collectors in no particular guaranteed order, and if ordering is
 * implementation important, the event bus should not be used.
 *
 * Furthermore, [Event] dispatchers and listeners should NOT be recursive or cascading. Collectors
 * should ensure they do not dispatch additional events in response to receiving an event (either
 * directly or indirectly via updating internal state), as this may inadvertently introduce
 * unpredictable looping behaviors.
 */
class Events(
    private val scope: CoroutineScope,
    private val configuration: StateFlow<PhotopickerConfiguration>,
    private val featureManager: FeatureManager
) {

    companion object {
        val TAG: String = "PhotopickerEvents"
    }

    private val _events =
        Channel<Event>(capacity = 100, onBufferOverflow = BufferOverflow.SUSPEND) { undeliveredEvent
            ->
            Log.w(TAG, "Unable to deliver $undeliveredEvent, no receiver.")
        }

    /**
     * The exposed flow of events. This can be collected to receive all future event dispatches. It
     * is highly recommended to collect this flow in a lifecycle aware manner to save resources.
     */
    val flow: SharedFlow<Event> =
        _events.receiveAsFlow().shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * Dispatch an event into the event flow. The dispatch is scheduled in a background coroutine
     * and will not be synchronous. The event registry will be checked before the event is
     * dispatched, and callers should ensure the event is registered with the
     * [Event.dispatcherToken]'s registry.
     *
     * @param event - The event to dispatch.
     * @throws [UnregisteredEventDispatchedException] when the event is not in the eventsProduced
     *   registry for the dispatcher's feature registration. This is not thrown in production
     *   configurations to avoid a crash.
     */
    suspend fun dispatch(event: Event) {

        // Check with the [FeatureManager] to see if the event exists in the registry.
        if (!featureManager.isEventDispatchable(event)) {
            if (configuration.value.deviceIsDebuggable) {
                // When the device is debuggable, this should be a fatal error to prevent
                // undeclared events from being dispatched which might create implicit
                // dependencies.
                throw UnregisteredEventDispatchedException(
                    "$event was dispatched by ${event.dispatcherToken} without first " +
                        "declaring the Event in the eventsProduced registry."
                )
            } else {
                // The device is not debuggable; Avoid throwing and risk a crash, but still log
                // a warning.
                Log.w(
                    TAG,
                    "$event was dispatched by ${event.dispatcherToken} but the event was not " +
                        "registered."
                )
            }
        }

        _events.send(event)
    }
}
