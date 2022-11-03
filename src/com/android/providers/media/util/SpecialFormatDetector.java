/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.os.Trace;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Class to detect and return special format for a media file.
 */
public class SpecialFormatDetector {
    private static final String TAG = "SpecialFormatDetector";
    // These are the known MotionPhoto attribute names
    private static final String[] MOTION_PHOTO_ATTRIBUTE_NAMES = {
            "Camera:MotionPhoto", // Motion Photo V1
            "GCamera:MotionPhoto", // Motion Photo V1 (legacy element naming)
            "Camera:MicroVideo", // Micro Video V1b
            "GCamera:MicroVideo", // Micro Video V1b (legacy element naming)
    };

    private static final String[] DESCRIPTION_MICRO_VIDEO_OFFSET_ATTRIBUTE_NAMES = {
            "Camera:MicroVideoOffset", // Micro Video V1b
            "GCamera:MicroVideoOffset", // Micro Video V1b (legacy element naming)
    };

    private static final String XMP_META_TAG = "x:xmpmeta";
    private static final String XMP_RDF_DESCRIPTION_TAG = "rdf:Description";

    private static final String XMP_CONTAINER_PREFIX = "Container";
    private static final String XMP_GCONTAINER_PREFIX = "GContainer";

    private static final String XMP_ITEM_PREFIX = "Item";
    private static final String XMP_GCONTAINER_ITEM_PREFIX =
            XMP_GCONTAINER_PREFIX + XMP_ITEM_PREFIX;

    private static final String XMP_DIRECTORY_TAG = ":Directory";
    private static final String XMP_CONTAINER_DIRECTORY_PREFIX =
            XMP_CONTAINER_PREFIX + XMP_DIRECTORY_TAG;
    private static final String XMP_GCONTAINER_DIRECTORY_PREFIX =
            XMP_GCONTAINER_PREFIX + XMP_DIRECTORY_TAG;

    private static final String SEMANTIC_PRIMARY = "Primary";
    private static final String SEMANTIC_MOTION_PHOTO = "MotionPhoto";

    /**
     * {@return} special format for a file
     */
    public static int detect(File file) throws Exception {
        try (FileInputStream is = new FileInputStream(file)) {
            final ExifInterface exif = new ExifInterface(is);
            return detect(exif, file);
        }
    }

    /**
     * {@return} special format for a file
     */
    public static int detect(ExifInterface exif, File file) throws Exception {
        if (isMotionPhoto(exif)) {
            return FileColumns._SPECIAL_FORMAT_MOTION_PHOTO;
        }

        return detectGifOrAnimatedWebp(file);
    }

    /**
     * Checks file metadata to detect if the given file is a GIF or Animated Webp.
     *
     * Note: This does not respect file extension.
     *
     * @return {@link FileColumns#_SPECIAL_FORMAT_GIF} if the file is a GIF file or
     *         {@link FileColumns#_SPECIAL_FORMAT_ANIMATED_WEBP} if the file is an Animated Webp
     *         file. Otherwise returns {@link FileColumns#_SPECIAL_FORMAT_NONE}
     */
    private static int detectGifOrAnimatedWebp(File file) throws IOException {
        final BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
        // Set options such that the image is not decoded to a bitmap, as we only want mimetype
        // options
        bitmapOptions.inSampleSize = 1;
        bitmapOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bitmapOptions);

