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

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.util.Xml;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

public class XspfPlaylistPersister implements PlaylistPersister {
    private static final String TAG_PLAYLIST = "playlist";
    private static final String TAG_TRACK_LIST = "trackList";
    private static final String TAG_TRACK = "track";
    private static final String TAG_LOCATION = "location";

    @Override
    public void read(@NonNull InputStream in, @NonNull List<Path> items) throws IOException {
        final FileSystem fs = FileSystems.getDefault();
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != END_DOCUMENT) {
                if (type != START_TAG) continue;

                if (TAG_LOCATION.equals(parser.getName())) {
                    final String src = parser.nextText();
                    if (src != null) {
                        items.add(fs.getPath(src.replace('\\', '/')));
                    }
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void write(@NonNull OutputStream out, @NonNull List<Path> items) throws IOException {
        final XmlSerializer doc = Xml.newSerializer();
        doc.setOutput(out, StandardCharsets.UTF_8.name());
        doc.startDocument(null, true);
        doc.startTag(null, TAG_PLAYLIST);
        doc.startTag(null, TAG_TRACK_LIST);
        for (Path item : items) {
            doc.startTag(null, TAG_TRACK);
            doc.startTag(null, TAG_LOCATION);
            doc.text(item.toString());
            doc.endTag(null, TAG_LOCATION);
            doc.endTag(null, TAG_TRACK);
        }
        doc.endTag(null, TAG_TRACK_LIST);
        doc.endTag(null, TAG_PLAYLIST);
        doc.endDocument();
    }
}
