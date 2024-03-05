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
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.media.ExifInterface;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Parser for Extensible Metadata Platform (XMP) metadata. Designed to mirror
 * ergonomics of {@link ExifInterface}.
 * <p>
 * Since values can be repeated multiple times within the same XMP data, this
 * parser prefers the first valid definition of a specific value, and it ignores
 * any subsequent attempts to redefine that value.
 */
public class XmpInterface {
    private static final String TAG = "XmpInterface";
    private static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_XMPMM = "http://ns.adobe.com/xap/1.0/mm/";
    private static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    private static final String NS_EXIF = "http://ns.adobe.com/exif/1.0/";

    private static final String NAME_DESCRIPTION = "Description";
    private static final String NAME_FORMAT = "format";
    private static final String NAME_DOCUMENT_ID = "DocumentID";
    private static final String NAME_ORIGINAL_DOCUMENT_ID = "OriginalDocumentID";
    private static final String NAME_INSTANCE_ID = "InstanceID";

    private final String mFormat;
    private final String mDocumentId;
    private final String mInstanceId;
    private final String mOriginalDocumentId;

    private final LongArray mRedactedRanges;

    private final @NonNull byte[] mRedactedXmp;

    private XmpInterface(String format, String documentId,
            String instanceId, String originalDocumentId, @NonNull byte[] redactedXmp,
            @NonNull LongArray redactedRanges) {
        mFormat = format;
        mDocumentId = documentId;
        mInstanceId = instanceId;
        mOriginalDocumentId = originalDocumentId;
        mRedactedXmp = redactedXmp;
        mRedactedRanges = redactedRanges;
    }

