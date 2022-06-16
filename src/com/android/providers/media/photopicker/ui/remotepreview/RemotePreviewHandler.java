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

import static android.provider.CloudMediaProviderContract.EXTRA_AUTHORITY;
import static android.provider.CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK;
import static android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER;

import static com.android.providers.media.PickerUriResolver.createSurfaceControllerUri;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback.PlaybackState;
import android.provider.ICloudMediaSurfaceController;
import android.provider.ICloudMediaSurfaceStateChangedCallback;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.android.providers.media.photopicker.RemoteVideoPreviewProvider;
import com.android.providers.media.photopicker.data.MuteStatus;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.ui.PreviewVideoHolder;

import java.util.Map;

/**
 * Manages playback of videos on a {@link Surface} with a
 * {@link android.provider.CloudMediaProvider.CloudMediaSurfaceController} populated remotely.
 *
 * <p>This class is not thread-safe and the methods are meant to be always called on the main
 * thread.
 */
public final class RemotePreviewHandler {

    private static final String TAG = "RemotePreviewHandler";

    private final Context mContext;
    private final MuteStatus mMuteStatus;
    private final ArrayMap<SurfaceHolder, RemotePreviewSession>
            mSessionMap = new ArrayMap<>();
    private final Map<String, SurfaceControllerProxy> mControllers =
            new ArrayMap<>();
    private final SurfaceHolder.Callback mSurfaceHolderCallback = new PreviewSurfaceCallback();
    private final SurfaceStateChangedCallbackWrapper mSurfaceStateChangedCallbackWrapper =
            new SurfaceStateChangedCallbackWrapper();
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private final ItemPreviewState mCurrentPreviewState = new ItemPreviewState();
    private final PlayerControlsVisibilityStatus mPlayerControlsVisibilityStatus =
            new PlayerControlsVisibilityStatus();

    private boolean mIsInBackground = false;
    private int mSurfaceCounter = 0;

    /**
     * Returns {@code true} if remote preview is enabled.
     */
    public static boolean isRemotePreviewEnabled() {
        return SystemProperties.getBoolean("sys.photopicker.remote_preview", true);
    }

    public RemotePreviewHandler(Context context, MuteStatus muteStatus) {
        mContext = context;
        mMuteStatus = muteStatus;
    }

    /**
     * Prepares the given {@link SurfaceView} for remote preview of the given {@link Item}.
     *
     * @param viewHolder {@link PreviewVideoHolder} for the media item under preview
     * @param item       {@link Item} to be previewed
     */
    public void onViewAttachedToWindow(PreviewVideoHolder viewHolder, Item item) {
        final RemotePreviewSession session = createRemotePreviewSession(item, viewHolder);
        final SurfaceHolder holder = viewHolder.getSurfaceHolder();

        mSessionMap.put(holder, session);
        // Ensure that we don't add the same callback twice, since we don't remove callbacks
        // anywhere else.
        holder.removeCallback(mSurfaceHolderCallback);
        holder.addCallback(mSurfaceHolderCallback);
    }

    /**
     * Handle page selected event for the given {@link Item}.
     *
     * <p>This is where we start the playback for the {@link Item}.
     *
     * @param item {@link Item} to be played
     * @return true if the given {@link Item} can be played, else false
     */
    public boolean onHandlePageSelected(Item item) {
        if (!item.isVideo()) {
            // Clear state of the previous player controls visibility state. Controls visibility
            // state will only be tracked and used for contiguous videos in the preview.
            mPlayerControlsVisibilityStatus.setShouldShowPlayerControlsForNextItem(true);
            return false;
        }

        Log.i(TAG, "onHandlePageSelected() called, attempting to start playback.");
        RemotePreviewSession session = getSessionForItem(item);
        if (session == null) {
            Log.w(TAG, "No RemotePreviewSession found.");
            return false;
        }

        mCurrentPreviewState.item = item;
        mCurrentPreviewState.viewHolder = session.getPreviewVideoHolder();

        session.requestPlayMedia();
        return true;
    }

    /**
     * Handle onStop called from activity/fragment lifecycle.
     */
    public void onStop() {
        mIsInBackground = true;
    }

    /**
     * Handle onDestroy called from activity/fragment lifecycle.
     *
     * <p>This is where the surface controllers are destroyed and their references are released.
     */
    public void onDestroy() {
        Log.i(TAG, "onDestroy() called, destroying all surface controllers.");
        destroyAllSurfaceControllers();
    }

    private RemotePreviewSession createRemotePreviewSession(Item item,
            PreviewVideoHolder previewVideoHolder) {
        String authority = item.getContentUri().getAuthority();
        SurfaceControllerProxy controller = getSurfaceController(authority, false);
        if (controller == null) {
            Log.w(TAG, "Failed to create RemotePreviewSession for " + authority
                    + ". Fallback to openPreview");
            controller = getSurfaceController(authority, true);
        }

        return new RemotePreviewSession(mSurfaceCounter++, item.getId(), authority, controller,
                previewVideoHolder, mMuteStatus, mPlayerControlsVisibilityStatus, mContext);
    }

