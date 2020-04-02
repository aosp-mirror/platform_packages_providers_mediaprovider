/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.media.playlist;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

public class M3uPlaylistPersister implements PlaylistPersister {
    @Override
    public void read(@NonNull InputStream in, @NonNull List<Path> items) throws IOException {
        final FileSystem fs = FileSystems.getDefault();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!TextUtils.isEmpty(line) && !line.startsWith("#")) {
                    items.add(fs.getPath(line.replace('\\', '/')));
                }
            }
        }
    }

    @Override
    public void write(@NonNull OutputStream out, @NonNull List<Path> items) throws IOException {
        try (PrintWriter writer = new PrintWriter(out)) {
            writer.println("#EXTM3U");
            for (Path item : items) {
                writer.println(item);
            }
        }
    }
}
