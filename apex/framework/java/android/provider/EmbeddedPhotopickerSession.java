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
import android.content.res.Configuration;
import android.os.Build;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/**
 * Apps will asynchronously receive instance of this class from the service upon successful
 * execution of {@link EmbeddedPhotopickerProvider#openSession} via
 * {@link EmbeddedPhotopickerClient#onSessionOpened}
 *
 * <p> Contains the {@link SurfaceControlViewHost.SurfacePackage} that can be embedded by the
 * app in their view hierarchy in a {@link SurfaceView}
 *
 * <p> Apps can use the instance they hold to notify PhotoPicker about different events.
 *
 * <p> Apps should close the session when no longer being used to help system release the resources.
 *
 * @see EmbeddedPhotopickerProvider
 * @see EmbeddedPhotopickerClient
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public interface EmbeddedPhotopickerSession {

    /**
     * Returns the {@link SurfaceControlViewHost.SurfacePackage} that contains view representing
     * embedded picker.
     *
     * <p> App can attach this view in their hierarchy using
     * {@link SurfaceView#setChildSurfacePackage}
     */
    @NonNull
    SurfaceControlViewHost.SurfacePackage getSurfacePackage();

    /**
     * Close the session, i.e. photopicker will release resources associated with this
     * session. Any further notifications to this session will be ignored.
     */
    void close();

    /**
     * Notify that embedded photopicker view is attached/detached from the screen.
     *
     * <p> This helps photopicker to close upstream work and also move lifecycle of this
     * session object to RESUME
     *
     * @param isVisible True if view present on the screen, false if detached.
     */
    void notifyVisibilityChanged(boolean isVisible);

    /**
     * Notify that app's presentation area has changed and photopicker's dimensions
     * should change accordingly
     *
     * @param width width of the view, in pixels
     * @param height height of the view, in pixels
     */
    void notifyResized(int width, int height);

    /**
     * Notifies photopicker that host side configuration has changed
     *
     * @param configuration new configuration of app
     */
    void notifyConfigurationChanged(@NonNull Configuration configuration);

    /**
     * Notify that user switched photopicker between expanded/collapsed state.
     *
     * <p> Some photopicker features (like Profile selector, Album grid etc.)
     * are only shown in full/expanded view and are hidden in collapsed view.
     */
    void notifyPhotopickerExpanded(boolean isExpanded);
}
