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
package com.android.photopicker.core.embedded

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.SurfaceControlViewHost
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import android.widget.photopicker.EmbeddedPhotoPickerSessionResponse
import android.widget.photopicker.IEmbeddedPhotoPicker
import android.widget.photopicker.IEmbeddedPhotoPickerClient
import androidx.annotation.RequiresApi

/**
 * Implementation class of [IEmbeddedPhotoPicker].
 *
 * Instance of this class is returned as a Binder interface when apps bind to [EmbeddedService].
 * This class invokes the SessionFactory provided by the service and proxies the arguments received
 * from the client app back to to the Service.
 *
 * After a [Session] is ready, this implementation wraps the [Session] and its
 * [SurfaceControlViewHost.SurfacePackage] in a [EmbeddedPhotoPickerSessionResponse] and dispatches
 * it back to the client.
 *
 * @property sessionFactory A factory method for creating [Session]
 * @see EmbeddedService
 * @see Session
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedPhotopickerImpl(
    private val sessionFactory: SessionFactory,
    private val verifyCaller: (packageName: String) -> Boolean,
    // TODO(b/354929684): Replace AIDL implementation with wrapper class.
) : IEmbeddedPhotoPicker.Stub() {

    /**
     * Implementation of [EmbeddedPhotoPickerProvider#openSession] api.
     *
     * This methods requests a new [Session], sends it and the corresponding
     * [SurfaceControlViewHost.SurfacePackage] back to the client.
     *
     * @param packageName Package name of client app
     * @param uid uid of client app
     * @param hostToken Token for setting up [SurfaceControlViewHost] for client
     * @param displayId [Display] id for setting up [SurfaceControlViewHost] for client
     * @param width Width of the embedded photopicker, in pixels
     * @param height Height of the embedded photopicker, in pixels
     * @param featureInfo Required feature info [EmbeddedPhotoPickerFeatureInfo] for given session
     * @param clientCallback Callback to report to client for any events on the session that was
     *   setup
     */
    override fun openSession(
        packageName: String,
        hostToken: IBinder,
        displayId: Int,
        width: Int,
        height: Int,
        featureInfo: EmbeddedPhotoPickerFeatureInfo,
        // TODO(b/354929684): Replace AIDL implementation with wrapper class.
        clientCallback: IEmbeddedPhotoPickerClient,
    ) {
        // Verify that package actually belongs to the caller
        if (!verifyCaller(packageName)) {
            throw SecurityException(
                "Caller does not have permission to openSession " + "for $packageName"
            )
        }

        val session =
            sessionFactory(
                packageName,
                Binder.getCallingUid(),
                hostToken,
                displayId,
                width,
                height,
                featureInfo,
                clientCallback,
            )

        // Notify client about the successful creation of Session & SurfacePackage
        val response = EmbeddedPhotoPickerSessionResponse(session, session.surfacePackage)
        clientCallback.onSessionOpened(response)
    }
}