    private void restorePreviewState(SurfaceHolder holder) {
        RemotePreviewSession session = createRemotePreviewSession(mCurrentPreviewState.item,
                mCurrentPreviewState.viewHolder);
        if (session == null) {
            throw new IllegalStateException("Failed to restore preview state.");
        }

        mSessionMap.put(holder, session);
        session.surfaceCreated();
        session.requestPlayMedia();
    }

    private RemotePreviewSession getSessionForItem(Item item) {
        String mediaId = item.getId();
        String authority = item.getContentUri().getAuthority();
        for (RemotePreviewSession session : mSessionMap.values()) {
            if (session.getMediaId().equals(mediaId) && session.getAuthority().equals(authority)) {
                return session;
            }
        }
        return null;
    }

    private RemotePreviewSession getSessionForSurfaceId(int surfaceId) {
        for (RemotePreviewSession session : mSessionMap.values()) {
            if (session.getSurfaceId() == surfaceId) {
                return session;
            }
        }
        return null;
    }

    @Nullable
    private SurfaceControllerProxy getSurfaceController(String authority,
            boolean localControllerFallback) {
        if (mControllers.containsKey(authority)) {
            return mControllers.get(authority);
        }

        SurfaceControllerProxy controller = null;
        try {
            controller = createController(authority, localControllerFallback);
            if (controller != null) {
                mControllers.put(authority, controller);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not create SurfaceController.", e);
        }
        return controller;
    }

    private void destroyAllSurfaceControllers() {
        for (SurfaceControllerProxy controller : mControllers.values()) {
            try {
                controller.onDestroy();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to destroy SurfaceController.", e);
            }
        }
        mControllers.clear();
    }

    private SurfaceControllerProxy createController(String authority,
            boolean localControllerFallback) {
        Log.i(TAG, "Creating new SurfaceController for authority: " + authority
                + ". localControllerFallback: " + localControllerFallback);
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_LOOPING_PLAYBACK_ENABLED, true);
        // Only start audio after audio focus gain
        extras.putBoolean(EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, true);
        extras.putBinder(EXTRA_SURFACE_STATE_CALLBACK, mSurfaceStateChangedCallbackWrapper);

        if (localControllerFallback) {
            extras.putString(EXTRA_AUTHORITY, authority);
            authority = RemoteVideoPreviewProvider.AUTHORITY;
        }

        final Bundle surfaceControllerBundle = mContext.getContentResolver().call(
                createSurfaceControllerUri(authority),
                METHOD_CREATE_SURFACE_CONTROLLER, /* arg */ null, extras);
        IBinder binder = surfaceControllerBundle.getBinder(EXTRA_SURFACE_CONTROLLER);
        return binder != null ? new SurfaceControllerProxy(
                ICloudMediaSurfaceController.Stub.asInterface(binder))
                : null;
    }

    /**
     * Wrapper class for {@link android.provider.ICloudMediaSurfaceStateChangedCallback} interface
     * implementation.
     */
    private final class SurfaceStateChangedCallbackWrapper extends
            ICloudMediaSurfaceStateChangedCallback.Stub {

        @Override
        public void setPlaybackState(int surfaceId, @PlaybackState int playbackState,
                @Nullable Bundle playbackStateInfo) {
            Log.d(TAG, "Received onPlaybackEvent for surfaceId: " + surfaceId +
                    " ; playbackState: " + playbackState + " ; playbackStateInfo: " +
                    playbackStateInfo);

            mMainThreadHandler.post(() -> {
                final RemotePreviewSession session = getSessionForSurfaceId(surfaceId);
                if (session == null) {
                    Log.w(TAG, "No RemotePreviewSession found.");
                    return;
                }
                session.setPlaybackState(playbackState, playbackStateInfo);
            });
        }
    }

    private final class PreviewSurfaceCallback implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "Surface created: " + holder);

            if (mIsInBackground) {
                // This indicates that the app has just come to foreground, and we need to
                // restore the preview state.
                restorePreviewState(holder);
                mIsInBackground = false;
                return;
            }

            Surface surface = holder.getSurface();
            RemotePreviewSession session = mSessionMap.get(holder);
            session.surfaceCreated();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "Surface changed: " + holder + " format: " + format + " width: " + width
                    + " height: " + height);

            RemotePreviewSession session = mSessionMap.get(holder);
            session.surfaceChanged(format, width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "Surface destroyed: " + holder);

            RemotePreviewSession session = mSessionMap.get(holder);
            session.surfaceDestroyed();
            mSessionMap.remove(holder);
        }
    }

    private static final class ItemPreviewState {
        Item item;
        PreviewVideoHolder viewHolder;
    }
}
