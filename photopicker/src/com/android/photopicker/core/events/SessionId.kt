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

import java.security.SecureRandom

// The sessionId can contain at most 20 bits which gives ~1M possibilities for the same, so ~0.5%
// collision probability in 100 values
const val MAX_SESSION_ID: Int = 1 shl 20

/**
 * Generates a random integer between 1 and [MAX_SESSION_ID] to identify a particular photopicker
 * session. The id gets attached to all the picker atoms so that it is easy to identify logs that
 * are session specific.
 *
 * @return photopicker sessionId
 */
fun generatePickerSessionId(): Int {
    val getRandom = SecureRandom()
    return 1 + getRandom.nextInt(MAX_SESSION_ID)
}
