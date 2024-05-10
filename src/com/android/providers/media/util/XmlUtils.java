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

import android.util.Xml;

import androidx.annotation.NonNull;

import com.google.common.collect.Maps;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * XML utility methods.
 */
public class XmlUtils {
    private static final String FEATURE_NAME =
            "http://xmlpull.org/v1/doc/features.html#indent-output";
    private static final String TAG_NAME_MAP_ENTRY = "map_entry";
    private static final String ATTRIBUTE_NAME_MAP_ENTRY_KEY = "key";

    /**
     * Read a {@link Map}<{@link String}, {@link String}> from an {@link InputStream} containing
     * XML. The stream should previously have been written by
     * {@link #writeMapXml(Map, OutputStream)}, else it may return a {@link Maps#newHashMap()},
     * or throw a {@link RuntimeException}.
     *
     * @param in The {@link InputStream} from which to read.
     *
     * @return The resulting {@link Map}<{@link String}, {@link String}>.
     *
     * @see #writeMapXml(Map, OutputStream)
     */
    @NonNull
    public static Map<String, String> readMapXml(@NonNull InputStream in)
            throws IOException, XmlPullParserException {
        final Map<String, String> map = new HashMap<>();

        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, StandardCharsets.UTF_8.name());

        final StringBuilder mapEntryKey = new StringBuilder(), mapEntryValue = new StringBuilder();

        for (int eventType = parser.getEventType(); eventType != parser.END_DOCUMENT;
                eventType = parser.next()) {
            if (eventType == parser.START_TAG) {
                final String tagName = parser.getName();
                if (!Objects.equals(tagName, TAG_NAME_MAP_ENTRY)) {
                    throw new RuntimeException("Unexpected tag name: " + tagName);
                }

                mapEntryKey.append(parser.getAttributeValue(
                        /* namespace */ null, ATTRIBUTE_NAME_MAP_ENTRY_KEY));
            } else if (eventType == parser.TEXT) {
                mapEntryValue.append(parser.getText());
            } else if (eventType == parser.END_TAG) {
                if (mapEntryKey.length() > 0 && mapEntryValue.length() > 0) {
                    map.put(mapEntryKey.toString(), mapEntryValue.toString());
                }

                mapEntryKey.setLength(0);
                mapEntryValue.setLength(0);
            }
        }

        return map;
    }

    /**
     * Flatten a {@link Map}<{@link String}, {@link String}> into an {@link OutputStream} as XML.
     * The map can later be read back with {@link #readMapXml(InputStream)}.
     *
     * @param map The {@link Map}<{@link String}, {@link String}> to be flattened.
     * @param out The {@link OutputStream} where to write the XML data.
     *
     * @see #readMapXml(InputStream)
     */
    public static void writeMapXml(@NonNull Map<String, String> map, @NonNull OutputStream out)
            throws IOException {
        final XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());

        serializer.startDocument(/* encoding */ null, /* standalone */ true);
        serializer.setFeature(FEATURE_NAME, /* state */ true);

        for (Map.Entry<String, String> e : map.entrySet()) {
            serializer.startTag(/* namespace */ null, TAG_NAME_MAP_ENTRY);
            serializer.attribute(/* namespace */ null, ATTRIBUTE_NAME_MAP_ENTRY_KEY, e.getKey());

            final String val = e.getValue();
            if (val != null) {
                serializer.text(val);
            }

            serializer.endTag(/* namespace */ null, TAG_NAME_MAP_ENTRY);
        }

        serializer.endDocument();
    }
}
