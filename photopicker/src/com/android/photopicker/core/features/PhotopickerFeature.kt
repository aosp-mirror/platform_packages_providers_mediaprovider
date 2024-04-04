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

import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass

/**
 * The base feature interface, that exposes hooks from the [FeatureManager] that is available to all
 * features. It's unlikely that features will want to implement this directly, but rather implement
 * a different interface, such as [PhotopickerUiFeature] which inherits from the base interface.
 */
interface PhotopickerFeature {

    /**
     * Features must claim a [FeatureToken]. This is used to identify calls the feature makes such
     * as dispatching events (to ensure the feature has registered for these events). This must be
     * unique for any given runtime configuration of enabled features. Multiple features may claim
     * the same token, but they can never be enabled in the same configuration, or an
     * [IllegalStateException] will be thrown during initialization.
     */
    val token: String

    /**
     * Establishes the contract of Events that this feature requires from outside dependencies. Any
     * [Event] that is listened to inside any codepath this feature encapsulates should be
     * registered in this set.
     *
     * In the event the feature has indicated it should be enabled (in its Registration check) and
     * it's set of consumed events is not fulfilled by the global events produced this will cause a
     * RuntimeException if [PhotopickerConfiguration.isDeviceDebuggable] is true.
     *
     * For non debuggable devices, the error is not thrown to avoid crashes if possible, but this
     * should be considered a state which should be avoided at all costs.
     */
    val eventsConsumed: Set<RegisteredEventClass>

    /**
     * Establishes the contract of Events that this Feature produces.
     *
     * Any [Event] that is dispatched inside any codepath this feature encapsulates should be
     * registered in the returned set.
     *
     * The events produced here is used to compute the list of globally produced events to check if
     * all required events in a runtime configuration are produceable. Note: This does not mean that
     * events WILL be produced, as that may be user-interaction dependant, but this helps to catch
     * implicit or undeclared dependencies between features that rely on the Event bus.
     *
     * If an event is dispatched in a feature which is not present in its eventsProduced registry,
     * this will trigger a RuntimeException when [PhotopickerConfiguration.isDeviceDebuggable] is
     * true, and a warning will be logged otherwise.
     */
    val eventsProduced: Set<RegisteredEventClass>

    /**
     * Notification hook that the [FeatureManager] is about to emit a new configuration, and
     * reinitialize the active Feature set.
     *
     * It is highly likely that shortly after this call completes the current instance of this class
     * will be de-referenced and (potentially) be re-created if the Registration check still
     * indicates it should be enabled with the new configuration.
     *
     * The UI tree will be re-composed shortly after, and persistent state should be saved in
     * associated view models to avoid state loss.
     */
    fun onConfigurationChanged(config: PhotopickerConfiguration) {}
}
