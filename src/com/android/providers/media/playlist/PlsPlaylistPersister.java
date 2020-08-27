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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlsPlaylistPersister implements PlaylistPersister {
    private static final Pattern PATTERN_PLS = Pattern.compile("File(\\d+)=(.+)");

    @Override
    public void read(@NonNull InputStream in, @NonNull List<Path> items) throws IOException {
        final FileSystem fs = FileSystems.getDefault();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            Path[] res = new Path[0];

            String line;
            while ((line = reader.readLine()) != null) {
                final Matcher matcher = PATTERN_PLS.matcher(line);
                if (matcher.matches()) {
                    final int index = Integer.parseInt(matcher.group(1));
                    final Path item = fs.getPath(matcher.group(2).replace('\\', '/'));
                    if (index + 1 > res.length) {
                        res = Arrays.copyOf(res, index + 1);
                    }
                    res[index] = item;
                }
            }

            for (int i = 0; i < res.length; i++) {
                if (res[i] != null) {
                    items.add(res[i]);
                }
            }
        }
    }

    @Override
    public void write(@NonNull OutputStream out, @NonNull List<Path> items) throws IOException {
        try (PrintWriter writer = new PrintWriter(out)) {
            writer.printf("[playlist]\n");
            for (int i = 0; i < items.size(); i++) {
                writer.printf("File%d=%s\n", i + 1, items.get(i));
            }
            writer.printf("NumberOfEntries=%d\n", items.size());
            writer.printf("Version=2\n");
        }
    }
}
