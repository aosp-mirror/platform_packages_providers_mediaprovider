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

package com.android.providers.media.util;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.ExifInterface;
import android.text.TextUtils;
import android.util.Xml;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Parser for Extensible Metadata Platform (XMP) metadata. Designed to mirror
 * ergonomics of {@link ExifInterface}.
 * <p>
 * Since values can be repeated multiple times within the same XMP data, this
 * parser prefers the first valid definition of a specific value, and it ignores
 * any subsequent attempts to redefine that value.
 */
public class XmpInterface {
    private static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_XMP = "http://ns.adobe.com/xap/1.0/";
    private static final String NS_XMPMM = "http://ns.adobe.com/xap/1.0/mm/";
    private static final String NS_DC = "http://purl.org/dc/elements/1.1/";

    private static final String NAME_DESCRIPTION = "Description";
    private static final String NAME_FORMAT = "format";
    private static final String NAME_DOCUMENT_ID = "DocumentID";
    private static final String NAME_ORIGINAL_DOCUMENT_ID = "OriginalDocumentID";
    private static final String NAME_INSTANCE_ID = "InstanceID";

    private String mFormat;
    private String mDocumentId;
    private String mInstanceId;
    private String mOriginalDocumentId;

    private XmpInterface(@NonNull InputStream in) throws IOException {
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != END_DOCUMENT) {
                if (type != START_TAG) continue;

                // The values we're interested in could be stored in either
                // attributes or tags, so we're willing to look for both

                final String ns = parser.getNamespace();
                final String name = parser.getName();

                if (NS_RDF.equals(ns) && NAME_DESCRIPTION.equals(name)) {
                    mFormat = maybeOverride(mFormat,
                            parser.getAttributeValue(NS_DC, NAME_FORMAT));
                    mDocumentId = maybeOverride(mDocumentId,
                            parser.getAttributeValue(NS_XMPMM, NAME_DOCUMENT_ID));
                    mInstanceId = maybeOverride(mInstanceId,
                            parser.getAttributeValue(NS_XMPMM, NAME_INSTANCE_ID));
                    mOriginalDocumentId = maybeOverride(mOriginalDocumentId,
                            parser.getAttributeValue(NS_XMPMM, NAME_ORIGINAL_DOCUMENT_ID));
                } else if (NS_DC.equals(ns) && NAME_FORMAT.equals(name)) {
                    mFormat = maybeOverride(mFormat, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_DOCUMENT_ID.equals(name)) {
                    mDocumentId = maybeOverride(mDocumentId, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_INSTANCE_ID.equals(name)) {
                    mInstanceId = maybeOverride(mInstanceId, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_ORIGINAL_DOCUMENT_ID.equals(name)) {
                    mOriginalDocumentId = maybeOverride(mOriginalDocumentId, parser.nextText());
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    public static @NonNull XmpInterface fromContainer(@NonNull InputStream is)
            throws IOException {
        return fromContainer(new ExifInterface(is));
    }

    public static @NonNull XmpInterface fromContainer(@NonNull ExifInterface exif)
            throws IOException {
        final byte[] buf;
        if (exif.hasAttribute(ExifInterface.TAG_XMP)) {
            buf = exif.getAttributeBytes(ExifInterface.TAG_XMP);
        } else {
            buf = EmptyArray.BYTE;
        }
        return new XmpInterface(new ByteArrayInputStream(buf));
    }

    public static @NonNull XmpInterface fromSidecar(@NonNull File file)
            throws IOException {
        return new XmpInterface(new FileInputStream(file));
    }

    private static @Nullable String maybeOverride(@Nullable String existing,
            @Nullable String current) {
        if (!TextUtils.isEmpty(existing)) {
            // If already defined, first definition always wins
            return existing;
        } else if (!TextUtils.isEmpty(current)) {
            // If current defined, it wins
            return current;
        } else {
            // Otherwise, null wins to prevent weird empty strings
            return null;
        }
    }

    public @Nullable String getFormat() {
        return mFormat;
    }

    public @Nullable String getDocumentId() {
        return mDocumentId;
    }

    public @Nullable String getInstanceId() {
        return mInstanceId;
    }

    public @Nullable String getOriginalDocumentId() {
        return mOriginalDocumentId;
    }
}
