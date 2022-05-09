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

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ICloudMediaSurfaceController;
import android.view.Surface;

/**
 * Wrapper over {@link ICloudMediaSurfaceController} which manages player creation/release based on
 * presence of active surfaces.
 *
 * <p>This class is not thread-safe and the methods are meant to be always called on the main
 * thread.
 * TODO(b/216414060): Handle binder call failures and propagate to the caller.
 */
final class SurfaceControllerProxy {

    private final ICloudMediaSurfaceController mController;

    private int mNumActiveSurfaces = 0;

    SurfaceControllerProxy(@NonNull ICloudMediaSurfaceController controller) {
        mController = controller;
    }

    void onSurfaceCreated(int surfaceId, @NonNull Surface surface,
            @NonNull String mediaId) throws RemoteException {
        if (mNumActiveSurfaces == 0) {
            onPlayerCreate();
        }

        mNumActiveSurfaces++;
        mController.onSurfaceCreated(surfaceId, surface, mediaId);
    }

    void onSurfaceChanged(int surfaceId, int format, int width, int height) throws RemoteException {
        mController.onSurfaceChanged(surfaceId, format, width, height);
    }

    void onSurfaceDestroyed(int surfaceId) throws RemoteException {
        mController.onSurfaceDestroyed(surfaceId);
        mNumActiveSurfaces--;

        if (mNumActiveSurfaces == 0) {
            onPlayerRelease();
        }
    }

    void onMediaPlay(int surfaceId) throws RemoteException {
        mController.onMediaPlay(surfaceId);
    }

    void onMediaPause(int surfaceId) throws RemoteException {
        mController.onMediaPause(surfaceId);
    }

    void onMediaSeekTo(int surfaceId, @DurationMillisLong long timestampMillis)
            throws RemoteException {
        mController.onMediaSeekTo(surfaceId, timestampMillis);
    }

    void onConfigChange(@NonNull Bundle bundle) throws RemoteException {
        mController.onConfigChange(bundle);
    }

    void onDestroy() throws RemoteException {
        mController.onDestroy();
    }

    private void onPlayerCreate() throws RemoteException {
        mController.onPlayerCreate();
    }

    private void onPlayerRelease() throws RemoteException {
        mController.onPlayerRelease();
    }
}
