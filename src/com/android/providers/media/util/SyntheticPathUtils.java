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

package com.android.providers.media.util;

import static com.android.providers.media.util.FileUtils.buildPath;
import static com.android.providers.media.util.FileUtils.buildPrimaryVolumeFile;
import static com.android.providers.media.util.FileUtils.extractFileName;

import androidx.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SyntheticPathUtils {
    private static final String TAG = "SyntheticPathUtils";

    private static final String TRANSFORMS_DIR = ".transforms";
    private static final String SYNTHETIC_DIR = "synthetic";
    private static final String REDACTED_DIR = "redacted";
    private static final String PICKER_DIR = "picker";

    public static final String REDACTED_URI_ID_PREFIX = "RUID";
    public static final int REDACTED_URI_ID_SIZE = 36;

    private SyntheticPathUtils() {}

    public static String getRedactedRelativePath() {
        return buildPath(/* base */ null, TRANSFORMS_DIR, SYNTHETIC_DIR, REDACTED_DIR).getPath();
    }

    public static String getPickerRelativePath() {
        return buildPath(/* base */ null, TRANSFORMS_DIR, SYNTHETIC_DIR, PICKER_DIR).getPath();
    }

    public static boolean isRedactedPath(String path, int userId) {
        if (path == null) return false;

        final String redactedDir = buildPrimaryVolumeFile(userId, getRedactedRelativePath())
                .getAbsolutePath();
        final String fileName = extractFileName(path);

        return fileName != null
                && startsWith(path, redactedDir)
                && startsWith(fileName, REDACTED_URI_ID_PREFIX)
                && fileName.length() == REDACTED_URI_ID_SIZE;
    }

    public static boolean isPickerPath(String path, int userId) {
        final String pickerDir = buildPrimaryVolumeFile(userId, getPickerRelativePath())
                .getAbsolutePath();

        return path != null && startsWith(path, pickerDir);
    }

    public static boolean isSyntheticPath(String path, int userId) {
        final String syntheticDir = buildPrimaryVolumeFile(userId, getSyntheticRelativePath())
                .getAbsolutePath();

        return path != null && startsWith(path, syntheticDir);
    }

    public static List<String> extractSyntheticRelativePathSegements(String path, int userId) {
        final List<String> segments = new ArrayList<>();
        final String syntheticDir = buildPrimaryVolumeFile(userId,
                getSyntheticRelativePath()).getAbsolutePath();

        if (path.toLowerCase(Locale.ROOT).indexOf(syntheticDir.toLowerCase(Locale.ROOT)) < 0) {
            return segments;
        }

        final String[] segmentArray = path.substring(syntheticDir.length()).split("/");
        for (String segment : segmentArray) {
            if (TextUtils.isEmpty(segment)) {
                continue;
            }
            segments.add(segment);
        }

        return segments;
    }

    public static boolean createSparseFile(File file, long size) {
        if (size < 0) {
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(size);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create sparse file: " + file, e);
            file.delete();
            return false;
        }
    }

    @VisibleForTesting
    static String getSyntheticRelativePath() {
        return buildPath(/* base */ null, TRANSFORMS_DIR, SYNTHETIC_DIR).getPath();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }
}
