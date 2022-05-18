/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.providers.media.photopicker.util;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;

import org.junit.Test;

public class MimeFilterUtilsTest {
    private static final String[] MEDIA_MIME_TYPES = new String[] {"image/*", "video/*"};

    @Test
    public void testRequiresMoreThanMediaItems_imagesType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "image/*";
        intent.setType(mimeType);

        assertThat(MimeFilterUtils.requiresMoreThanMediaItems(intent)).isFalse();
    }

    @Test
    public void testRequiresMoreThanMediaItems_videosType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "video/*";
        intent.setType(mimeType);

        assertThat(MimeFilterUtils.requiresMoreThanMediaItems(intent)).isFalse();
    }

    @Test
    public void testRequiresMoreThanMediaItems_allType() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "*/*";
        intent.setType(mimeType);

        assertThat(MimeFilterUtils.requiresMoreThanMediaItems(intent)).isTrue();
    }

    @Test
    public void testRequiresMoreThanMediaItems_extraMimeTypeImagesVideos() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "*/*";
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, MEDIA_MIME_TYPES);

        assertThat(MimeFilterUtils.requiresMoreThanMediaItems(intent)).isFalse();
    }

    @Test
    public void testRequiresMoreThanMediaItems_extraMimeTypeAllFiles() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "image/*";
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/mp4",
                "image/gif"});

        assertThat(MimeFilterUtils.requiresMoreThanMediaItems(intent)).isTrue();
    }

    @Test
    public void testIsMimeTypeMedia() {
        // Image mime types
        assertThat(MimeFilterUtils.isMimeTypeMedia("image/*")).isTrue();
        assertThat(MimeFilterUtils.isMimeTypeMedia("image/png")).isTrue();
        assertThat(MimeFilterUtils.isMimeTypeMedia("image/gif")).isTrue();

        // Video mime types
        assertThat(MimeFilterUtils.isMimeTypeMedia("video/*")).isTrue();
        assertThat(MimeFilterUtils.isMimeTypeMedia("video/mp4")).isTrue();
        assertThat(MimeFilterUtils.isMimeTypeMedia("video/dng")).isTrue();

        // Non-media mime types
        assertThat(MimeFilterUtils.isMimeTypeMedia("audio/*")).isFalse();
        assertThat(MimeFilterUtils.isMimeTypeMedia("*/*")).isFalse();
        assertThat(MimeFilterUtils.isMimeTypeMedia("test")).isFalse();
    }

    @Test
    public void testGetMimeTypeFilter() {
        Intent intent = new Intent();

        String mimeType = "image/*";
        intent.setType(mimeType);
        assertThat(MimeFilterUtils.getMimeTypeFilter(intent).equals(mimeType)).isTrue();

        mimeType = "video/mp4";
        intent.setType(mimeType);
        assertThat(MimeFilterUtils.getMimeTypeFilter(intent).equals(mimeType)).isTrue();

        mimeType = "*/*";
        intent.setType(mimeType);
        assertThat(MimeFilterUtils.getMimeTypeFilter(intent)).isNull();
    }
}