    // The original value of rawXMP gets edited here, do we need preserving
    private static XmpInterface createXmpInterface(@NonNull byte[] rawXmp,
            @NonNull long[] xmpOffsets) throws IOException {

        // Core data
        String format = null;
        String documentId = null;
        String instanceId = null;
        String originalDocumentId = null;

        // Redaction stuff
        final long xmpOffset = xmpOffsets.length == 0 ? 0 : xmpOffsets[0];
        LongArray redactedRanges = new LongArray();

        try (ByteCountingInputStream in = new ByteCountingInputStream(
                new ByteArrayInputStream(rawXmp))) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            long offset = 0;
            int type;
            while ((type = parser.next()) != END_DOCUMENT) {
                if (type != START_TAG) {
                    offset = in.getOffset(parser);
                    continue;
                }

                // The values we're interested in could be stored in either
                // attributes or tags, so we're willing to look for both

                final String ns = parser.getNamespace();
                final String name = parser.getName();

                if (NS_RDF.equals(ns) && NAME_DESCRIPTION.equals(name)) {
                    format = maybeOverride(format,
                            parser.getAttributeValue(NS_DC, NAME_FORMAT));
                    documentId = maybeOverride(documentId,
                            parser.getAttributeValue(NS_XMPMM, NAME_DOCUMENT_ID));
                    instanceId = maybeOverride(instanceId,
                            parser.getAttributeValue(NS_XMPMM, NAME_INSTANCE_ID));
                    originalDocumentId = maybeOverride(originalDocumentId,
                            parser.getAttributeValue(NS_XMPMM, NAME_ORIGINAL_DOCUMENT_ID));
                } else if (NS_DC.equals(ns) && NAME_FORMAT.equals(name)) {
                    format = maybeOverride(format, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_DOCUMENT_ID.equals(name)) {
                    documentId = maybeOverride(documentId, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_INSTANCE_ID.equals(name)) {
                    instanceId = maybeOverride(instanceId, parser.nextText());
                } else if (NS_XMPMM.equals(ns) && NAME_ORIGINAL_DOCUMENT_ID.equals(name)) {
                    originalDocumentId = maybeOverride(originalDocumentId, parser.nextText());
                } else if (NS_EXIF.equals(ns) && RedactionUtils.getsRedactedExifTags()
                        .contains(name)) {
                    long start = offset;
                    do {
                        type = parser.next();
                    } while (type != END_TAG || !parser.getName().equals(name));
                    offset = in.getOffset(parser);

                    // Redact range within entire file
                    redactedRanges.add(xmpOffset + start);
                    redactedRanges.add(xmpOffset + offset);

                    // Redact range within local copy
                    Arrays.fill(rawXmp, (int) start, (int) offset, (byte) ' ');
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Couldn't read large xmp", e);
            throw new IOException(e);
        }
        return new XmpInterface(format, documentId, instanceId, originalDocumentId, rawXmp,
                redactedRanges);
    }

    /**
     * Extract XMP data starting from an Exif container
     * @throws IOException if errors were found while trying to read XMP data
     */
    public static @NonNull XmpInterface createXmpInterface(@NonNull ExifInterface exif)
            throws IOException {
        if (exif.hasAttribute(ExifInterface.TAG_XMP)) {
            final byte[] buf = exif.getAttributeBytes(ExifInterface.TAG_XMP);
            long[] xmpOffsets = exif.getAttributeRange(ExifInterface.TAG_XMP);
            if (buf != null && xmpOffsets != null) {
                return createXmpInterface(buf, xmpOffsets);
            }
        }
        return createXmpInterface(new byte[0], new long[0]);
    }

    /**
     * Extract XMP data starting from an Iso container
     * @throws IOException if errors were found while trying to read XMP data
     */
    public static @NonNull XmpInterface createXmpInterface(@NonNull IsoInterface iso)
            throws IOException {
        UUID uuid = UUID.fromString("be7acfcb-97a9-42e8-9c71-999491e3afac");
        byte[] buf = iso.getBoxBytes(uuid);
        long[] xmpOffsets = iso.getBoxRanges(uuid);

        if (buf == null) {
            buf = iso.getBoxBytes(IsoInterface.BOX_XMP);
            xmpOffsets = iso.getBoxRanges(IsoInterface.BOX_XMP);
        }
        if (buf == null) {
            buf = new byte[0];
            xmpOffsets = new long[0];
        }
        return createXmpInterface(buf, xmpOffsets);
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

    public @NonNull byte[] getRedactedXmp() {
        return mRedactedXmp;
    }

    /** The [start, end] offsets in the original file where to-be redacted info is stored */
    public LongArray getRedactionRanges() {
        return mRedactedRanges;
    }

    @VisibleForTesting
    public static class ByteCountingInputStream extends InputStream {
        private final InputStream mWrapped;
        private final LongArray mOffsets;
        private int mLine;
        private int mOffset;

        public ByteCountingInputStream(InputStream wrapped) {
            mWrapped = wrapped;
            mOffsets = new LongArray();
            mLine = 1;
            mOffset = 0;
        }

        public long getOffset(XmlPullParser parser) {
            int line = parser.getLineNumber() - 1; // getLineNumber is 1-based
            long lineOffset = line == 0 ? 0 : mOffsets.get(line - 1);
            int columnOffset = parser.getColumnNumber() - 1; // meant to be 0-based, but is 1-based?
            return lineOffset + columnOffset;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            final int read = mWrapped.read(b, off, len);
            if (read == -1) return -1;

            for (int i = 0; i < read; i++) {
                if (b[off + i] == '\n') {
                    mOffsets.add(mLine - 1, mOffset + i + 1);
                    mLine++;
                }
            }
            mOffset += read;
            return read;
        }

        @Override
        public int read() throws IOException {
            int r = mWrapped.read();
            if (r == -1) return -1;

            mOffset++;
            if (r == '\n') {
                mOffsets.add(mLine - 1, mOffset);
                mLine++;
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            return super.skip(n);
        }

        @Override
        public int available() throws IOException {
            return mWrapped.available();
        }

        @Override
        public void close() throws IOException {
            mWrapped.close();
        }

        @Override
        public String toString() {
            return java.util.Arrays.toString(mOffsets.toArray());
        }
    }
}
