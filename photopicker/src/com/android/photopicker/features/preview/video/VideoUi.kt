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

import android.content.ContentResolver.EXTRA_SIZE
import android.graphics.Point
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.RemoteException
import android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import androidx.hilt.navigation.compose.hiltViewModel
import com.android.photopicker.R
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.requireSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter

/** [AudioAttributes] to use with all VideoUi instances. */
private val AUDIO_ATTRIBUTES =
    AudioAttributes.Builder()
        .apply {
            setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            setUsage(AudioAttributes.USAGE_MEDIA)
        }
        .build()

/** The size of the Play/Pause button in the center of the video controls */
private val MEASUREMENT_PLAY_PAUSE_ICON_SIZE = 48.dp

/** Padding between the edge of the screen and the Player controls box. */
private val MEASUREMENT_PLAYER_CONTROLS_PADDING_HORIZONTAL = 8.dp
private val MEASUREMENT_PLAYER_CONTROLS_PADDING_VERTICAL = 128.dp

/** Padding between the bottom edge of the screen and the snackbars */
private val MEASUREMENT_SNACKBAR_BOTTOM_PADDING = 48.dp

/** Delay in milliseconds before the player controls are faded. */
private val TIME_MS_PLAYER_CONTROLS_FADE_DELAY = 3000L

/**
 * Builds a remote video player surface and handles the interactions with the
 * [RemoteSurfaceController] for remote video playback.
 *
 * This composable is the entry point into creating a remote player for Photopicker video sources.
 * It utilizes the remote preview functionality of [CloudMediaProvider] to expose a [Surface] to a
 * remote process.
 *
 * @param video The video to prepare and play
 * @param audioIsMuted a preview session-global of the audio mute state
 * @param onRequestAudioMuteChange a callback to request a switch of the [audioIsMuted] state
 * @param viewModel The current instance of the [PreviewViewModel], injected by hilt.
 */
@Composable
fun VideoUi(
    video: Media.Video,
    audioIsMuted: Boolean,
    onRequestAudioMuteChange: (Boolean) -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
) {

    /**
     * The controller is remembered based on the authority so it is efficiently re-used for videos
     * from the same authority. The view model also caches surface controllers to avoid re-creating
     * them.
     */
    val controller =
        remember(video.authority) { viewModel.getControllerForAuthority(video.authority) }

    /** Obtain a surfaceId which will identify this VideoUi's surface to the remote player. */
    val surfaceId = remember(video) { controller.getNextSurfaceId() }

    /** The visibility of the player controls for this video */
    var areControlsVisible by remember { mutableStateOf(false) }

    /** If the underlying video surface has been created */
    var surfaceCreated by remember(video) { mutableStateOf(false) }

    /** Whether the [RetriableErrorDialog] is visible. */
    var showErrorDialog by remember { mutableStateOf(false) }

    /** SnackbarHost api for launching Snackbars */
    val snackbarHostState = remember { SnackbarHostState() }

    /** Producer for [PlaybackInfo] for the current video surface */
    val playbackInfo by producePlaybackInfo(surfaceId, video)

    /** Producer for AspectRatio for the current video surface */
    val aspectRatio by produceAspectRatio(surfaceId, video)

    val context = LocalContext.current

    /** Run these effects when a new PlaybackInfo is received */
    LaunchedEffect(playbackInfo) {
        when (playbackInfo.state) {
            PlaybackState.READY -> {
                // When the controller indicates the video is ready to be played,
                // immediately request for it to begin playing.
                controller.onMediaPlay(surfaceId)
            }
            PlaybackState.STARTED -> {
                // When playback starts, show the controls to the user.
                areControlsVisible = true
            }
            PlaybackState.ERROR_RETRIABLE_FAILURE -> {
                // The remote player has indicated a retriable failure, so show the
                // error dialog.
                showErrorDialog = true
            }
            PlaybackState.ERROR_PERMANENT_FAILURE -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.photopicker_preview_video_error_snackbar)
                )
            }
            else -> {}
        }
    }

    // Acquire audio focus for the player, and establish a callback to change audio mute status.
    val onAudioMuteToggle =
        rememberAudioFocus(
            video,
            surfaceCreated,
            audioIsMuted,
            onFocusLost = {
                try {
                    controller.onMediaPause(surfaceId)
                } catch (e: RemoteException) {
                    Log.d(PreviewFeature.TAG, "Failed to pause media when audio focus was lost.")
                }
            },
            onConfigChangeRequested = { bundle -> controller.onConfigChange(bundle) },
            onRequestAudioMuteChange = onRequestAudioMuteChange,
        )

    // Finally! Now the actual VideoPlayer can be created! \0/
    // This is the top level box of the player, and all of its children are drawn on-top
    // of each other.
    Box {
        VideoPlayer(
            aspectRatio = aspectRatio,
            playbackInfo = playbackInfo,
            muteAudio = audioIsMuted,
            areControlsVisible = areControlsVisible,
            onPlayPause = {
                when (playbackInfo.state) {
                    PlaybackState.STARTED -> controller.onMediaPause(surfaceId)
                    PlaybackState.PAUSED -> controller.onMediaPlay(surfaceId)
                    else -> {}
                }
            },
            onToggleAudioMute = { onAudioMuteToggle(audioIsMuted) },
            onTogglePlayerControls = { areControlsVisible = !areControlsVisible },
            onSurfaceCreated = { surface ->
                controller.onSurfaceCreated(surfaceId, surface, video.mediaId)
                surfaceCreated = true
            },
            onSurfaceChanged = { format, width, height ->
                controller.onSurfaceChanged(surfaceId, format, width, height)
            },
            onSurfaceDestroyed = { controller.onSurfaceDestroyed(surfaceId) },
        )

        // Photopicker is (generally) inside of a BottomSheet, and the preview route is inside a
        // dialog, so this requires a custom [SnackbarHost] to draw on top of those elements that do
        // not play nicely with snackbars. Peace was never an option.
        SnackbarHost(
            snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = MEASUREMENT_SNACKBAR_BOTTOM_PADDING)
        )
    }

    // If the Error dialog is needed, launch the dialog.
    if (showErrorDialog) {
        RetriableErrorDialog(
            onDismissRequest = { showErrorDialog = false },
            onRetry = {
                showErrorDialog = !showErrorDialog
                controller.onMediaPlay(surfaceId)
            },
        )
    }
}

