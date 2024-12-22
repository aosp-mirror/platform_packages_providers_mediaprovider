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

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import android.widget.photopicker.IEmbeddedPhotoPickerClient
import androidx.annotation.RequiresApi
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.EmbeddedServiceComponentBuilder
import com.android.providers.media.flags.Flags.enableEmbeddedPhotopicker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * This service is the client's entrypoint into the embedded Photopicker.
 *
 * It is responsible for creating new [Session]s for every EmbeddedPhotopickerProvider#openSession
 * call. The service has one-to-many relationship with client apps and one-to-many relationship with
 * [Session] i.e. multiple client apps can request multiple [Session] through this service.
 *
 * Service returns binder instance of [IEmbeddedPhotoPicker] when bound.
 *
 * NOTE: This service requires the [FLAGS.ENABLE_EMBEDDED_PHOTOPICKER] to be enabled, or onBind
 * requests will be ignored.
 *
 * @see EmbeddedPhotopickerImpl for implementation of [IEmbeddedPhotoPicker]
 * @see Session for implementation of [EmbeddedPhotopickerSession]
 * @see android.provider.EmbeddedPhotoPickerProvider#openSession for api surface
 */
@AndroidEntryPoint(Service::class)
class EmbeddedService : Hilt_EmbeddedService() {

    // A builder that can be used to build a unique set of hilt containers to supply individual
    // dependencies for each embedded photopicker session.
    @Inject lateinit var embeddedServiceComponentBuilder: EmbeddedServiceComponentBuilder

    // A list that keeps track of all sessions created by this instance of the bound
    // EmbeddedService.
    private val allSessions: MutableList<Session> = mutableListOf()

    companion object {
        val TAG: String = "PhotopickerEmbeddedService"
    }

    // The binder object that is sent to all clients that bind this service.
    private val _binder: IBinder? =
        if (SdkLevel.isAtLeastU() && enableEmbeddedPhotopicker()) {
            EmbeddedPhotopickerImpl(
                sessionFactory = ::buildSession,
                verifyCaller = ::verifyCallerIdentity
            )
        } else {
            // Embedded Photopicker is only available on U+ devices when the build flag is enabled.
            // When those conditions aren't meant, this is null to reject any bind requests on
            // devices that aren't at least on SdkLevel U+ with the correct flag settings.
            null
        }

    override fun onBind(intent: Intent?): IBinder? {

        // If _binder is null, the device Sdk is too low, or a required flag was not enabled, and so
        // this session will be ignored.
        if (_binder == null) {
            Log.w(TAG, "onBind was attempted, but EmbeddedPhotopicker is not available.")
        }
        return _binder
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (SdkLevel.isAtLeastU()) {
            cleanupSessions()
        }
    }

    /**
     * Ensures that all sessions opened by this service were closed so that the resources are
     * released.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun cleanupSessions() {
        for (session in allSessions) {
            if (session.isActive) {
                session.close()
            }
        }
    }

    /**
     * The [Session] factory used by the Binder implementation the client receives. After the
     * session is created the service retains a reference to it to ensure resources are closed in
     * the onDestroy.
     *
     * @See Session constructor for parameter details.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun buildSession(
        packageName: String,
        uid: Int,
        hostToken: IBinder,
        displayId: Int,
        width: Int,
        height: Int,
        featureInfo: EmbeddedPhotoPickerFeatureInfo,
        // TODO(b/354929684): Replace AIDL implementations with wrapper classes.
        clientCallback: IEmbeddedPhotoPickerClient,
    ): Session {
        val newSession =
            Session(
                context = this,
                component = embeddedServiceComponentBuilder.build(),
                clientPackageName = packageName,
                clientUid = uid,
                width = width,
                height = height,
                displayId = displayId,
                hostToken = hostToken,
                featureInfo = featureInfo,
                clientCallback = clientCallback,
                grantUriPermission = ::grantUriToClient,
                revokeUriPermission = ::revokeUriToClient,
            )
        allSessions.add(newSession)
        return newSession
    }

    /**
     * Grants [Intent.FLAG_GRANT_READ_URI_PERMISSION] to uri for given client.
     *
     * This happens during selection of new items recorded in [Session.listenForSelectionEvents]
     */
    fun grantUriToClient(clientPackageName: String, uri: Uri): GrantResult {
        try {
            this.grantUriPermission(clientPackageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            return GrantResult.FAILURE
        }

        return GrantResult.SUCCESS
    }

    /**
     * Revokes [Intent.FLAG_GRANT_READ_URI_PERMISSION] to uri for given client.
     *
     * This happens during deselection of items recorded in [Session.listenForSelectionEvents]
     */
    fun revokeUriToClient(clientPackageName: String, uri: Uri): GrantResult {
        try {
            this.revokeUriPermission(clientPackageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            return GrantResult.FAILURE
        }
        return GrantResult.SUCCESS
    }

    /**
     * Enum that denotes if MediaProvider was able to successfully grant uri permission to a given
     * package or not.
     */
    enum class GrantResult {
        SUCCESS,
        FAILURE
    }

    /** Verify that package belongs to caller by mapping their uids */
    private fun verifyCallerIdentity(packageName: String): Boolean {
        val packageUid = getPackageUid(packageName)
        return packageUid == Binder.getCallingUid()
    }

    private fun getPackageUid(packageName: String): Int {
        try {
            return this.getPackageManager().getPackageUid(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return -1
        }
    }
}
