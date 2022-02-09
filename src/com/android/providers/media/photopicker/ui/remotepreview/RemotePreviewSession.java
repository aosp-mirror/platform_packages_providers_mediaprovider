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

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

/**
 * Handles preview of a given media on a {@link Surface}.
 */
final class RemotePreviewSession {

    private static final String TAG = "RemotePreviewSession";

    private final int mSurfaceId;
    private final String mMediaId;
    private final String mAuthority;
    private final SurfaceControllerProxy mSurfaceController;

    private boolean mIsSurfaceCreated = false;
    private boolean mIsPlaying = false;

    RemotePreviewSession(int surfaceId, @NonNull String mediaId, @NonNull String authority,
            @NonNull SurfaceControllerProxy surfaceController) {
        this.mSurfaceId = surfaceId;
        this.mMediaId = mediaId;
        this.mAuthority = authority;
        this.mSurfaceController = surfaceController;
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
            Log.w(TAG, "Surface is not created.");
            return;
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
            Log.w(TAG, "Surface is not created.");
            return;
        }

        try {
            mSurfaceController.onSurfaceChanged(mSurfaceId, format, width, height);
        } catch (RemoteException e) {
            Log.e(TAG, "Failure in onSurfaceChanged().", e);
        }
    }

    void playMedia() {
        // When the user is at the first item in ViewPager, swiping further right trigger the
        // callback {@link ViewPager2.PageTransformer#transforPage(View, int)}, which would call
        // into playMedia again. Hence we want to check is its already playing, before making the
        // call to {@link SurfaceControllerProxy}.
        if (mIsPlaying) {
            return;
        }

        if (!mIsSurfaceCreated) {
            Log.w(TAG, "Surface is not created.");
            return;
        }

        try {
            mSurfaceController.onMediaPlay(mSurfaceId);
            mIsPlaying = false;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to play media.", e);
        }
    }
}
