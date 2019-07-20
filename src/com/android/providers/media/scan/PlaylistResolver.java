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

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaylistResolver {
    private static final Pattern PATTERN_PLS = Pattern.compile("File(\\d+)=(.+)");

    private static final String TAG_MEDIA = "media";
    private static final String ATTR_SRC = "src";

    /**
     * Resolve the contents of the given
     * {@link android.provider.MediaStore.Audio.Playlists} item, returning a
     * list of {@link ContentProviderOperation} that will update all members.
     */
    public static @NonNull List<ContentProviderOperation> resolvePlaylist(
            @NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        final String mimeType;
        final File file;
        try (Cursor cursor = resolver.query(uri, new String[] {
                MediaColumns.MIME_TYPE, MediaColumns.DATA
        }, null, null, null)) {
            if (!cursor.moveToFirst()) {
                throw new FileNotFoundException(uri.toString());
            }
            mimeType = cursor.getString(0);
            file = new File(cursor.getString(1));
        }

        switch (mimeType) {
            case "audio/x-mpegurl":
            case "audio/mpegurl":
            case "application/x-mpegurl":
            case "application/vnd.apple.mpegurl":
                return resolvePlaylistM3u(resolver, uri, file);
            case "audio/x-scpls":
                return resolvePlaylistPls(resolver, uri, file);
            case "application/vnd.ms-wpl":
            case "video/x-ms-asf":
                return resolvePlaylistWpl(resolver, uri, file);
            default:
                throw new IOException("Unsupported playlist of type " + mimeType);
        }
    }

    private static @NonNull List<ContentProviderOperation> resolvePlaylistM3u(
            @NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File file)
            throws IOException {
        final Path parentPath = file.getParentFile().toPath();
        final List<ContentProviderOperation> res = new ArrayList<>();
        res.add(ContentProviderOperation.newDelete(getPlaylistMembersUri(uri)).build());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!TextUtils.isEmpty(line) && !line.startsWith("#")) {
                    final int itemIndex = res.size() + 1;
                    final File itemFile = parentPath.resolve(line).toFile();
                    try {
                        res.add(resolvePlaylistItem(resolver, uri, itemIndex, itemFile));
                    } catch (FileNotFoundException ignored) {
                    }
                }
            }
        }
        return res;
    }

    private static @NonNull List<ContentProviderOperation> resolvePlaylistPls(
            @NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File file)
            throws IOException {
        final Path parentPath = file.getParentFile().toPath();
        final List<ContentProviderOperation> res = new ArrayList<>();
        res.add(ContentProviderOperation.newDelete(getPlaylistMembersUri(uri)).build());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final Matcher matcher = PATTERN_PLS.matcher(line);
                if (matcher.matches()) {
                    final int itemIndex = Integer.parseInt(matcher.group(1));
                    final File itemFile = parentPath.resolve(matcher.group(2)).toFile();
                    try {
                        res.add(resolvePlaylistItem(resolver, uri, itemIndex, itemFile));
                    } catch (FileNotFoundException ignored) {
                    }
                }
            }
        }
        return res;
    }

    private static @NonNull List<ContentProviderOperation> resolvePlaylistWpl(
            @NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File file)
            throws IOException {
        final Path parentPath = file.getParentFile().toPath();
        final List<ContentProviderOperation> res = new ArrayList<>();
        res.add(ContentProviderOperation.newDelete(getPlaylistMembersUri(uri)).build());
        try (InputStream in = new FileInputStream(file)) {
            try {
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, StandardCharsets.UTF_8.name());

                int type;
                while ((type = parser.next()) != END_DOCUMENT) {
                    if (type != START_TAG) continue;

                    if (TAG_MEDIA.equals(parser.getName())) {
                        final String src = parser.getAttributeValue(null, ATTR_SRC);
                        if (src != null) {
                            final int itemIndex = res.size() + 1;
                            final File itemFile = parentPath.resolve(src).toFile();
                            try {
                                res.add(resolvePlaylistItem(resolver, uri, itemIndex, itemFile));
                            } catch (FileNotFoundException ignored) {
                            }
                        }
                    }
                }
            } catch (XmlPullParserException e) {
                throw new IOException(e);
            }
        }
        return res;
    }

    private static @Nullable ContentProviderOperation resolvePlaylistItem(
            @NonNull ContentResolver resolver, @NonNull Uri uri, int itemIndex, File itemFile)
            throws IOException {
        final Uri audioUri = MediaStore.Audio.Media.getContentUri(MediaStore.getVolumeName(uri));
        try (Cursor cursor = resolver.query(audioUri,
                new String[] { MediaColumns._ID }, MediaColumns.DATA + "=?",
                new String[] { itemFile.getCanonicalPath() }, null)) {
            if (!cursor.moveToFirst()) {
                throw new FileNotFoundException(uri.toString());
            }

            final ContentProviderOperation.Builder op = ContentProviderOperation
                    .newInsert(getPlaylistMembersUri(uri));
            op.withValue(MediaStore.Audio.Playlists.Members.PLAY_ORDER, itemIndex);
            op.withValue(MediaStore.Audio.Playlists.Members.AUDIO_ID, cursor.getInt(0));
            return op.build();
        }
    }

    private static @NonNull Uri getPlaylistMembersUri(@NonNull Uri uri) {
        return MediaStore.Audio.Playlists.Members.getContentUri(MediaStore.getVolumeName(uri),
                ContentUris.parseId(uri));
    }
}
