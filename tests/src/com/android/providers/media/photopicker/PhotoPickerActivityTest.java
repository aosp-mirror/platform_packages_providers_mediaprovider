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

package com.android.providers.media.photopicker;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.provider.MediaStore;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class PhotoPickerActivityTest {

    private static final int INTENT_FORWARDING_FLAGS =
            Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP;
    private static final String[] MEDIA_MIME_TYPES = new String[] {"image/*", "video/*"};

    @Test
    public void testGetGetContentIntent_pickImages_singleSelect() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);

        Intent getContentIntent = PhotoPickerActivity.getGetContentIntent(intent);

        assertThat(getContentIntent.getAction().equals(Intent.ACTION_GET_CONTENT)).isTrue();
        assertThat(getContentIntent.getType().equals("*/*")).isTrue();
        final String[] actualMimeType = getContentIntent.getStringArrayExtra(
                Intent.EXTRA_MIME_TYPES);
        assertThat(Arrays.equals(actualMimeType, MEDIA_MIME_TYPES)).isTrue();
        assertThat(getContentIntent.getFlags()).isEqualTo(INTENT_FORWARDING_FLAGS);

        assertThat(getContentIntent.hasExtra(Intent.EXTRA_ALLOW_MULTIPLE)).isFalse();
        assertThat(getContentIntent.hasExtra(Intent.EXTRA_LOCAL_ONLY)).isFalse();
    }

    @Test
    public void testGetGetContentIntent_pickImages_multiSelect() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());

        Intent getContentIntent = PhotoPickerActivity.getGetContentIntent(intent);

        assertThat(getContentIntent.getAction().equals(Intent.ACTION_GET_CONTENT)).isTrue();
        assertThat(getContentIntent.getType().equals("*/*")).isTrue();
        final String[] actualMimeType = getContentIntent.getStringArrayExtra(
                Intent.EXTRA_MIME_TYPES);
        assertThat(Arrays.equals(actualMimeType, MEDIA_MIME_TYPES)).isTrue();
        assertThat(getContentIntent.hasExtra(Intent.EXTRA_ALLOW_MULTIPLE)).isTrue();
        assertThat(getContentIntent.getFlags()).isEqualTo(INTENT_FORWARDING_FLAGS);

        assertThat(getContentIntent.hasExtra(Intent.EXTRA_LOCAL_ONLY)).isFalse();
    }

    @Test
    public void testGetGetContentIntent_pickImages_imageType() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        final String mimeType = "image/gif";
        intent.setType(mimeType);

        Intent getContentIntent = PhotoPickerActivity.getGetContentIntent(intent);

        assertThat(getContentIntent.getAction().equals(Intent.ACTION_GET_CONTENT)).isTrue();
        assertThat(getContentIntent.getType().equals(mimeType)).isTrue();
        assertThat(getContentIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).isNull();
        assertThat(getContentIntent.getFlags()).isEqualTo(INTENT_FORWARDING_FLAGS);

        assertThat(getContentIntent.hasExtra(Intent.EXTRA_ALLOW_MULTIPLE)).isFalse();
        assertThat(getContentIntent.hasExtra(Intent.EXTRA_LOCAL_ONLY)).isFalse();
    }

    @Test
    public void testGetGetContentIntent_getContent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        final String mimeType = "image/*";
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, MEDIA_MIME_TYPES);

        Intent getContentIntent = PhotoPickerActivity.getGetContentIntent(intent);

        assertThat(getContentIntent.equals(intent)).isTrue();
        assertThat(getContentIntent.getType().equals(mimeType)).isTrue();
        final String[] actualMimeType = getContentIntent.getStringArrayExtra(
                Intent.EXTRA_MIME_TYPES);
        assertThat(Arrays.equals(actualMimeType, MEDIA_MIME_TYPES)).isTrue();
        assertThat(getContentIntent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)).isTrue();
        assertThat(getContentIntent.getFlags()).isEqualTo(INTENT_FORWARDING_FLAGS);
    }
}
