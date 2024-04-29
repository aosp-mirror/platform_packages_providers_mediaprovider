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

package com.android.photopicker.features.preview

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK
import android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER
import android.provider.ICloudMediaSurfaceController
import android.provider.ICloudMediaSurfaceStateChangedCallback
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.model.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The view model for the Preview routes.
 *
 * This view model manages snapshots of the session's selection so that items can observe a slice of
 * state rather than the mutable selection state.
 *
 * Additionally, [RemoteSurfaceController] are created and held for re-use in the scope of this view
 * model. The view model handles the [ICloudMediaSurfaceStateChangedCallback] for each controller,
 * and stores the information for the UI to obtain via exported flows.
 */
@HiltViewModel
class PreviewViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    private val selection: Selection<Media>,
    private val userMonitor: UserMonitor,
) : ViewModel() {

    companion object {
        val TAG: String = PreviewFeature.TAG

        // These are the authority strings for [CloudMediaProvider]-s for local on device files.
        private val PHOTOPICKER_PROVIDER_AUTHORITY = "com.android.providers.media.photopicker"
        private val REMOTE_PREVIEW_PROVIDER_AUTHORITY =
            "com.android.providers.media.remote_video_preview"
    }

    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope =
        if (scopeOverride == null) {
            this.viewModelScope
        } else {
            scopeOverride
        }

    /**
     * A flow which exposes a snapshot of the selection. Initially this is an empty set and will not
     * automatically update with the current selection, snapshots must be explicitly requested.
     */
    val selectionSnapshot = MutableStateFlow<Set<Media>>(emptySet())

    /** Trigger a new snapshot of the selection. */
    fun takeNewSelectionSnapshot() {
        scope.launch { selectionSnapshot.update { selection.snapshot() } }
    }

    /**
     * Toggle the media item into the current session's selection.
     *
     * @param media
     */
    fun toggleInSelection(media: Media) {
        scope.launch { selection.toggle(media) }
    }

    /**
     * Holds any cached [RemotePreviewControllerInfo] to avoid re-creating
     * [RemoteSurfaceController]-s that already exist during a preview session.
     */
    val controllers: HashMap<String, RemotePreviewControllerInfo> = HashMap()

    /**
     * A flow that all [ICloudMediaSurfaceStateChangedCallback] push their [setPlaybackState]
     * updates to. This flow is later filtered to a specific (authority + surfaceId) pairing for
     * providing the playback state updates to the UI composables to collect.
     *
     * A shared flow is used here to ensure that all emissions are delivered since a StateFlow will
     * conflate deliveries to slow receivers (sometimes the UI is slow to pull emissions) to this
     * flow since they happen in quick succession, and this will avoid dropping any.
     *
     * See [getPlaybackInfoForPlayer] where this flow is filtered.
     */
    private val _playbackInfo = MutableSharedFlow<PlaybackInfo>()

    /**
     * Creates a [Flow<PlaybackInfo>] for the provided player configuration. This just siphons the
     * larger [playbackInfo] flow that all of the [ICloudMediaSurfaceStateChangedCallback]-s push
     * their updates to.
     *
     * The larger flow is filtered for updates related to the requested video session. (surfaceId +
     * authority)
     */
    fun getPlaybackInfoForPlayer(surfaceId: Int, video: Media.Video): Flow<PlaybackInfo> {
        return _playbackInfo.filter { it.surfaceId == surfaceId && it.authority == video.authority }
    }

    /** @return the active user's [ContentResolver]. */
    fun getContentResolverForCurrentUser(): ContentResolver {
        return userMonitor.userStatus.value.activeContentResolver
    }

    /**
     * Obtains an instance of [RemoteSurfaceController] for the requested authority. Attempts to
     * re-use any controllers that have previously been fetched, and additionally, generates a
     * [RemotePreviewControllerInfo] for the requested authority and holds it in [controllers] for
     * future re-use.
     *
     * @return A [RemoteSurfaceController] for [authority]
     */
    fun getControllerForAuthority(
        authority: String,
    ): RemoteSurfaceController {

        if (controllers.containsKey(authority)) {
            Log.d(TAG, "Existing controller found, re-using for $authority")
            return controllers.getValue(authority).controller
        }

        Log.d(TAG, "Creating controller for authority: $authority")

        val callback = buildSurfaceStateChangedCallback(authority)

        // For local photos which use the PhotopickerProvider, the remote video preview
        // functionality is actually delegated to the mediaprovider:Photopicker process
        // and is run out of the RemoteVideoPreviewProvider, so for the purposes of
        // acquiring a [ContentProviderClient], use a different authority.
        val clientAuthority =
            when (authority) {
                PHOTOPICKER_PROVIDER_AUTHORITY -> REMOTE_PREVIEW_PROVIDER_AUTHORITY
                else -> authority
            }

        // Acquire a [ContentProviderClient] that can be retained as long as the [PreviewViewModel]
        // is active. This creates a binding between the current process that is running Photopicker
        // and the remote process that is rendering video and prevents the remote process from being
        // killed by the OS. This client is held onto until the [PreviewViewModel] is cleared when
        // the Preview route is navigated away from. (The PreviewViewModel is bound to the
        // navigation backStackEntry).
        val remoteClient =
            getContentResolverForCurrentUser().acquireContentProviderClient(clientAuthority)
        // TODO: b/323833427 Navigate back to the main grid when a controller cannot be obtained.
        checkNotNull(remoteClient) { "Unable to get a client for $clientAuthority" }

        // Don't reuse the remote client from above since it may not be the right provider for
        // local files. Instead, assemble a new URI, and call the correct provider via
        // [ContentResolver#call]
        val uri: Uri =
            Uri.Builder()
                .apply {
                    scheme(ContentResolver.SCHEME_CONTENT)
                    authority(authority)
                }
                .build()

        val extras =
            bundleOf(
                EXTRA_LOOPING_PLAYBACK_ENABLED to true,
                EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to true,
                EXTRA_SURFACE_STATE_CALLBACK to callback
            )

        val controllerBundle: Bundle? =
            getContentResolverForCurrentUser()
                .call(
                    /*uri=*/ uri,
                    /*method=*/ METHOD_CREATE_SURFACE_CONTROLLER,
                    /*arg=*/ null,
                    /*extras=*/ extras,
                )
        checkNotNull(controllerBundle) { "No controller was returned for RemoteVideoPreview" }

        val binder = controllerBundle.getBinder(EXTRA_SURFACE_CONTROLLER)

        // Produce the [RemotePreviewControllerInfo] and save it for future re-use.
        val controllerInfo =
            RemotePreviewControllerInfo(
                authority = authority,
                client = remoteClient,
                controller =
                    RemoteSurfaceController(ICloudMediaSurfaceController.Stub.asInterface(binder)),
            )
        controllers.put(authority, controllerInfo)

        return controllerInfo.controller
    }

    /**
     * When this ViewModel is cleared, close any held [ContentProviderClient]s that are retained for
     * video rendering.
     */
    override fun onCleared() {
        // When the view model is cleared then it is safe to assume the preview route is no longer
        // active, and any [ContentProviderClient] that are being held to support remote video
        // preview can now be closed.
        for ((_, controllerInfo) in controllers) {

            try {
                controllerInfo.controller.onDestroy()
            } catch (e: RemoteException) {
                Log.d(TAG, "Failed to destroy surface controller.", e)
            }

            controllerInfo.client.close()
        }
    }

    /**
     * Constructs a [ICloudMediaSurfaceStateChangedCallback] for the provided authority.
     *
     * @param authority The authority this callback will assign to its PlaybackInfo emissions.
     * @return A [ICloudMediaSurfaceStateChangedCallback] bound to the provided authority.
     */
    private fun buildSurfaceStateChangedCallback(
        authority: String
    ): ICloudMediaSurfaceStateChangedCallback.Stub {
        return object : ICloudMediaSurfaceStateChangedCallback.Stub() {
                override fun setPlaybackState(
                    surfaceId: Int,
                    playbackState: Int,
                    playbackStateInfo: Bundle?
                ) {
                    scope.launch {
                        _playbackInfo.emit(
                            PlaybackInfo(
                                state = PlaybackState.fromStateInt(playbackState),
                                surfaceId = surfaceId,
                                authority = authority,
                                playbackStateInfo = playbackStateInfo,
                            )
                        )
                    }
                }
            }
    }
}
