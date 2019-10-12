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

import android.os.ParcelFileDescriptor;
import android.service.storage.ExternalStorageService;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles filesystem I/O from other apps.
 */
public final class ExternalStorageServiceImpl extends ExternalStorageService {
    private static final String TAG = "ExternalStorageServiceImpl";

    private final Object mLock = new Object();
    private final Map<String, FuseDaemon> mFuseDaemons = new HashMap<>();

    @Override
    public void onStartSession(String sessionId, @SessionFlag int flag,
            @NonNull ParcelFileDescriptor deviceFd, @NonNull String upperFileSystemPath,
            @NonNull String lowerFileSystemPath) {
        synchronized (mLock) {
            if (mFuseDaemons.containsKey(sessionId)) {
                Log.w(TAG, "Session already started with id: " + sessionId);
                return;
            }

            Log.i(TAG, "Starting session for id: " + sessionId);
            // We only use the upperFileSystemPath because the media process is mounted as
            // REMOUNT_MODE_PASS_THROUGH which guarantees that all /storage paths are bind
            // mounts of the lower filesystem.
            FuseDaemon daemon = new FuseDaemon(getContentResolver(), sessionId, this, deviceFd,
                    upperFileSystemPath);
            new Thread(daemon).start();
            mFuseDaemons.put(sessionId, daemon);
        }
    }

    @Override
    public void onEndSession(String sessionId) {
        synchronized (mLock) {
            FuseDaemon daemon = mFuseDaemons.get(sessionId);
            if (daemon != null) {
                Log.i(TAG, "Ending session for id: " + sessionId);
                daemon.stop();
                mFuseDaemons.remove(sessionId);
            } else {
                Log.w(TAG, "No session with id: " + sessionId);
            }
        }
    }
}
