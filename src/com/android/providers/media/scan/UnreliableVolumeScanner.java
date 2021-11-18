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

package com.android.providers.media.scan;

import android.content.ContentValues;
import android.util.Log;

import androidx.annotation.NonNull;

import static com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper.MediaColumns.DATE_MODIFIED;
import static com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper.MediaColumns.DISPLAY_NAME;
import static com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper.MediaColumns.MIME_TYPE;
import static com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper.MediaColumns.SIZE_BYTES;
import static com.android.providers.media.photopicker.data.UnreliableVolumeDatabaseHelper.MediaColumns._DATA;

import static com.android.providers.media.util.MimeUtils.isImageMimeType;
import static com.android.providers.media.util.MimeUtils.isVideoMimeType;
import static com.android.providers.media.util.MimeUtils.resolveMimeType;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class UnreliableVolumeScanner {
    private final String TAG = "UnreliableVolumeScanner";

    private boolean isAllowedMimeType(String mimeType) {
        return (isImageMimeType(mimeType) || isVideoMimeType(mimeType));
    }

    private @NonNull ContentValues addDataFromFile(File file, String mimeType) {
        ContentValues fileData = new ContentValues();
        fileData.put(SIZE_BYTES, file.length());
        fileData.put(_DATA, file.getPath());
        fileData.put(MIME_TYPE, mimeType);
        fileData.put(DATE_MODIFIED, file.lastModified());
        fileData.put(DISPLAY_NAME, file.getName());

        return fileData;
    }

    /**
     * @return list of image and video data from the unreliable volume {@code path}
     */
    public @NonNull List<ContentValues> scanAndGetUnreliableVolFileInfo(Path path)
            throws IOException {
        List<ContentValues> unreliableVolumeData = new ArrayList<>();
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String mimeType = resolveMimeType(file.toFile());
                if (isAllowedMimeType(mimeType)) {
                    unreliableVolumeData.add(addDataFromFile(file.toFile(), mimeType));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.w(TAG, "File read failed for: " + file.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return unreliableVolumeData;
    }
}