/**
 * Composable that creates the video SurfaceView and player controls. The VideoPlayer itself is
 * stateless, and handles showing loading indicators and player controls when requested by the
 * parent.
 *
 * It hoists a number of events for the parent to handle:
 * - Button/UI touch interactions
 * - the underlying video surface's lifecycle events.
 *
 * @param aspectRatio the aspectRatio of the video to be played. (Null until it is known)
 * @param playbackInfo the current PlaybackState from the remote controller
 * @param muteAudio if the audio is currently muted
 * @param areControlsVisible if the controls are currently visible
 * @param onPlayPause Callback for the Play/Pause button
 * @param onToggleAudioMute Callback for the Audio mute/unmute button
 * @param onTogglePlayerControls Callback for toggling the player controls visibility
 * @param onSurfaceCreated Callback for the underlying [SurfaceView] lifecycle
 * @param onSurfaceChanged Callback for the underlying [SurfaceView] lifecycle
 * @param onSurfaceDestroyed Callback for the underlying [SurfaceView] lifecycle
 */
@Composable
private fun VideoPlayer(
    aspectRatio: Float?,
    playbackInfo: PlaybackInfo,
    muteAudio: Boolean,
    areControlsVisible: Boolean,
    onPlayPause: () -> Unit,
    onToggleAudioMute: () -> Unit,
    onTogglePlayerControls: () -> Unit,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceChanged: (format: Int, width: Int, height: Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {

    // Clicking anywhere on the player should toggle the visibility of the controls.
    Box(Modifier.fillMaxSize().clickable { onTogglePlayerControls() }) {
        val modifier =
            if (aspectRatio != null) Modifier.aspectRatio(aspectRatio).align(Alignment.Center)
            else Modifier.align(Alignment.Center)
        VideoSurfaceView(
            modifier = modifier,
            playerSizeSet = aspectRatio != null,
            onSurfaceCreated = onSurfaceCreated,
            onSurfaceChanged = onSurfaceChanged,
            onSurfaceDestroyed = onSurfaceDestroyed,
        )

        // Auto hides the controls after the delay has passed (if they are still visible).
        LaunchedEffect(areControlsVisible) {
            if (areControlsVisible) {
                delay(TIME_MS_PLAYER_CONTROLS_FADE_DELAY)
                onTogglePlayerControls()
            }
        }

        // Overlay the playback controls
        VideoPlayerControls(
            visible = areControlsVisible,
            currentPlaybackState = playbackInfo.state,
            onPlayPauseClicked = onPlayPause,
            audioIsMuted = muteAudio,
            onToggleAudioMute = onToggleAudioMute,
        )

        Box(Modifier.fillMaxSize()) {
            /** Conditional UI based on the current [PlaybackInfo] */
            when (playbackInfo.state) {
                PlaybackState.UNKNOWN,
                PlaybackState.BUFFERING -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                else -> {}
            }
        }
    }
}

/**
 * Composes a [SurfaceView] for remote video rendering via the [CloudMediaProvider]'s remote video
 * preview Binder process.
 *
 * The [SurfaceView] itself is wrapped inside of a compose interop [AndroidView] which wraps a
 * [FrameLayout] for managing visibility, and then the [SurfaceView] itself. The SurfaceView
 * attaches its own [SurfaceHolder.Callback] and hoists those events out of this composable for the
 * parent to handle.
 *
 * @param modifier A modifier which can be used to position the SurfaceView inside of the parent.
 * @param playerSizeSet Indicates the aspectRatio and size of the surface has been set by the
 *   parent.
 * @param onSurfaceCreated Surface lifecycle callback when the underlying surface has been created.
 * @param onSurfaceChanged Surface lifecycle callback when the underlying surface has been changed.
 * @param onSurfaceDestroyed Surface lifecycle callback when the underlying surface has been
 *   destroyed.
 */
@Composable
private fun VideoSurfaceView(
    modifier: Modifier = Modifier,
    playerSizeSet: Boolean,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceChanged: (format: Int, width: Int, height: Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {

    /**
     * [SurfaceView] is not available in compose, however the remote video preview with the cloud
     * provider requires a [Surface] object passed via Binder.
     *
     * The SurfaceView is instead wrapped in this [AndroidView] compose inter-op and behaves like a
     * normal SurfaceView.
     */
    AndroidView(
        /** Factory is called once on first compose, and never again */
        modifier = modifier,
        factory = { context ->

            // The [FrameLayout] will manage sizing the SurfaceView since it uses a LayoutParam of
            // [MATCH_PARENT] by default, it doesn't need to be explicitly set.
            FrameLayout(context).apply {

                // Add a child view to the FrameLayout which is the [SurfaceView] itself.
                addView(
                    SurfaceView(context).apply {
                        /**
                         * The SurfaceHolder callback is held by the SurfaceView itself, and is
                         * directly attached to this view's SurfaceHolder, so that each SurfaceView
                         * has its own SurfaceHolder.Callback associated with it.
                         */
                        val surfaceCallback =
                            object : SurfaceHolder.Callback {

                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceCreated(holder.getSurface())
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    onSurfaceChanged(format, width, height)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            }

                        // Ensure the SurfaceView never draws outside of its parent's bounds.
                        setClipToOutline(true)

                        getHolder().addCallback(surfaceCallback)
                    }
                )

                // Initially hide the view until there is a aspect ratio set to avoid any visual
                // snapping to position.
                setVisibility(View.INVISIBLE)
            }
        },
        update = { view ->
            // Once the parent has indicated the size has been set, make the player visible.
            if (playerSizeSet) {
                view.setVisibility(View.VISIBLE)
            }
        },
    )
}

/**
 * Composable which generates the Video controls UI and handles displaying / fading the controls
 * when the visibility changes.
 *
 * @param visible Whether the controls are currently visible.
 * @param currentPlaybackState the current [PlaybackInfo] of the player.
 * @param onPlayPauseClicked Click handler for the Play/Pause button
 * @param audioIsMuted The current audio mute state (true if muted)
 * @param onToggleAudioMute Click handler for the audio mute button.
 */
@Composable
private fun VideoPlayerControls(
    visible: Boolean,
    currentPlaybackState: PlaybackState,
    onPlayPauseClicked: () -> Unit,
    audioIsMuted: Boolean,
    onToggleAudioMute: () -> Unit,
) {

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // Box to draw everything on top of the video surface which is underneath.
        Box(
            Modifier.padding(
                vertical = MEASUREMENT_PLAYER_CONTROLS_PADDING_VERTICAL,
                horizontal = MEASUREMENT_PLAYER_CONTROLS_PADDING_HORIZONTAL
            )
        ) {
            // Play / Pause button (center of the screen)
            FilledTonalIconButton(
                modifier = Modifier.align(Alignment.Center).size(MEASUREMENT_PLAY_PAUSE_ICON_SIZE),
                onClick = { onPlayPauseClicked() },
            ) {
                when (currentPlaybackState) {
                    PlaybackState.STARTED ->
                        Icon(
                            Icons.Filled.PauseCircle,
                            contentDescription =
                                stringResource(R.string.photopicker_video_pause_button_description),
                            modifier = Modifier.size(MEASUREMENT_PLAY_PAUSE_ICON_SIZE)
                        )
                    else ->
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription =
                                stringResource(R.string.photopicker_video_play_button_description),
                            modifier = Modifier.size(MEASUREMENT_PLAY_PAUSE_ICON_SIZE)
                        )
                }
            }

            // Mute / UnMute button (bottom right for LTR layouts)
            FilledTonalIconButton(
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = onToggleAudioMute,
            ) {
                when (audioIsMuted) {
                    false ->
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription =
                                stringResource(R.string.photopicker_video_mute_button_description)
                        )
                    true ->
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription =
                                stringResource(R.string.photopicker_video_unmute_button_description)
                        )
                }
            }
        }
    }
}

