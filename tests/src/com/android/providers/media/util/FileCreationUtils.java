/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.net.Uri;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.MediaStore;

import com.android.providers.media.photopicker.PickerSyncController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A utility class to assist creating files for tests
 */
public class FileCreationUtils {
    /**
     * Helper method to insert a test image/png into given {@code contentResolver}
     *
     * @param  contentResolver ContentResolver to which file is inserted
     * @param name file name
     * @return {@link Long} the files table {@link MediaStore.MediaColumns.ID}
     */
    public static Long insertFileInResolver(ContentResolver contentResolver, String name)
            throws IOException {
        final File dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final File file = new File(dir, name + System.nanoTime() + ".png");

        // Write 1 byte because 0 byte files are not valid in the db
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(1);
        }

        Uri uri = MediaStore.scanFile(contentResolver, file);
        return ContentUris.parseId(uri);
    }

    /**
     * Assembles a valid picker content URI that resembels a content:// uri that would be returned
     * from photopicker.
     *
     * @param id The files table id
     * @return {@link Uri}
     */
    public static Uri buildValidPickerUri(Long id) {

        return initializeUriBuilder(MediaStore.AUTHORITY)
                .appendPath("picker")
                .appendPath(Integer.toString(UserHandle.myUserId()))
                .appendPath(PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)
                .appendPath(MediaStore.AUTHORITY)
                .appendPath(Long.toString(id))
                .build();
    }

    /**
     * @param authority The authority to encode in the Uri builder.
     * @return {@link Uri.Builder} for a content:// uri for the passed authority.
     */
    private static Uri.Builder initializeUriBuilder(String authority) {
        final Uri.Builder builder = Uri.EMPTY.buildUpon();
        builder.scheme("content");
        builder.encodedAuthority(authority);

        return builder;
    }
}
