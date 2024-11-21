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

package com.android.providers.media.photopicker.v2;

import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;

public class PickerUriResolverV2Test {
    @Test
    public void testMediaQuery() {
        assertEquals(
                PickerUriResolverV2.PICKER_INTERNAL_MEDIA,
                PickerUriResolverV2.sUriMatcher.match(
                        Uri.parse("content://media/picker_internal/v2/media"))
        );
    }

    @Test
    public void testAlbumQuery() {
        assertEquals(
                PickerUriResolverV2.PICKER_INTERNAL_ALBUM,
                PickerUriResolverV2.sUriMatcher.match(
                        Uri.parse("content://media/picker_internal/v2/album"))
        );
    }

    @Test
    public void testAlbumContentQuery() {
        assertEquals(
                PickerUriResolverV2.PICKER_INTERNAL_ALBUM_CONTENT,
                PickerUriResolverV2.sUriMatcher.match(
                        Uri.parse("content://media/picker_internal/v2/album/album_id"))
        );
    }

    @Test
    public void testAvailableProvidersQuery() {
        assertEquals(
                PickerUriResolverV2.PICKER_INTERNAL_AVAILABLE_PROVIDERS,
                PickerUriResolverV2.sUriMatcher.match(
                        Uri.parse("content://media/picker_internal/v2/available_providers"))
        );
    }

    @Test
    public void testSearchResultsQuery() {
        assertEquals(
                PickerUriResolverV2.PICKER_INTERNAL_SEARCH_MEDIA,
                PickerUriResolverV2.sUriMatcher.match(
                        Uri.parse("content://media/picker_internal/v2/search_media/132"))
        );
    }
}
