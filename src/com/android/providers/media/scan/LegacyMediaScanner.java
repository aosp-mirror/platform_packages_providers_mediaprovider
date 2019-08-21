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

package com.android.providers.media.scan;

import android.content.Context;
import android.net.Uri;
import android.os.Trace;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;

public class LegacyMediaScanner implements MediaScanner {
    private final Context mContext;

    public LegacyMediaScanner(Context context) {
        mContext = context;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void scanDirectory(File file) {
        final String path = file.getAbsolutePath();
        final String volumeName = MediaStore.getVolumeName(file);

        Trace.beginSection("scanDirectory");
        try (android.media.MediaScanner scanner =
                new android.media.MediaScanner(mContext, volumeName)) {
            scanner.scanDirectories(new String[] { path });
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public Uri scanFile(File file) {
        final String path = file.getAbsolutePath();
        final String volumeName = MediaStore.getVolumeName(file);

        Trace.beginSection("scanFile");
        try (android.media.MediaScanner scanner =
                new android.media.MediaScanner(mContext, volumeName)) {
            final String ext = path.substring(path.lastIndexOf('.') + 1);
            return scanner.scanSingleFile(path,
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onDetachVolume(String volumeName) {
        // Ignored
    }
}
