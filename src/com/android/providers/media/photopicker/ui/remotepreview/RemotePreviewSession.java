/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.ui.remotepreview;

import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Point;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PlaybackState;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import android.widget.ImageButton;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.ui.PreviewVideoHolder;

/**
 * Handles preview of a given media on a {@link Surface}.
 */
final class RemotePreviewSession {

    private static final String TAG = "RemotePreviewSession";
    private static final long PLAYER_CONTROL_ON_PLAY_TIMEOUT_MS = 1000;
    private static final AudioAttributes sAudioAttributes = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.USAGE_MEDIA)
            .setUsage(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build();


    private final int mSurfaceId;
    private final String mMediaId;
    private final String mAuthority;
    private final SurfaceControllerProxy mSurfaceController;
    private final PreviewVideoHolder mPreviewVideoHolder;
    private final MuteStatus mMuteStatus;
    private final PlayerControlsVisibilityStatus mPlayerControlsVisibilityStatus;
    private final AccessibilityManager mAccessibilityManager;
    private final AudioManager mAudioManager;
    private AudioFocusRequest mAudioFocusRequest = null;
    private final View.OnClickListener mPlayPauseButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mCurrentPlaybackState == PLAYBACK_STATE_STARTED) {
                pauseMedia();
            } else {
                playMedia();
            }
        }
    };
    private final View.OnClickListener mMuteButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            boolean newMutedValue = !mMuteStatus.isVolumeMuted();
            mMuteStatus.setVolumeMuted(newMutedValue);
            handleAudioFocusAndInitVolumeState();
        }
    };
    private final View.OnClickListener mPlayerContainerClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            boolean playerControlsVisible =
                    mPreviewVideoHolder.getPlayerControlsRoot().getVisibility() == View.VISIBLE;
            updatePlayerControlsVisibilityState(!playerControlsVisible);
        }
    };
    private final AccessibilityStateChangeListener mAccessibilityStateChangeListener =
            this::updateAccessibilityState;

    private boolean mIsSurfaceCreated = false;
    private boolean mIsPlaybackRequested = false;
    @PlaybackState
    private int mCurrentPlaybackState = PLAYBACK_STATE_BUFFERING;
    private boolean mIsAccessibilityEnabled;

    RemotePreviewSession(int surfaceId, @NonNull String mediaId, @NonNull String authority,
            @NonNull SurfaceControllerProxy surfaceController,
            @NonNull PreviewVideoHolder previewVideoHolder, @NonNull MuteStatus muteStatus,
            @NonNull PlayerControlsVisibilityStatus playerControlsVisibilityStatus,
            @NonNull Context context) {
        this.mSurfaceId = surfaceId;
        this.mMediaId = mediaId;
        this.mAuthority = authority;
        this.mSurfaceController = surfaceController;
        this.mPreviewVideoHolder = previewVideoHolder;
        this.mMuteStatus = muteStatus;
        this.mPlayerControlsVisibilityStatus = playerControlsVisibilityStatus;
        this.mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        this.mAudioManager = context.getSystemService(AudioManager.class);

        initUI();
    }

    int getSurfaceId() {
        return mSurfaceId;
    }

    @NonNull
    String getMediaId() {
        return mMediaId;
    }

    @NonNull
    String getAuthority() {
        return mAuthority;
    }

    void surfaceCreated(@NonNull Surface surface) {
        if (mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is already created.");
        }

        if (surface == null) {
            throw new IllegalStateException("surfaceCreated() called with null surface.");
        }

        try {
            mSurfaceController.onSurfaceCreated(mSurfaceId, surface, mMediaId);
            mIsSurfaceCreated = true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in onSurfaceCreated().", e);
        }
    }

    void surfaceDestroyed() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }

        tearDownUI();

        try {
            mSurfaceController.onSurfaceDestroyed(mSurfaceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in onSurfaceDestroyed().", e);
        }
    }

    void surfaceChanged(int format, int width, int height) {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }

        try {
            mSurfaceController.onSurfaceChanged(mSurfaceId, format, width, height);
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in onSurfaceChanged().", e);
        }
    }

    void requestPlayMedia() {
        // When the user is at the first item in ViewPager, swiping further right would trigger the
        // callback {@link ViewPager2.PageTransformer#transforPage(View, int)}, which would call
        // into requestPlayMedia again. Hence, we want to check if playback is already requested or
        // if playback is already happening, before proceeding further.
        if (mIsPlaybackRequested || (mCurrentPlaybackState == PLAYBACK_STATE_STARTED)) {
            return;
        }

        if (mCurrentPlaybackState == PLAYBACK_STATE_READY
                || mCurrentPlaybackState == PLAYBACK_STATE_MEDIA_SIZE_CHANGED
                || mCurrentPlaybackState == PLAYBACK_STATE_COMPLETED
                || mCurrentPlaybackState == PLAYBACK_STATE_PAUSED) {
            playMedia();
            return;
        }

        mIsPlaybackRequested = true;
    }

    void setPlaybackState(@PlaybackState int playbackState, @Nullable Bundle playbackStateInfo) {
        mCurrentPlaybackState = playbackState;
        switch (mCurrentPlaybackState) {
            case PLAYBACK_STATE_READY:
                if (mIsPlaybackRequested) {
                    playMedia();
                    mIsPlaybackRequested = false;
                }
                return;
            case PLAYBACK_STATE_MEDIA_SIZE_CHANGED:
                Point size = playbackStateInfo.getParcelable(ContentResolver.EXTRA_SIZE);
                onMediaSizeChanged(size.x, size.y);
                return;
            case PLAYBACK_STATE_STARTED:
                updatePlayPauseButtonState(true /* isPlaying */);
                if (mIsAccessibilityEnabled
                        || mPlayerControlsVisibilityStatus.shouldShowPlayerControls()) {
                    updatePlayerControlsVisibilityState(true /* visible */);
                }
                if (!mIsAccessibilityEnabled) {
                    hidePlayerControlsWithDelay();
                }
                handleAudioFocusAndInitVolumeState();
                return;
            case PLAYBACK_STATE_PAUSED:
                updatePlayPauseButtonState(false /* isPlaying */);
                abandonAudioFocusIfAny();
                return;
            default:
        }
    }

    private void playMedia() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (mCurrentPlaybackState == PLAYBACK_STATE_STARTED) {
            throw new IllegalStateException("Player is already playing.");
        }

        try {
            mSurfaceController.onMediaPlay(mSurfaceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to play media.", e);
        }
    }

    private void pauseMedia() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (mCurrentPlaybackState != PLAYBACK_STATE_STARTED) {
            throw new IllegalStateException("Player is not playing.");
        }

        try {
            mSurfaceController.onMediaPause(mSurfaceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to pause media.", e);
        }
    }

    private void setAudioMuted(boolean isMuted) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, isMuted);
        try {
            mSurfaceController.onConfigChange(bundle);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to perform config change.", e);
        }
    }

    private void onMediaSizeChanged(int width, int height) {
        float aspectRatio = width / (float) height;
        mPreviewVideoHolder.getPlayerFrame().setAspectRatio(aspectRatio);

        // We want to show the player view only when we have the correct aspect ratio.
        mPreviewVideoHolder.getPlayerContainer().setVisibility(View.VISIBLE);
        mPreviewVideoHolder.getThumbnailView().setVisibility(View.GONE);
    }

    private void initUI() {
        // We hide the player view and show the thumbnail till the player is ready and we know the
        // media size. However, since we want the surface to be created, we cannot use View.GONE
        // here.
        mPreviewVideoHolder.getPlayerContainer().setVisibility(View.INVISIBLE);
        mPreviewVideoHolder.getThumbnailView().setVisibility(View.VISIBLE);
        mPreviewVideoHolder.getPlayerControlsRoot().setVisibility(View.GONE);

        updatePlayPauseButtonState(false /* isPlaying */);
        mPreviewVideoHolder.getPlayPauseButton().setOnClickListener(mPlayPauseButtonClickListener);

        updateMuteButtonState(mMuteStatus.isVolumeMuted());
        mPreviewVideoHolder.getMuteButton().setOnClickListener(mMuteButtonClickListener);

        updateAccessibilityState(mAccessibilityManager.isEnabled());
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);
    }

    private void tearDownUI() {
        mAccessibilityManager.removeAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);
        mPreviewVideoHolder.getPlayPauseButton().setOnClickListener(null);
        mPreviewVideoHolder.getMuteButton().setOnClickListener(null);
        mPreviewVideoHolder.getPlayerContainer().setOnClickListener(null);
        abandonAudioFocusIfAny();
    }

    /**
     * Requests AudioFocus if current state of the volume state is volume on. Sets the volume of
     * the playback if the AudioFocus request is granted.
     * Also, updates the mute button based on the state of the muteStatus.
     */
    private void handleAudioFocusAndInitVolumeState() {
        if (mMuteStatus.isVolumeMuted()) {
            setAudioMuted(true);
            abandonAudioFocusIfAny();
        } else if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setAudioMuted(false);
        }

        updateMuteButtonState(mMuteStatus.isVolumeMuted());
    }

    /**
     * Abandons the AudioFocus request so that the previous focus owner can resume their playback
     */
    private void abandonAudioFocusIfAny() {
        if (mAudioFocusRequest == null) return;

        mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
        mAudioFocusRequest = null;
    }

    private int requestAudioFocus() {
        // Always request new AudioFocus
        abandonAudioFocusIfAny();

        mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(sAudioAttributes)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pauseMedia();
                    }
                }).build();

        // We don't need to reset mAudioFocusRequest to null on failure of requestAudioFocus. This
        // is because we always reset the AudioFocus before requesting, reset mechanism will also
        // try to abandon AudioFocus if there is any.
        return mAudioManager.requestAudioFocus(mAudioFocusRequest);
    }

    private void updateAccessibilityState(boolean enabled) {
        mIsAccessibilityEnabled = enabled;
        mPreviewVideoHolder.getPlayerContainer().setOnClickListener(
                mIsAccessibilityEnabled ? null : mPlayerContainerClickListener);
        updatePlayerControlsVisibilityState(mIsAccessibilityEnabled);
    }

    private void updatePlayPauseButtonState(boolean isPlaying) {
        ImageButton playPauseButton = mPreviewVideoHolder.getPlayPauseButton();
        Context context = playPauseButton.getContext();
        playPauseButton.setContentDescription(
                context.getString(
                        isPlaying ? R.string.picker_pause_video : R.string.picker_play_video));
        playPauseButton.setImageResource(
                isPlaying ? R.drawable.ic_preview_pause : R.drawable.ic_preview_play);
    }

    private void updateMuteButtonState(boolean isVolumeMuted) {
        ImageButton muteButton = mPreviewVideoHolder.getMuteButton();
        Context context = muteButton.getContext();
        muteButton.setContentDescription(
                context.getString(
                        isVolumeMuted ? R.string.picker_unmute_video : R.string.picker_mute_video));
        muteButton.setImageResource(
                isVolumeMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
    }

    private void hidePlayerControlsWithDelay() {
        mPreviewVideoHolder.getPlayerControlsRoot().postDelayed(
                () -> updatePlayerControlsVisibilityState(false /* visible */),
                PLAYER_CONTROL_ON_PLAY_TIMEOUT_MS);
    }

    private void updatePlayerControlsVisibilityState(boolean visible) {
        mPreviewVideoHolder.getPlayerControlsRoot().setVisibility(
                visible ? View.VISIBLE : View.GONE);
        mPlayerControlsVisibilityStatus.setShouldShowPlayerControlsForNextItem(visible);
    }
}
