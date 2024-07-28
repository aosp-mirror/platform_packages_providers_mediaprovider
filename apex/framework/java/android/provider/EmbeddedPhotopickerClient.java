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

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.net.Uri;
import android.os.Build;

/**
 * Callback to define mechanisms by which can apps can receive notifications about
 * different events from PhotoPicker
 *
 * <p> PhotoPicker will invoke the methods of this interface on the Executor provided by
 * the app in {@link EmbeddedPhotopickerProvider#openSession}
 *
 * @see EmbeddedPhotopickerProvider
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public interface EmbeddedPhotopickerClient {

    /**
     * Reports that session of app with photopicker was established successfully.
     * Also shares {@link EmbeddedPhotopickerSession} handle containing the view
     * with the app that should be used to notify the session of UI events.
     */
    void onSessionOpened(@NonNull EmbeddedPhotopickerSession session);

    /**
     * Reports that terminal error has occurred in the session. Any further events
     * notified on this session will be ignored. The embedded photopicker view will be
     * torn down along with session upon error.
     */
    void onSessionError(@NonNull String errorMsg);

    /**
     * Reports that URI permission has been granted to the item selected by the user.
     *
     * <p> It is possible that the permission to the URI was revoked if item unselected
     * by user, but before the URI is actually accessed by the app. Hence, app must
     * handle {@code SecurityException} when attempting to read or use the URI in
     * response to this callback.
     */
    void onItemSelected(@NonNull Uri uri);

    /**
     * Reports that URI permission has been revoked of the item deselected by the
     * user.
     */
    void onItemDeselected(@NonNull Uri uri);
}
