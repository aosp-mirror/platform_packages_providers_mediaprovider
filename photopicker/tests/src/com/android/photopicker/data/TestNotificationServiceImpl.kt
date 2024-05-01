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
import android.content.UriMatcher
import android.database.ContentObserver
import android.net.Uri

/**
 * Test implementation of Notification Service. It registers the observers in memory. Test writers
 * can use [dispatchChangeToObservers] method to notify the registered content observers.
 */
class TestNotificationServiceImpl : NotificationService {

    companion object {
        private const val EXACT_MATCH = 0
        private const val DESCENDANT_MATCH = 1
        private const val WILDCARD = "*"
    }

    private val registeredObservers: MutableMap<Uri, UriObservers> = mutableMapOf()

    override fun registerContentObserverCallback(
        contentResolver: ContentResolver,
        uri: Uri,
        notifyDescendants: Boolean,
        observer: ContentObserver
    ) {
        val uriObservers: UriObservers = registeredObservers[uri] ?: UriObservers()
        uriObservers.add(observer, notifyDescendants)
        registeredObservers.put(uri, uriObservers)
    }

    override fun unregisterContentObserverCallback(
        contentResolver: ContentResolver,
        observer: ContentObserver
    ) {
        registeredObservers.values.forEach {
            it.remove(observer)
        }
    }

    /**
     * This method can be used to simulate notifying [ContentObserver]-s registered against a
     * given Uri.
     */
    fun dispatchChangeToObservers(uri: Uri) {
        val uriBuilder = Uri.parse("content://" + uri.authority).buildUpon()
        uri.pathSegments.forEach {
            uriBuilder.appendPath(it)
            val newUri = uriBuilder.build()
            val allowExactMatch = newUri.equals(uri)
            registeredObservers[newUri]
                ?.getMatchedObservers(newUri, allowExactMatch)
                ?.forEach { contentObserver: ContentObserver ->
                    contentObserver.dispatchChange(
                        /* selfChange */ true,
                        uri,
                        /* flags */ 0
                    )
                }
        }
    }

    /**
     * Represents the Content Observers registered against a Uri. A registered content observer can
     * choose to be notified for descendant Uris.
     */
    class UriObservers {
        private val exactMatchObservers: MutableList<ContentObserver> = mutableListOf()
        private val descendantMatchObservers: MutableList<ContentObserver> = mutableListOf()

        fun add(observer: ContentObserver, notifyDescendants: Boolean) {
            if (notifyDescendants) {
                descendantMatchObservers.add(observer)
            } else {
                exactMatchObservers.add(observer)
            }
        }

        fun remove(observer: ContentObserver): Boolean {
            return exactMatchObservers.remove(observer) ||
                    descendantMatchObservers.remove(observer)
        }

        fun getMatchedObservers(uri: Uri, allowExactMatch: Boolean):
                Set<ContentObserver> {
            val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
            uriMatcher.addURI(uri.authority, uri.path, EXACT_MATCH)
            uriMatcher.addURI(uri.authority, uri.path + WILDCARD, DESCENDANT_MATCH)

            val observers: MutableSet<ContentObserver> = mutableSetOf()
            when (uriMatcher.match(uri)) {
                EXACT_MATCH -> {
                    if (allowExactMatch) {
                        observers.addAll(exactMatchObservers)
                        observers.addAll(descendantMatchObservers)
                    }
                }
                DESCENDANT_MATCH -> {
                    observers.addAll(descendantMatchObservers)
                }
            }
            return observers
        }
    }
}