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
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PlaybackState;
import android.util.Log;
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

    private final int mSurfaceId;
    private final String mMediaId;
    private final String mAuthority;
    private final SurfaceControllerProxy mSurfaceController;
    private final PreviewVideoHolder mPreviewVideoHolder;
    private final MuteStatus mMuteStatus;
    private final PlayerControlsVisibilityStatus mPlayerControlsVisibilityStatus;
    private final AccessibilityManager mAccessibilityManager;
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
            setAudioMuted(newMutedValue);
            mMuteStatus.setVolumeMuted(newMutedValue);
            updateMuteButtonState(mMuteStatus.isVolumeMuted());
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

    private SurfaceChangeData mSurfaceChangeData;
    private boolean mIsSurfaceCreated = false;
    private boolean mIsSurfaceCreationNotified = false;
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

    @NonNull
    PreviewVideoHolder getPreviewVideoHolder() {
        return mPreviewVideoHolder;
    }

    void surfaceCreated() {
        if (mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is already created.");
        }
        if (mIsSurfaceCreationNotified) {
            throw new IllegalStateException(
                    "Surface creation has been already notified to SurfaceController.");
        }

        mIsSurfaceCreated = true;

        // Notify surface creation only if playback has been already requested, else this will be
        // done in requestPlayMedia() when playback is explicitly requested.
        if (mIsPlaybackRequested) {
            notifySurfaceCreated();
        }
    }

    void surfaceChanged(int format, int width, int height) {
        mSurfaceChangeData = new SurfaceChangeData(format, width, height);

        // Notify surface change only if playback has been already requested, else this will be
        // done in requestPlayMedia() when playback is explicitly requested.
        if (mIsPlaybackRequested) {
            notifySurfaceChanged();
        }
    }

    void surfaceDestroyed() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }

        mSurfaceChangeData = null;

        tearDownUI();

        if (!mIsSurfaceCreationNotified) {
            // If we haven't notified surface creation yet, then no need to notify surface
            // destruction either.
            return;
        }

        try {
            mSurfaceController.onSurfaceDestroyed(mSurfaceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in onSurfaceDestroyed().", e);
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

        // Now that playback has been requested, try to notify surface creation and surface change
        // so that player can be prepared with the surface.
        if (mIsSurfaceCreated) {
            notifySurfaceCreated();
        }
        if (mSurfaceChangeData != null) {
            notifySurfaceChanged();
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
                return;
            case PLAYBACK_STATE_PAUSED:
                updatePlayPauseButtonState(false /* isPlaying */);
                return;
            default:
        }
    }

    private void notifySurfaceCreated() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (mIsSurfaceCreationNotified) {
            throw new IllegalStateException(
                    "Surface creation has already been notified to SurfaceController.");
        }

        try {
            mSurfaceController.onSurfaceCreated(mSurfaceId,
                    mPreviewVideoHolder.getSurfaceHolder().getSurface(), mMediaId);
            mIsSurfaceCreationNotified = true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in notifySurfaceCreated().", e);
        }
    }

    private void notifySurfaceChanged() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (!mIsSurfaceCreationNotified) {
            throw new IllegalStateException(
                    "Surface creation has not been notified to SurfaceController.");
        }

        if (mSurfaceChangeData == null) {
            throw new IllegalStateException("No surface change data present.");
        }

        try {
            mSurfaceController.onSurfaceChanged(mSurfaceId, mSurfaceChangeData.getFormat(),
                    mSurfaceChangeData.getWidth(), mSurfaceChangeData.getHeight());
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in notifySurfaceChanged().", e);
        }
    }

    private void playMedia() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (!mIsSurfaceCreationNotified) {
            throw new IllegalStateException(
                    "Surface creation has not been notified to SurfaceController.");
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
        if (!mIsSurfaceCreationNotified) {
            throw new IllegalStateException(
                    "Surface creation has not been notified to SurfaceController.");
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
        // We show the thumbnail view till the player is ready and when we know the
        // media size, then we hide the thumbnail view.
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

    private static final class SurfaceChangeData {

        private int mFormat;
        private int mWidth;
        private int mHeight;

        SurfaceChangeData(int format, int width, int height) {
            mFormat = format;
            mWidth = width;
            mHeight = height;
        }

        int getFormat() {
            return mFormat;
        }

        int getWidth() {
            return mWidth;
        }

        int getHeight() {
            return mHeight;
        }
    }
}
