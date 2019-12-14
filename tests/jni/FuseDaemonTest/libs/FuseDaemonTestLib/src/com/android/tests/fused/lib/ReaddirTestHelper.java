/**
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

package com.android.tests.fused.lib;

import static android.provider.MediaStore.MediaColumns;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import android.util.Log;
import android.provider.MediaStore;
import android.content.ContentResolver;
import android.database.Cursor;

import androidx.test.InstrumentationRegistry;

/**
 * Helper functions for readdir tests
 */
public class ReaddirTestHelper {
    private static final String TAG = "ReaddirTestHelper";

    public static final String READDIR_QUERY = "com.android.tests.fused.readdir";
    public static final String CREATE_FILE_QUERY = "com.android.tests.fused.createfile";
    public static final String DELETE_FILE_QUERY = "com.android.tests.fused.deletefile";

    /**
     * Returns directory entries for the given {@code directory}
     *
     * @param directory directory that needs to be listed.
     * @return list of directory names and filenames in the given directory.
     */
    public static ArrayList<String> readDirectory(File directory) {
        return readDirectory(directory.toString());
    }

    /**
     * Returns directory entries for the given {@code directoryPath}
     *
     * @param directoryPath path of the directory.
     * @return list of directory names and filenames in the given directory.
     */
    public static ArrayList<String> readDirectory(String directoryPath) {
        Filter<Path> filter = new Filter<Path>() {
            public boolean accept(Path file) {
                return true;
            }
        };
        return readDirectory(directoryPath, filter);
    }

    /**
     * Returns filtered directory entries for the given {@code directoryPath}
     *
     * @param directoryPath path of the directory.
     * @param filter filter to apply on directory entries.
     * @return list of directory names and filenames in the given directory. Directory entries are
     * filtered by the given filter.
     */
    public static ArrayList<String> readDirectory(String directoryPath, Filter<Path> filter) {
        ArrayList<String> directoryEntries = new ArrayList<String>();
        File dir = new File(directoryPath);
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir.toPath(),
                filter)) {
            for (Path de: directoryStream) {
                directoryEntries.add(de.getFileName().toString());
            }
        } catch (IOException x) {
            Log.e(TAG, "IOException occurred while readding directory entries from " +
                  directoryPath);
        }
        return directoryEntries;
    }
}
