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

import android.media.ExifInterface;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Parser for Extensible Metadata Platform (XMP) metadata. Designed to mirror
 * ergonomics of {@link ExifInterface}.
 * <p>
 * Since values can be repeated multiple times within the same XMP data, this
 * parser prefers the first valid definition of a specific value, and it ignores
 * any subsequent attempts to redefine that value.
 */
public class XmpInterface {
    private final String mFormat;
    private final String mDocumentId;
    private final String mInstanceId;
    private final String mOriginalDocumentId;

    private final @NonNull byte[] mRedactedXmp;

    private XmpInterface(String format, String documentId,
            String instanceId, String originalDocumentId, @NonNull byte[] redactedXmp) {
        mFormat = format;
        mDocumentId = documentId;
        mInstanceId = instanceId;
        mOriginalDocumentId = originalDocumentId;
        mRedactedXmp = redactedXmp;
    }

    static class Builder {
        String mFormat;
        String mDocumentId;
        String mInstanceId;
        String mOriginalDocumentId;
        byte[] mRedactedXmp;

        Builder format(String format) {
            mFormat = maybeOverride(mFormat, format);
            return this;
        }

        Builder documentId(String documentId) {
            mDocumentId = maybeOverride(mDocumentId, documentId);
            return this;
        }

        Builder instanceId(String instanceId) {
            mInstanceId = maybeOverride(mInstanceId, instanceId);
            return this;
        }

        Builder originalDocumentId(String originalDocumentId) {
            mOriginalDocumentId = maybeOverride(mOriginalDocumentId, originalDocumentId);
            return this;
        }

        Builder(byte[] redactedXmp) {
            mRedactedXmp = redactedXmp;
        }

        XmpInterface build() {
            return new XmpInterface(mFormat, mDocumentId, mInstanceId, mOriginalDocumentId,
                    mRedactedXmp);
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
}