        if (bitmapOptions.outMimeType.equalsIgnoreCase("image/gif")) {
            return FileColumns._SPECIAL_FORMAT_GIF;
        }
        if (bitmapOptions.outMimeType.equalsIgnoreCase("image/webp") &&
                isAnimatedWebp(file)) {
            return FileColumns._SPECIAL_FORMAT_ANIMATED_WEBP;
        }
        return FileColumns._SPECIAL_FORMAT_NONE;
    }

    private static boolean isAnimatedWebp(File file) throws IOException {
        final ImageDecoder.Source source = ImageDecoder.createSource(file);
        final Drawable drawable = ImageDecoder.decodeDrawable(source);
        return (drawable instanceof AnimatedImageDrawable);
    }

    private static boolean isMotionPhoto(ExifInterface exif) throws Exception {
        if (!exif.hasAttribute(ExifInterface.TAG_XMP)) {
            return false;
        }
        final String xmp = new String(exif.getAttributeBytes(ExifInterface.TAG_XMP),
                StandardCharsets.UTF_8);

        // The below logic is copied from ExoPlayer#XmpMotionPhotoDescriptionParser class
        XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
        XmlPullParser xpp = xmlPullParserFactory.newPullParser();
        xpp.setInput(new StringReader(xmp));
        xpp.next();
        if (!isStartTag(xpp, XMP_META_TAG)) {
            Log.d(TAG, "Couldn't find xmp metadata");
            return false;
        }

        Trace.beginSection("FormatDetector.motionPhotoDetectionUsingXpp");
        try {
            return isMotionPhoto(xpp);
        } finally {
            Trace.endSection();
        }
    }

    private static boolean isMotionPhoto(XmlPullParser xpp) throws Exception {
        boolean isMotionPhotoAttributesFound = false;

        do {
            xpp.next();
            if (!isStartTag(xpp)) {
                continue;
            }

            switch (xpp.getName()) {
                case XMP_RDF_DESCRIPTION_TAG:
                    if (!isMotionPhotoFlagSet(xpp)) {
                        // The motion photo flag is not set, so the file should not be treated as a
                        // motion photo.
                        return false;
                    }
                    isMotionPhotoAttributesFound = isMicroVideoPresent(xpp);
                    break;
                case XMP_CONTAINER_DIRECTORY_PREFIX:
                    isMotionPhotoAttributesFound = isMotionPhotoDirectory(xpp, XMP_CONTAINER_PREFIX,
                        XMP_ITEM_PREFIX);
                    break;
                case XMP_GCONTAINER_DIRECTORY_PREFIX:
                    isMotionPhotoAttributesFound = isMotionPhotoDirectory(xpp,
                            XMP_GCONTAINER_PREFIX, XMP_GCONTAINER_ITEM_PREFIX);
                    break;
                default: // do nothing
            }

            // Return early if motion photo attributes were found in the xpp,
            // otherwise continue looking
            if (isMotionPhotoAttributesFound) {
                return true;
            }

        } while (!isEndTag(xpp, XMP_META_TAG) && xpp.getEventType() != XmlPullParser.END_DOCUMENT);

        return false;
    }

    private static boolean isMotionPhotoDirectory(XmlPullParser xpp,
            String containerNamespacePrefix, String itemNamespacePrefix)
            throws XmlPullParserException, IOException {
        final String itemTagName = containerNamespacePrefix + ":Item";
        final String directoryTagName = containerNamespacePrefix + ":Directory";
        final String mimeAttributeName = itemNamespacePrefix + ":Mime";
        final String semanticAttributeName = itemNamespacePrefix + ":Semantic";
        final String lengthAttributeName = itemNamespacePrefix + ":Length";
        boolean isPrimaryImagePresent = false;
        boolean isMotionPhotoPresent = false;

        do {
            xpp.next();
            if (!isStartTag(xpp, itemTagName)) {
                continue;
            }

            String semantic = getAttributeValue(xpp, semanticAttributeName);
            if (getAttributeValue(xpp, mimeAttributeName) == null || semantic == null) {
                // Required values are missing.
                return false;
            }

            switch (semantic) {
                case SEMANTIC_PRIMARY:
                    isPrimaryImagePresent = true;
                    break;
                case SEMANTIC_MOTION_PHOTO:
                    String length = getAttributeValue(xpp, lengthAttributeName);
                    isMotionPhotoPresent = (length != null && Integer.parseInt(length) > 0);
                    break;
                default: // do nothing
            }

            if (isMotionPhotoPresent && isPrimaryImagePresent) {
                return true;
            }
        } while (!isEndTag(xpp, directoryTagName) &&
                xpp.getEventType() != XmlPullParser.END_DOCUMENT);
        // We need a primary item (photo) and at least one secondary item (video).
        return false;
    }

    private static boolean isMicroVideoPresent(XmlPullParser xpp) {
        for (String attributeName : DESCRIPTION_MICRO_VIDEO_OFFSET_ATTRIBUTE_NAMES) {
            String attributeValue = getAttributeValue(xpp, attributeName);
            if (attributeValue != null) {
                long microVideoOffset = Long.parseLong(attributeValue);
                return microVideoOffset > 0;
            }
        }
        return false;
    }

    private static boolean isMotionPhotoFlagSet(XmlPullParser xpp) {
        for (String attributeName : MOTION_PHOTO_ATTRIBUTE_NAMES) {
            String attributeValue = getAttributeValue(xpp, attributeName);
            if (attributeValue != null) {
                int motionPhotoFlag = Integer.parseInt(attributeValue);
                return motionPhotoFlag == 1;
            }
        }
        return false;
    }

    private static String getAttributeValue(XmlPullParser xpp, String attributeName) {
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            if (xpp.getAttributeName(i).equals(attributeName)) {
                return xpp.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Returns whether the current event is an end tag with the specified name.
     *
     * @param xpp The {@link XmlPullParser} to query.
     * @param name The specified name.
     * @return Whether the current event is an end tag.
     * @throws XmlPullParserException If an error occurs querying the parser.
     */
    private static boolean isEndTag(XmlPullParser xpp, String name) throws XmlPullParserException {
        return xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(name);
    }

    /**
     * Returns whether the current event is a start tag with the specified name.
     *
     * @param xpp The {@link XmlPullParser} to query.
     * @param name The specified name.
     * @return Whether the current event is a start tag with the specified name.
     * @throws XmlPullParserException If an error occurs querying the parser.
     */
    private static boolean isStartTag(XmlPullParser xpp, String name)
            throws XmlPullParserException {
        return isStartTag(xpp) && xpp.getName().equals(name);
    }

    private static boolean isStartTag(XmlPullParser xpp) throws XmlPullParserException {
        return xpp.getEventType() == XmlPullParser.START_TAG;
    }
}
