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

/**
 * Standard Priorities for use with [Location] registration.
 *
 * Features can also declare a custom priority by just providing a simple integer to the registation
 * callback, but these are some useful default priorities.
 *
 * @property priority The value of the priority level (this is what should be passed to
 *   registerLocation).
 */
enum class Priority(val priority: Int) {
    LAST(0),
    REGISTRATION_ORDER(1),
    LOW(25),
    MEDIUM(50),
    HIGH(90),
}
