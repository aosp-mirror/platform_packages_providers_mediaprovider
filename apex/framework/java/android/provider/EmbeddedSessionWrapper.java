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
import android.os.RemoteException;
import android.view.SurfaceControlViewHost;

/**
 * Wrapper class to {@link EmbeddedPhotopickerSession} for internal use that helps with IPC between
 * caller of {@link EmbeddedPhotopickerProvider#openSession} api and service inside PhotoPicker apk.
 *
 * <p> This class implements the {@link EmbeddedPhotopickerSession} interface to convert incoming
 * calls on to it from app and send it to the service. It uses {@link IEmbeddedPhotopickerSession}
 * as the delegate
 *
 * @see EmbeddedPhotopickerSession
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedSessionWrapper implements EmbeddedPhotopickerSession {
    private final EmbeddedPhotopickerProviderFactory mProvider;
    private final EmbeddedPhotopickerSessionResponse mSessionResponse;
    private final IEmbeddedPhotopickerSession mSession;
    private static final String TAG = "EmbeddedSessionWrapper";
    EmbeddedSessionWrapper(EmbeddedPhotopickerProviderFactory provider,
            EmbeddedPhotopickerSessionResponse mSessionResponse) {
        this.mProvider = provider;
        this.mSessionResponse = mSessionResponse;
        this.mSession = mSessionResponse.getSession();
    }

    @Override
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage() {
        return mSessionResponse.getSurfacePackage();
    }

    @Override
    public void close() {
        mProvider.onSessionClosed();
        try {
            mSession.close();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    public void notifyVisibilityChanged(boolean isVisible) {
        try {
            mSession.notifyVisibilityChanged(isVisible);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void notifyResized(int width, int height) {
        try {
            mSession.notifyResized(width, height);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void notifyConfigurationChanged(Configuration configuration) {
        try {
            mSession.notifyConfigurationChanged(configuration);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void notifyPhotopickerExpanded(boolean isExpanded) {
        try {
            mSession.notifyPhotopickerExpanded(isExpanded);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}

