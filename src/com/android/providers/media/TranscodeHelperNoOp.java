/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media;

import android.net.Uri;
import android.os.Bundle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * No-op transcode helper to avoid loading MediaTranscodeManager classes in Android R
 */
public final class TranscodeHelperNoOp implements TranscodeHelper {

    @Override
    public void freeCache(long bytes) {}

    @Override
    public void onAnrDelayStarted(String packageName, int uid, int tid, int reason) {}

    @Override
    public boolean transcode(String src, String dst, int uid, int reason) {
        return false;
    }

    @Override
    public String prepareIoPath(String path, int uid) {
        return null;
    }

    @Override
    public int shouldTranscode(String path, int uid, Bundle bundle) {
        return 0;
    }
    @Override
    public boolean supportsTranscode(String path) {
        return false;
    }

    @Override
    public void onUriPublished(Uri uri) {}

    @Override
    public void onFileOpen(String path, String ioPath, int uid, int transformsReason) {}

    @Override
    public boolean isTranscodeFileCached(String path, String transcodePath) {
        return false;
    }

    @Override
    public boolean deleteCachedTranscodeFile(long rowId) {
        return false;
    }

    @Override
    public void dump(PrintWriter writer) {}

    @Override
    public List<String> getSupportedRelativePaths() {
        return new ArrayList<String>();
    }
}