/**
 * Acquire and remember the audio focus for the current composable context.
 *
 * This composable encapsulates all of the audio focus / abandon focus logic for the VideoUi. Focus
 * is managed via [AudioManager] and this composable will react to changes to [audioIsMuted] and
 * request (in the event video players have switched) / or abandon focus accordingly.
 *
 * @param video The current video being played
 * @param surfaceCreated If the video surface has been created
 * @param audioIsMuted if the audio is currently muted
 * @param onFocusLost Callback for when the AudioManager informs the audioListener that focus has
 *   been lost.
 * @param onConfigChangeRequested Callback for when the controller's configuration needs to be
 *   updated
 * @param onRequestAudioMuteChange Callback to request audio mute state change
 * @return Additionally, return a function which should be called to toggle the current audio mute
 *   status of the player. Utilizing the provided callbacks to update the controller configuration,
 *   this ensures the correct requests are sent to [AudioManager] before the players are unmuted /
 *   muted.
 */
@Composable
private fun rememberAudioFocus(
    video: Media.Video,
    surfaceCreated: Boolean,
    audioIsMuted: Boolean,
    onFocusLost: () -> Unit,
    onConfigChangeRequested: (Bundle) -> Unit,
    onRequestAudioMuteChange: (Boolean) -> Unit,
): (Boolean) -> Unit {

    val context = LocalContext.current
    val audioManager: AudioManager = remember { context.requireSystemService() }

    /** [OnAudioFocusChangeListener] unique to this remote player (authority based) */
    val audioListener =
        remember(video.authority) {
            object : AudioManager.OnAudioFocusChangeListener {
                override fun onAudioFocusChange(focusChange: Int) {
                    if (
                        focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        onFocusLost()
                    }
                }
            }
        }

    /** [AudioFocusRequest] unique to this remote player (authority based) */
    val audioRequest =
        remember(video.authority) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .apply {
                    setAudioAttributes(AUDIO_ATTRIBUTES)
                    setWillPauseWhenDucked(true)
                    setAcceptsDelayedFocusGain(true)
                    setOnAudioFocusChangeListener(audioListener)
                }
                .build()
        }

    // Wait for the video surface to be created before setting up audio focus for the player.
    // This is required because the Player may not exist yet if this is the first / only active
    // surface for this controller.
    if (surfaceCreated) {

        // A DisposableEffect is needed here to ensure the audio focus is abandoned
        // when this composable leaves the view. Otherwise, AudioManager will continue
        // to make calls to the callback which can potentially cause runtime errors,
        // and audio may continue to play until the underlying video surface gets
        // destroyed.
        DisposableEffect(video.authority) {

            // Additionally, any time the current video's authority is different from the
            // last compose, set the audio state on the current controller to match the
            // session's audio state.
            val bundle =
                when (audioIsMuted) {
                    true -> bundleOf(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to true)
                    false -> bundleOf(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to false)
                }
            onConfigChangeRequested(bundle)

            // If the audio currently isn't muted, then request audio focus again with the new
            // request to ensure callbacks are received.
            if (!audioIsMuted) {
                audioManager.requestAudioFocus(audioRequest)
            }

            // When the composable leaves the tree, cleanup the audio request to prevent any
            // audio from playing while the screen isn't being shown to the user.
            onDispose {
                Log.d(PreviewFeature.TAG, "Abandoning audio focus for authority $video.authority")
                audioManager.abandonAudioFocusRequest(audioRequest)
            }
        }
    }

    /** Return a function that can be used to toggle the mute status of the composable */
    return { currentlyMuted: Boolean ->
        when (currentlyMuted) {
            true -> {
                if (
                    audioManager.requestAudioFocus(audioRequest) ==
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                ) {
                    Log.d(PreviewFeature.TAG, "Acquired audio focus to unmute player")
                    val bundle = bundleOf(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to false)
                    onConfigChangeRequested(bundle)
                    onRequestAudioMuteChange(false)
                }
            }
            false -> {
                Log.d(PreviewFeature.TAG, "Abandoning audio focus and muting player")
                audioManager.abandonAudioFocusRequest(audioRequest)
                val bundle = bundleOf(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED to true)
                onConfigChangeRequested(bundle)
                onRequestAudioMuteChange(true)
            }
        }
    }
}

