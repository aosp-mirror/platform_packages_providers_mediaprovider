/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.fuse;

import static com.android.providers.media.scan.MediaScanner.REASON_MOUNTED;

import android.content.ContentProviderClient;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.service.storage.ExternalStorageService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.MediaProvider;
import com.android.providers.media.MediaService;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles filesystem I/O from other apps.
 */
public final class ExternalStorageServiceImpl extends ExternalStorageService {
    private static final String TAG = "ExternalStorageServiceImpl";

    private static final Object sLock = new Object();
    private static final Map<String, FuseDaemon> sFuseDaemons = new HashMap<>();

    @Override
    public void onStartSession(String sessionId, /* @SessionFlag */ int flag,
            @NonNull ParcelFileDescriptor deviceFd, @NonNull File upperFileSystemPath,
            @NonNull File lowerFileSystemPath) {
        MediaProvider mediaProvider = getMediaProvider();

        synchronized (sLock) {
            if (sFuseDaemons.containsKey(sessionId)) {
                Log.w(TAG, "Session already started with id: " + sessionId);
            } else {
                Log.i(TAG, "Starting session for id: " + sessionId);
                // We only use the upperFileSystemPath because the media process is mounted as
                // REMOUNT_MODE_PASS_THROUGH which guarantees that all /storage paths are bind
                // mounts of the lower filesystem.
                FuseDaemon daemon = new FuseDaemon(mediaProvider, this, deviceFd, sessionId,
                        upperFileSystemPath.getPath());
                daemon.start();
                sFuseDaemons.put(sessionId, daemon);
            }
        }
    }

    @Override
    public void onVolumeStateChanged(StorageVolume vol) throws IOException {
        MediaProvider mediaProvider = getMediaProvider();
        String volumeName = vol.getMediaStoreVolumeName();

        switch(vol.getState()) {
            case Environment.MEDIA_MOUNTED:
                mediaProvider.attachVolume(volumeName, /* validate */ false);
                break;
            case Environment.MEDIA_UNMOUNTED:
            case Environment.MEDIA_EJECTING:
            case Environment.MEDIA_REMOVED:
            case Environment.MEDIA_BAD_REMOVAL:
                mediaProvider.detachVolume(volumeName);
                break;
            default:
                Log.i(TAG, "Ignoring volume state for vol:" + volumeName
                        + ". State: " + vol.getState());
        }
        // Check for invalidation of cached volumes
        mediaProvider.updateVolumes();
    }

    @Override
    public void onEndSession(String sessionId) {
        FuseDaemon daemon = onExitSession(sessionId);

        if (daemon == null) {
            Log.w(TAG, "Session already ended with id: " + sessionId);
        } else {
            Log.i(TAG, "Ending session for id: " + sessionId);
            // The FUSE daemon cannot end the FUSE session itself, but if the FUSE filesystem
            // is unmounted, the FUSE thread started in #onStartSession will exit and we can
            // this allows us wait for confirmation. This blocks the client until the session has
            // exited for sure
            daemon.waitForExit();
        }
    }

    public FuseDaemon onExitSession(String sessionId) {
        Log.i(TAG, "Exiting session for id: " + sessionId);
        synchronized (sLock) {
            return sFuseDaemons.remove(sessionId);
        }
    }

    @Nullable
    public static FuseDaemon getFuseDaemon(String sessionId) {
        synchronized (sLock) {
            return sFuseDaemons.get(sessionId);
        }
    }

    private MediaProvider getMediaProvider() {
        try (ContentProviderClient cpc =
                getContentResolver().acquireContentProviderClient(MediaStore.AUTHORITY)) {
            return (MediaProvider) cpc.getLocalContentProvider();
        } catch (OperationCanceledException e) {
            throw new IllegalStateException("Failed to acquire MediaProvider", e);
        }
    }
}
