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
import android.net.Uri

/**
 * Provides URI constants and helper functions.
 */
private const val MEDIA_PROVIDER_AUTHORITY = "media"
private const val UPDATE_PATH_SEGMENT = "update"
private const val AVAILABLE_PROVIDERS_PATH_SEGMENT = "available_providers"
private const val MEDIA_PATH_SEGMENT = "media"

private val pickerUri: Uri = Uri.Builder().apply {
    scheme(ContentResolver.SCHEME_CONTENT)
    authority(MEDIA_PROVIDER_AUTHORITY)
    appendPath("picker_internal")
    appendPath("v2")
}.build()

/**
 * URI for available providers resource.
 */
val AVAILABLE_PROVIDERS_URI: Uri = pickerUri.buildUpon().apply {
    appendPath(AVAILABLE_PROVIDERS_PATH_SEGMENT)
}.build()

/**
 * URI that receives [ContentProvider] change notifications for available provider updates.
 */
val AVAILABLE_PROVIDERS_CHANGE_NOTIFICATION_URI: Uri = pickerUri.buildUpon().apply {
    appendPath(AVAILABLE_PROVIDERS_PATH_SEGMENT)
    appendPath(UPDATE_PATH_SEGMENT)
}.build()

/**
 * URI for media metadata.
 */
val MEDIA_URI: Uri = pickerUri.buildUpon().apply {
    appendPath(MEDIA_PATH_SEGMENT)
}.build()

/**
 * URI that receives [ContentProvider] change notifications for media updates.
 */
val MEDIA_CHANGE_NOTIFICATION_URI: Uri = pickerUri.buildUpon().apply {
    appendPath(MEDIA_PATH_SEGMENT)
    appendPath(UPDATE_PATH_SEGMENT)
}.build()

