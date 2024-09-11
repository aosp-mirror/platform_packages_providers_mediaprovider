/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class XmpDataParser implements Closeable {

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
    private final byte[] mRawXmp;
    private final long[] mXmpOffsets;
    private final ByteCountingInputStream mInput;
    private final XmlPullParser mParser;

    private XmpDataParser(byte[] rawXmp, long[] xmpOffsets) throws XmlPullParserException {
        mRawXmp = rawXmp;
        mXmpOffsets = xmpOffsets;
        mInput = new ByteCountingInputStream(new ByteArrayInputStream(rawXmp));
        mParser = Xml.newPullParser();
        mParser.setInput(mInput, StandardCharsets.UTF_8.name());
    }

    @Override
    public void close() throws IOException {
        mInput.close();
    }

    /**
     * Extract XMP data starting from an Iso container
     *
     * @throws IOException if errors were found while trying to read XMP data
     */
    public static XmpInterface createXmpInterface(IsoInterface iso)
            throws XmlPullParserException, IOException {
        XmpData data = XmpData.extractXmpData(iso);
        return createXmpInterface(data.mRawXmp, data.mXmpOffsets);
    }

    /**
     * Extract XMP data starting from an Exif container
     *
     * @throws IOException if errors were found while trying to read XMP data
     */
    public static XmpInterface createXmpInterface(ExifInterface exif)
            throws XmlPullParserException, IOException {
        XmpData data = XmpData.extractXmpData(exif);
        return createXmpInterface(data.mRawXmp, data.mXmpOffsets);
    }


    // The original value of rawXMP gets edited here, do we need preserving
    private static XmpInterface createXmpInterface(@NonNull byte[] rawXmp, @NonNull long[] offsets)
            throws IOException {
        try (XmpDataParser xmpDataParser = new XmpDataParser(rawXmp, offsets)) {
            return xmpDataParser.createXmpInterface();
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Couldn't read large xmp", e);
            throw new IOException(e);
        }
    }

    private XmpInterface createXmpInterface() throws XmlPullParserException, IOException {
        long offset = 0;
        int type;
        XmpInterface.Builder builder = new XmpInterface.Builder(mRawXmp);
        while ((type = mParser.next()) != END_DOCUMENT) {
            if (type != START_TAG) {
                offset = mInput.getOffset(mParser);
                continue;
            }
            // The values we're interested in could be stored in either
            // attributes or tags, so we're willing to look for both
            final String ns = mParser.getNamespace();
            final String name = mParser.getName();

            if (NS_RDF.equals(ns) && NAME_DESCRIPTION.equals(name)) {
                builder.format(mParser.getAttributeValue(NS_DC, NAME_FORMAT))
                        .documentId(mParser.getAttributeValue(
                                NS_XMPMM, NAME_DOCUMENT_ID))
                        .instanceId(mParser.getAttributeValue(
                                NS_XMPMM, NAME_INSTANCE_ID))
                        .originalDocumentId(
                                mParser.getAttributeValue(
                                        NS_XMPMM, NAME_ORIGINAL_DOCUMENT_ID));
            } else if (NS_DC.equals(ns) && NAME_FORMAT.equals(name)) {
                builder.format(mParser.nextText());
            } else if (NS_XMPMM.equals(ns) && NAME_DOCUMENT_ID.equals(name)) {
                builder.documentId(mParser.nextText());
            } else if (NS_XMPMM.equals(ns) && NAME_INSTANCE_ID.equals(name)) {
                builder.instanceId(mParser.nextText());
            } else if (NS_XMPMM.equals(ns)
                    && NAME_ORIGINAL_DOCUMENT_ID.equals(name)) {
                builder.originalDocumentId(mParser.nextText());
            } else if (NS_EXIF.equals(ns) && RedactionUtils.getsRedactedExifTags()
                    .contains(name)) {
                long start = offset;
                do {
                    type = mParser.next();
                } while (type != END_TAG || !mParser.getName().equals(name));
                offset = mInput.getOffset(mParser);

                // Redact range within local copy
                Arrays.fill(builder.mRedactedXmp, (int) start, (int) offset, (byte) ' ');
            }
        }
        return builder.build();
    }

    /**
     * Returns ranges to be redacted from an Exif file
     */
    public static LongArray getRedactionRanges(ExifInterface exifInterface) throws IOException {
        return getRedactionRanges(XmpData.extractXmpData(exifInterface));
    }

    /**
     * Returns ranges to be redacted from an Iso file
     */
    public static LongArray getRedactionRanges(IsoInterface isoInterface) throws IOException {
        return getRedactionRanges(XmpData.extractXmpData(isoInterface));
    }

    //    /** The [start, end] offsets in the original file where to-be redacted info is
    //    stored */
    private static LongArray getRedactionRanges(XmpData xmpData) throws IOException {
        try (XmpDataParser parser = new XmpDataParser(xmpData.mRawXmp, xmpData.mXmpOffsets)) {
            return parser.getRedactedRanges();
        } catch (XmlPullParserException e) {
            // Flag all XMP up for redaction
            long xmpOffset = xmpData.mXmpOffsets.length == 0 ? 0 : xmpData.mXmpOffsets[0];
            LongArray redactedRanges = new LongArray();
            redactedRanges.add(xmpOffset);
            redactedRanges.add(xmpOffset + xmpData.mRawXmp.length);
            return redactedRanges;
        }
    }

    private LongArray getRedactedRanges() throws XmlPullParserException, IOException {
        final long xmpOffset = mXmpOffsets.length == 0 ? 0 : mXmpOffsets[0];
        long offset = 0;
        int type;
        LongArray redactedRanges = new LongArray();
        while ((type = mParser.next()) != END_DOCUMENT) {
            if (type != START_TAG) {
                offset = mInput.getOffset(mParser);
                continue;
            }
            // The values we're interested in could be stored in either
            // attributes or tags, so we're willing to look for both
            final String ns = mParser.getNamespace();
            final String name = mParser.getName();
            if (NS_EXIF.equals(ns) && RedactionUtils.getsRedactedExifTags()
                    .contains(name)) {
                long start = offset;
                do {
                    type = mParser.next();
                } while (type != END_TAG || !mParser.getName().equals(name));
                offset = mInput.getOffset(mParser);

                // Redact range within entire file
                redactedRanges.add(xmpOffset + start);
                redactedRanges.add(xmpOffset + offset);
            }
        }
        return redactedRanges;
    }

    private static class XmpData {
        @NonNull
        private final byte[] mRawXmp;
        @NonNull
        private final long[] mXmpOffsets;

        XmpData(@NonNull byte[] raw, @NonNull long[] offsets) {
            mRawXmp = raw;
            mXmpOffsets = offsets;
        }

        static @NonNull XmpData extractXmpData(@NonNull IsoInterface iso) {
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
            return new XmpData(buf, xmpOffsets);
        }

        static @NonNull XmpData extractXmpData(@NonNull ExifInterface exif) {
            if (exif.hasAttribute(ExifInterface.TAG_XMP)) {
                final byte[] buf = exif.getAttributeBytes(ExifInterface.TAG_XMP);
                long[] xmpOffsets = exif.getAttributeRange(ExifInterface.TAG_XMP);
                if (buf != null && xmpOffsets != null) {
                    return new XmpData(buf, xmpOffsets);
                }
            }
            return new XmpData(new byte[0], new long[0]);
        }
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

        /**
         * From an active XmlPullParser, returns the offset of the current element
         */
        @VisibleForTesting
        public long getOffset(XmlPullParser parser) {
            int line = parser.getLineNumber() - 1; // getLineNumber is 1-based
            long lineOffset = line == 0 ? 0 : mOffsets.get(line - 1);
            int columnOffset =
                    parser.getColumnNumber() - 1; // meant to be 0-based, but is 1-based?
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
