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

import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_PERMANENT_FAILURE;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED;
import static android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.graphics.Point;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PlaybackState;
import android.util.Log;
import android.view.Surface;
import android.view.View;

import com.android.providers.media.photopicker.ui.PreviewVideoHolder;

/**
 * Handles preview of a given media on a {@link Surface}.
 */
final class RemotePreviewSession {

    private static final String TAG = "RemotePreviewSession";

    private final int mSurfaceId;
    private final String mMediaId;
    private final String mAuthority;
    private final SurfaceControllerProxy mSurfaceController;
    private final PreviewVideoHolder mPreviewVideoHolder;

    private boolean mIsSurfaceCreated = false;
    private boolean mIsPlaying = false;
    private boolean mIsPlaybackRequested = false;
    private boolean mIsPlayerReady = false;

    RemotePreviewSession(int surfaceId, @NonNull String mediaId, @NonNull String authority,
            @NonNull SurfaceControllerProxy surfaceController,
            @NonNull PreviewVideoHolder previewVideoHolder) {
        this.mSurfaceId = surfaceId;
        this.mMediaId = mediaId;
        this.mAuthority = authority;
        this.mSurfaceController = surfaceController;
        this.mPreviewVideoHolder = previewVideoHolder;
        // We hide the player view till the player is ready. However, since we want the surface to
        // be created, we cannot use View.GONE here.
        mPreviewVideoHolder.getPlayerContainer().setVisibility(View.INVISIBLE);
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

        try {
            mSurfaceController.onSurfaceDestroyed(mSurfaceId);
            mIsPlaying = false;
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
        // When the user is at the first item in ViewPager, swiping further right trigger the
        // callback {@link ViewPager2.PageTransformer#transforPage(View, int)}, which would call
        // into requestPlayMedia again. Hence, we want to check is its already playing, before
        // proceeding further.
        if (mIsPlaying) {
            return;
        }

        if (mIsPlayerReady) {
            playMedia();
            return;
        }

        mIsPlaybackRequested = true;
    }

    void setPlaybackState(@PlaybackState int playbackState, @Nullable Bundle playbackStateInfo) {
        switch (playbackState) {
            case PLAYBACK_STATE_READY:
                mIsPlayerReady = true;

                if (mIsPlaybackRequested) {
                    playMedia();
                    mIsPlaybackRequested = false;
                }
                return;
            case PLAYBACK_STATE_ERROR_PERMANENT_FAILURE:
            case PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE:
                mIsPlayerReady = false;
                return;
            case PLAYBACK_STATE_PAUSED:
                mIsPlaying = false;
                return;
            case PLAYBACK_STATE_MEDIA_SIZE_CHANGED:
                Point size = playbackStateInfo.getParcelable(ContentResolver.EXTRA_SIZE);
                updateAspectRatio(size.x, size.y);
                return;
            default:
        }
    }

    private void playMedia() {
        if (!mIsSurfaceCreated) {
            throw new IllegalStateException("Surface is not created.");
        }
        if (mIsPlaying) {
            throw new IllegalStateException("Player is already playing.");
        }

        mPreviewVideoHolder.getPlayerContainer().setVisibility(View.VISIBLE);
        mPreviewVideoHolder.getThumbnailView().setVisibility(View.GONE);

        try {
            mSurfaceController.onMediaPlay(mSurfaceId);
            mIsPlaying = true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to play media.", e);
        }
    }

    private void updateAspectRatio(int width, int height) {
        float aspectRatio = width / (float) height;
        mPreviewVideoHolder.getPlayerContainer().setAspectRatio(aspectRatio);
    }
}
