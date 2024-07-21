/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.provider;

import android.annotation.RequiresApi;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.view.AttachedSurfaceControl;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;

/**
 * This class provides an api that apps can use to get a session of embedded PhotoPicker
 * ({@link EmbeddedPhotopickerSession}).
 *
 * <p> When a session opens successfully, they would receive an instance of
 * {@link EmbeddedPhotopickerSession} and {@link android.view.SurfaceControlViewHost.SurfacePackage}
 * via the {@link EmbeddedPhotopickerClient#onSessionOpened api}
 *
 * <p> Apps pass an instance of {@link EmbeddedPhotopickerClient} which is used by service to notify
 * apps about different events.
 *
 * <p> Apps can use this {@link EmbeddedPhotopickerSession} instance to notify photopicker about
 * different events.
 *
 * @see EmbeddedPhotopickerClient
 * @see EmbeddedPhotopickerSession
 * @see EmbeddedPhotopickerProviderFactory
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public interface EmbeddedPhotopickerProvider {

    /**
     * Open a new session for displaying content with an initial size of
     * width x height pixels. {@link EmbeddedPhotopickerClient} will receive all incoming
     * communication from the PhotoPicker. All incoming calls to {@link EmbeddedPhotopickerClient}
     * will be made through the provided {@code clientExecutor}
     *
     * @param hostToken Token used for constructing {@link android.view.SurfaceControlViewHost}.
     *                  Use {@link AttachedSurfaceControl#getInputTransferToken()} to
     *                  get token of attached
     *                  {@link android.view.SurfaceControlViewHost.SurfacePackage}.
     * @param displayId Application display id. Use
     *                  {@link DisplayManager#getDisplays()} to get the id.
     * @param width width of the view, in pixels.
     * @param height height of the view, in pixels.
     * @param featureInfo {@link EmbeddedPhotopickerFeatureInfo} object containing all
     *                     the required features for the given session.
     * @param clientExecutor {@link Executor} to invoke callbacks.
     * @param callback {@link EmbeddedPhotopickerClient} object to receive callbacks
     *                  from photopicker.
     */
    void openSession(
            @NonNull IBinder hostToken,
            int displayId,
            int width,
            int height,
            @NonNull EmbeddedPhotopickerFeatureInfo featureInfo,
            @NonNull Executor clientExecutor,
            @NonNull EmbeddedPhotopickerClient callback);
}

