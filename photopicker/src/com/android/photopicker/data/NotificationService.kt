/*
 * Copyright (C) 2024 The Android Open Source Project
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


package com.android.photopicker.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri

/**
 * Provides methods to register and unregister callbacks to receive notifications for data changes.
 *
 * Encapsulating this in a separate class allows us to inject this as a dependency. This provides
 * flexibility and increases testability.
 */
interface NotificationService {

    companion object {
        val TAG = "PhotoPickerNotificationService"
    }

    /**
     * Register a given ContentObserver callback.
     *
     * @param contentResolver The content resolver that you can register a [ContentObserver]
     * against.
     * @param uri The URI to watch for changes.
     * @param notifyDescendants hen false, the observer will be notified whenever a change occurs
     * to the exact URI specified by uri or to one of the URI's ancestors in the path hierarchy.
     * When true, the observer will also be notified whenever a change occurs to the URI's
     * descendants in the path hierarchy.
     * @param observer The object that receives callbacks when changes occur.
     */
    fun registerContentObserverCallback(
        contentResolver: ContentResolver,
        uri: Uri,
        notifyDescendants: Boolean,
        observer: ContentObserver
    )

    /**
     * Unregister a given ContentObserver callback.
     *
     * @param contentResolver The content resolver that you can register a [ContentObserver]
     * against.
     * @param observer The object that receives callbacks when changes occur.
     */
    fun unregisterContentObserverCallback(
        contentResolver: ContentResolver,
        observer: ContentObserver
    )
}