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
import android.net.Uri;
import android.os.Build;


import java.util.concurrent.Executor;

/**
 * Wrapper class to {@link EmbeddedPhotopickerClient} for internal use that helps with IPC
 * between caller of {@link EmbeddedPhotopickerProvider#openSession} api and service inside
 * PhotoPicker apk.
 *
 * <p> The app implements {@link EmbeddedPhotopickerClient} and passes it into
 * {@link EmbeddedPhotopickerProvider#openSession} APIs that run on the app's process. That api
 * will then wrap it around this class when doing the actual IPC.
 *
 * <p> This wrapper class implements the internal {@link IEmbeddedPhotopickerClient} interface to
 * convert incoming calls on to it from service back to call on the public
 * EmbeddedPhotopickerClient interface to send it to apps.
 *
 * @see EmbeddedPhotopickerClient
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedPhotopickerClientWrapper extends IEmbeddedPhotopickerClient.Stub {
    private final EmbeddedPhotopickerProviderFactory mProvider;
    private final EmbeddedPhotopickerClient mClientCallback;
    private final Executor mClientExecutor;

    EmbeddedPhotopickerClientWrapper(
            EmbeddedPhotopickerProviderFactory provider,
            EmbeddedPhotopickerClient clientCallback,
            Executor clientExecutor) {
        this.mProvider = provider;
        this.mClientCallback = clientCallback;
        this.mClientExecutor = clientExecutor;
    }

    @Override
    public void onSessionOpened(EmbeddedPhotopickerSessionResponse response) {
        final EmbeddedPhotopickerSession session = new EmbeddedSessionWrapper(mProvider, response);
        mClientExecutor.execute(() -> mClientCallback.onSessionOpened(session));
    }

    @Override
    public void onSessionError(String errorMsg) {
        mProvider.onSessionClosed();
        mClientExecutor.execute(() -> mClientCallback.onSessionError(errorMsg));
    }

    @Override
    public void onItemDeselected(Uri uri) {
        mClientExecutor.execute(() -> mClientCallback.onItemDeselected(uri));
    }

    @Override
    public void onItemSelected(Uri uri) {
        mClientExecutor.execute(() -> mClientCallback.onItemSelected(uri));
    }
}