/**
 * State produce for a video's [PlaybackInfo].
 *
 * This producer listens to all [PlaybackState] updates for the given video and surface, and
 * produces the most recent update as observable composable [State].
 *
 * @param surfaceId the id of the player's surface.
 * @param video the video to calculate the aspect ratio for. @viewModel an instance of
 *   [PreviewViewModel], this is injected by hilt.
 * @return observable composable state object that yields the most recent [PlaybackInfo].
 */
@Composable
private fun producePlaybackInfo(
    surfaceId: Int,
    video: Media.Video,
    viewModel: PreviewViewModel = hiltViewModel()
): State<PlaybackInfo> {

    return produceState<PlaybackInfo>(
        initialValue =
            PlaybackInfo(
                state = PlaybackState.UNKNOWN,
                surfaceId,
                authority = video.authority,
            ),
        surfaceId,
        video
    ) {
        viewModel.getPlaybackInfoForPlayer(surfaceId, video).collect { playbackInfo ->
            Log.d(PreviewFeature.TAG, "PlaybackState change received: $playbackInfo")
            value = playbackInfo
        }
    }
}

/**
 * State producer for a video's AspectRatio.
 *
 * This producer listens to the controller's [PlaybackState] flow and extracts any
 * [MEDIA_SIZE_CHANGED] events for the given surfaceId and video and produces the correct aspect
 * ratio for the video as composable [State]
 *
 * @param surfaceId the id of the player's surface.
 * @param video the video to calculate the aspect ratio for. @viewModel an instance of
 *   [PreviewViewModel], this is injected by hilt.
 * @return observable composable state object that yields the correct AspectRatio
 */
@Composable
private fun produceAspectRatio(
    surfaceId: Int,
    video: Media.Video,
    viewModel: PreviewViewModel = hiltViewModel()
): State<Float?> {

    return produceState<Float?>(
        initialValue = null,
        surfaceId,
        video,
    ) {
        viewModel
            .getPlaybackInfoForPlayer(surfaceId, video)
            .filter { it.state == PlaybackState.MEDIA_SIZE_CHANGED }
            .collect { playbackInfo ->
                val size: Point? =
                    playbackInfo.playbackStateInfo?.getParcelable(EXTRA_SIZE, Point::class.java)
                size?.let {
                    // AspectRatio = Width divided by height as a float
                    Log.d(PreviewFeature.TAG, "Media Size change received: ${size.x} x ${size.y}")
                    value = size.x.toFloat() / size.y.toFloat()
                }
            }
    }
}
