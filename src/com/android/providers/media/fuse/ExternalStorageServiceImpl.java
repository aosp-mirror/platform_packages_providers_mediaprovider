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

import android.annotation.NonNull;
import android.os.ParcelFileDescriptor;
import android.service.storage.ExternalStorageService;
import android.util.Log;

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
                return;
            }
            FuseDaemon daemon = new FuseDaemon(getContentResolver(), deviceFd, upperFileSystemPath,
                    lowerFileSystemPath);
            new Thread(daemon).start();
            mFuseDaemons.put(sessionId, daemon);
        }
    }

    @Override
    public void onEndSession(String sessionId) {
        synchronized (mLock) {
            FuseDaemon daemon = mFuseDaemons.get(sessionId);
            if (daemon != null) {
                daemon.stop();
                mFuseDaemons.remove(sessionId);
            } else {
                Log.w(TAG, "No session with id: " + sessionId);
            }
        }
    }
}
