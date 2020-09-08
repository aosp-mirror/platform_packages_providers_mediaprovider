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

import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.android.providers.media.util.MimeUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Interface that knows how to {@link #read} and {@link #write} a set of
 * playlist items using a specific file format. This design allows you to easily
 * convert between playlist file formats by reading one format and writing to
 * another.
 */
public interface PlaylistPersister {
    public void read(@NonNull InputStream in, @NonNull List<Path> items) throws IOException;
    public void write(@NonNull OutputStream out, @NonNull List<Path> items) throws IOException;

    /**
     * Resolve a concrete version of this interface which matches the given
     * playlist file format.
     */
    public static @NonNull PlaylistPersister resolvePersister(@NonNull File file)
            throws IOException {
        return resolvePersister(MimeUtils.resolveMimeType(file));
    }

    /**
     * Resolve a concrete version of this interface which matches the given
     * playlist file format.
     */
    public static @NonNull PlaylistPersister resolvePersister(@NonNull ContentResolver resolver,
            @NonNull Uri uri) throws IOException {
        return resolvePersister(resolver.getType(uri));
    }

    /**
     * Resolve a concrete version of this interface which matches the given
     * playlist file format.
     */
    public static @NonNull PlaylistPersister resolvePersister(@NonNull String mimeType)
            throws IOException {
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "audio/mpegurl":
            case "audio/x-mpegurl":
            case "application/vnd.apple.mpegurl":
            case "application/x-mpegurl":
                return new M3uPlaylistPersister();
            case "audio/x-scpls":
                return new PlsPlaylistPersister();
            case "application/vnd.ms-wpl":
            case "video/x-ms-asf":
                return new WplPlaylistPersister();
            case "application/xspf+xml":
                return new XspfPlaylistPersister();
            default:
                throw new IOException("Unsupported playlist format " + mimeType);
        }
    }
}
