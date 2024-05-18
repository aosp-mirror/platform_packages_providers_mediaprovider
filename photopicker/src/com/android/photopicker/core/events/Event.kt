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

/* Convenience alias for classes that implement [Event] */
typealias RegisteredEventClass = Class<out Event>

/**
 * The definition of Photopicker events that can be sent through the Event bus.
 *
 * Event definitions should indicate where they are intended to be dispatched from.
 *
 * In general, favor adding a new event over re-using or re-purposing an existing event to avoid
 * conflicts or unintended side effects.
 *
 * See [Events] for implementation details and guidance on how to use the event bus. Ensure that any
 * events added are properly registered with the [FeatureManager].
 */
interface Event {

    /**
     * All events must contain a dispatcherToken which signifies which feature dispatched this
     * event. If the feature is not registered in the claiming feature's [eventsProduced] registry
     * this will cause an error.
     */
    val dispatcherToken: String

    // Signal Event when the user has confirmed the selection of media.
    data class MediaSelectionConfirmed(override val dispatcherToken: String) : Event

    // For showing a message to the user in a snackbar. See [SnackbarFeature] for
    // snackbar implementation details.
    data class ShowSnackbarMessage(override val dispatcherToken: String, val message: String) :
        Event
}
