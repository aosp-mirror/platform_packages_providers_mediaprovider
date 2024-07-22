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

import static android.content.Context.BIND_AUTO_CREATE;

import android.annotation.RequiresApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Interface to query embedded photopicker session and to maintain session references
 *
 * <p> This will handles the service binding/unbinding on behalf of the caller by keeping track of
 * the number of sessions opened/closed.
 *
 * <p> Makes IPC call to the service using binder {@link IEmbeddedPhotopicker} to get a new session.
 *
 * @see EmbeddedPhotopickerProvider
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public interface EmbeddedPhotopickerProviderFactory {

    /**
     * Method to maintain the count of currently opened photopicker sessions.
     */
    void onSessionClosed();

    /**
     * Creates an implementation of {@link EmbeddedPhotopickerProvider}
     */
    @NonNull
    static EmbeddedPhotopickerProvider create(@NonNull Context context) {
        return new RuntimeEmbeddedPhotopickerProvider(context);
    }

    /**
     *  Implementation of
     *  {@link EmbeddedPhotopickerProviderFactory} and {@link EmbeddedPhotopickerProvider}.
     */
    final class RuntimeEmbeddedPhotopickerProvider
            implements EmbeddedPhotopickerProviderFactory, EmbeddedPhotopickerProvider {
        // Reference count to number of sessions created
        private int mRefCount = 0;
        private Context mClientContext;
        private ServiceConnection mServiceCon;
        private String mPackageName;
        private IEmbeddedPhotopicker mEmbeddedPhotopicker;
        private int mUid;
        private static final String ACTION_EMBEDDED_PHOTOPICKER_SERVICE =
                "com.android.photopicker.core.embedded.EmbeddedService.BIND";
        private static final String TAG = "EmbeddedProviderFactory";

        // If service is already bound and connected successfully
        private boolean mIsServiceBoundAndConnected = false;

        // If service binding has already been initiated by a previous call
        // and currently is in progress
        private boolean mIsBindingInProgress = false;
        private final Object mServiceBindingLock = new Object();
        private Intent mIntent = new Intent(ACTION_EMBEDDED_PHOTOPICKER_SERVICE);

        /**
         * Internal private class having all the necessary components to request
         * a new PhotoPicker session. An object of this class will represent a task to request a new
         * PhotoPicker session, that will be performed when service is fully connected or
         * {@link #mServiceCon}#onServiceConnected() is successfully called.
         */
        private class EmbeddedPhotoPickerOpenSessionCall {
            private IBinder mHostToken;
            private int mDisplayId;
            private int mWidth;
            private int mHeight;
            private EmbeddedPhotopickerClientWrapper mClientCallbackWrapper;
            private EmbeddedPhotopickerFeatureInfo mFeatureInfo;

            private EmbeddedPhotoPickerOpenSessionCall(IBinder hostToken, int displayId, int width,
                    int height, EmbeddedPhotopickerClientWrapper clientCallbackWrapper,
                    EmbeddedPhotopickerFeatureInfo featureInfo) {
                this.mHostToken = hostToken;
                this.mDisplayId = displayId;
                this.mWidth = width;
                this.mHeight = height;
                this.mClientCallbackWrapper = clientCallbackWrapper;
                this.mFeatureInfo = featureInfo;
            }

            // Send request to open a new PhotoPicker session
            private void requestToOpenNewSessionLocked() {
                if (mIsServiceBoundAndConnected) {
                    openNewSessionLocked(mHostToken, mDisplayId, mWidth, mHeight, mFeatureInfo,
                            mClientCallbackWrapper);
                } else {
                    // If Service crashed while requesting a PhotoPicker Session
                    mClientCallbackWrapper.onSessionError(
                            "Service Crashed, Request again to open a session");
                }
            }
        }

        /**
         * This Queue (FIFO) will maintain all open session calls that were requested when service
         * binding was in progress. These all tasks will be performed or resumed when
         * {@link #mServiceCon}#onServiceConnected() is successfully called.
         */
        @NonNull
        private Queue<EmbeddedPhotoPickerOpenSessionCall> mWaitingQueueForOpenSessionCalls =
                new ArrayDeque<>();

        private RuntimeEmbeddedPhotopickerProvider(@NonNull Context context) {
            mClientContext = context;
            mPackageName = context.getPackageName();
            mUid = context.getUser().myUserId();
            initialiseServiceConnection();
            //Set explicit package name
            mIntent.setPackage(getExplicitPackageName());
        }

        void initialiseServiceConnection() {
            mServiceCon = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    setBindingAttributesAndExecuteAllWaitingCalls(/* mIsServiceBoundAndConnected*/
                            true, IEmbeddedPhotopicker.Stub.asInterface(service)
                    );
                }
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    /*
                     * When service crashes , onServiceDisconnected method receives a call here and
                     * if the PhotoPicker service crashes or unexpectedly disconnects while some
                     * PhotoPicker sessions are still active, the host applications must be notified
                     * to close all currently open sessions.
                     */
                    // Todo(b/350959724) Implementation overview: This class will maintain a list
                    // containing references to all {@link EmbeddedPhotopickerClientWrapper}
                    // objects for that PhotoPicker sessions are still active  and onSessionError()
                    // method of {@link EmbeddedPhotopickerClientWrapper} will be called while
                    // iterating this complete reference list to notify host apps about the crash
                    // to take further actions.
                    setBindingAttributesAndExecuteAllWaitingCalls(/* mIsServiceBoundAndConnected*/
                            false, null);
                }
            };
        }

        /**
         * Set all binding related attributes and execute all the tasks in
         * {@link #mWaitingQueueForOpenSessionCalls} that initially were waiting for service to
         * be fully connected.
         */
        private void setBindingAttributesAndExecuteAllWaitingCalls(
                boolean isServiceBoundAndConnected, IEmbeddedPhotopicker iEmbeddedPhotopicker) {
            synchronized (mServiceBindingLock) {
                mEmbeddedPhotopicker = iEmbeddedPhotopicker;
                mIsServiceBoundAndConnected = isServiceBoundAndConnected;
                mIsBindingInProgress = false;
                // This waiting queue empty operation has to be inside synchronized block to avoid
                // inserting element inside {@link #openSession()} by some different thread at the
                // same time when current thread is engaged in removing the elements from the queue.
                while (!mWaitingQueueForOpenSessionCalls.isEmpty()) {
                    mWaitingQueueForOpenSessionCalls.remove().requestToOpenNewSessionLocked();
                }
            }
        }

        /**
         * Get an explicit package name that limit the component {@link #mIntent}  intent will
         * resolve to.
         */
        private String getExplicitPackageName() {
            /*
             * Since we are using {@link PackageManager.MATCH_SYSTEM_ONLY} flag, Only one
             * resolveInfo should be received inside resolveInfo list.
             */
            List<ResolveInfo> resolveInfo =
                    mClientContext.getPackageManager().queryIntentServices(
                            mIntent, PackageManager.MATCH_SYSTEM_ONLY);
            if (resolveInfo != null
                    && resolveInfo.size() == 1
                    && resolveInfo.get(0).serviceInfo != null) {
                return resolveInfo.get(0).serviceInfo.packageName;
            } else {
                throw new RuntimeException("Unable to get a valid explicit package name");
            }
        }

        @Override
        public void openSession(@NonNull IBinder hostToken, int displayId, int width, int height,
                @NonNull EmbeddedPhotopickerFeatureInfo featureInfo,
                @NonNull Executor clientExecutor, @NonNull EmbeddedPhotopickerClient client) {

            synchronized (mServiceBindingLock) {
                // Todo(b/350965066): Validate attributes (like: max selection limit,
                //  mime type filter strings, accent colors etc.) of EmbeddedPhotopickerFeatureInfo
                //  to avoid unnecessary binder calls and throw the exception error if required.
                EmbeddedPhotopickerClientWrapper clientCallbackWrapper =
                        new EmbeddedPhotopickerClientWrapper(
                                this, client, clientExecutor);
                mRefCount += 1;
                if (mIsServiceBoundAndConnected) {
                    // Service is already bound, no need to rebind it. Directly send the request
                    // to open a new session
                    openNewSessionLocked(hostToken,
                            displayId, width, height, featureInfo, clientCallbackWrapper);
                } else {
                    // Create an open session task
                    EmbeddedPhotoPickerOpenSessionCall openSessionCall =
                            new EmbeddedPhotoPickerOpenSessionCall(hostToken,
                                    displayId, width, height, clientCallbackWrapper, featureInfo);
                    // If service binding has not been initiated yet by any previous call,
                    // bind the service
                    if (!mIsBindingInProgress) {
                        if (mClientContext.bindService(mIntent, mServiceCon, BIND_AUTO_CREATE)) {
                            mIsBindingInProgress = true;
                            Log.d(TAG, "Service binding Started!");
                        } else {
                            clientCallbackWrapper.onSessionError("Unable to bind the service!"
                                    + "Request again to open a session");
                        }
                    }
                    if (mIsBindingInProgress) {
                        // Since service binding is in progress, push the task in waiting queue
                        // to resume later once service is fully connected.
                        mWaitingQueueForOpenSessionCalls.add(openSessionCall);
                    }
                }
            }
        }

        /**
         * Request a new session using {@link IEmbeddedPhotoPicker} object.
         */
        private void openNewSessionLocked(IBinder hostToken, int displayId, int width, int height,
                EmbeddedPhotopickerFeatureInfo featureInfo,
                EmbeddedPhotopickerClientWrapper clientCallbackWrapper) {
            try {
                mEmbeddedPhotopicker.openSession(mPackageName, mUid, hostToken, displayId,
                        width, height, featureInfo, clientCallbackWrapper);
            } catch (DeadObjectException e) {
                mIsServiceBoundAndConnected = false;
                mEmbeddedPhotopicker = null;
                clientCallbackWrapper.onSessionError(
                        "Remote delegate is Dead! Request again to open a session");
            } catch (RemoteException e) {
                clientCallbackWrapper.onSessionError(
                        "Remote delegate is Invalid! Request again to open a session");
            }
        }

        @Override
        public void onSessionClosed() {
            synchronized (mServiceBindingLock) {
                mRefCount--;
                if (mRefCount == 0) {
                    mClientContext.unbindService(mServiceCon);
                }
            }
        }
    }
}
