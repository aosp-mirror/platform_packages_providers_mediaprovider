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

package com.android.photopicker.core.configuration

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.ApplicationMediaCapabilities
import android.net.Uri
import android.os.SystemProperties
import android.provider.MediaStore
import android.util.Log
import com.android.photopicker.core.navigation.PhotopickerDestinations

/** Check system properties to determine if the device is considered debuggable */
private val buildIsDebuggable = SystemProperties.getInt("ro.debuggable", 0) == 1

/** The default selection maximum size if not set by the caller */
const val DEFAULT_SELECTION_LIMIT = 1

/** Enum that describes the current runtime environment of the Photopicker. */
enum class PhotopickerRuntimeEnv {
    ACTIVITY,
    EMBEDDED,
}

/**
 * Data object that represents a possible configuration state of the Photopicker.
 *
 * @property runtimeEnv The current Photopicker runtime environment, this should never be changed
 *   during configuration updates.
 * @property action the [Intent#getAction] that Photopicker is currently serving.
 * @property callingPackage the package name of the caller
 * @property callingPackageUid the uid of the caller
 * @property callingPackageLabel the display label of the caller that can be shown to the user
 * @property callingPackageMediaCapabilities the value of [MediaStore.EXTRA_MEDIA_CAPABILITIES]. If
 *   set, represents the [ApplicationMediaCapabilities] of the calling package.
 * @property accentColor the accent color (if valid) from
 *   [MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR]
 * @property mimeTypes the mimetypes to filter all media requests with for the current session.
 * @property pickImagesInOrder whether to show check marks as ordered number values for selected
 *   media.
 * @property selectionLimit the value of [MediaStore.EXTRA_PICK_IMAGES_MAX] with a default value of
 *   [DEFAULT_SELECTION_LIMIT], and max value of [MediaStore.getPickImagesMaxLimit()] if it was not
 *   set or set to too large a limit.
 * @property startDestination the start destination that should be consider the "home" view the user
 *   is shown for the session.
 * @property preSelectedUris an [ArrayList] of the [Uri]s of the items selected by the user in the
 *   previous photopicker sessions launched via the same calling app.
 * @property flags a snapshot of the relevant flags in [DeviceConfig]. These are not live values.
 * @property deviceIsDebuggable if the device is running a build which has [ro.debuggable == 1]
 * @property intent the [Intent] that Photopicker was launched with. This property is private to
 *   restrict access outside of this class.
 * @property sessionId identifies the current photopicker session
 */
data class PhotopickerConfiguration(
    val runtimeEnv: PhotopickerRuntimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
    val action: String,
    val callingPackage: String? = null,
    val callingPackageUid: Int? = null,
    val callingPackageLabel: String? = null,
    val callingPackageMediaCapabilities: ApplicationMediaCapabilities? = null,
    val accentColor: Long? = null,
    val mimeTypes: ArrayList<String> = arrayListOf("image/*", "video/*"),
    val pickImagesInOrder: Boolean = false,
    val selectionLimit: Int = DEFAULT_SELECTION_LIMIT,
    val startDestination: PhotopickerDestinations = PhotopickerDestinations.DEFAULT,
    val preSelectedUris: ArrayList<Uri>? = null,
    val deviceIsDebuggable: Boolean = buildIsDebuggable,
    val flags: PhotopickerFlags = PhotopickerFlags(),
    val sessionId: Int,
    private val intent: Intent? = null,
) {

    /**
     * Use the internal Intent to see if the Intent can be resolved as a
     * CrossProfileIntentForwarderActivity
     *
     * This method exists to limit the visibility of the intent field, but [UserMonitor] requires
     * the intent to check for CrossProfileIntentForwarder's. Rather than exposing intent as a
     * public field, this method can be called to do the check, if an Intent exists.
     *
     * @return Whether the current Intent Photopicker may be running under has a matching
     *   CrossProfileIntentForwarderActivity
     */
    fun doesCrossProfileIntentForwarderExists(packageManager: PackageManager): Boolean {

        val intentToCheck: Intent? =
            when (runtimeEnv) {
                PhotopickerRuntimeEnv.ACTIVITY ->
                    // clone() returns an object so cast back to an Intent
                    intent?.clone() as? Intent

                // For the EMBEDDED runtime, no intent exists, so generate cross profile forwarding
                // based upon Photopicker's standard api ACTION_PICK_IMAGES
                PhotopickerRuntimeEnv.EMBEDDED -> Intent(MediaStore.ACTION_PICK_IMAGES)
            }

        intentToCheck?.let {
            // Remove specific component / package info from the intent before querying
            // package manager. (This is going to look for all handlers of this intent,
            // and it shouldn't be scoped to a specific component or package)
            it.setComponent(null)
            it.setPackage(null)

            for (info: ResolveInfo? in
                packageManager.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY)) {
                info?.let {
                    if (it.isCrossProfileIntentForwarderActivity()) {
                        // This profile can handle cross profile content
                        // from the current context profile
                        return true
                    }
                }
            }
        }
            // Log a warning that the intent was null, but probably shouldn't have been.
            ?: Log.w(
                ConfigurationManager.TAG,
                "No intent available for checking cross-profile access.",
            )

        return false
    }

    /**
     * Checks if mimeTypes contains only video MIME types and no image MIME types.
     *
     * @return `true` if mimeTypes list contains only video MIME types (starting with "video/") and
     *   no image MIME types (starting with "image/"), `false` otherwise.
     */
    fun hasOnlyVideoMimeTypes(): Boolean {
        return mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith("video/") }
    }
}
