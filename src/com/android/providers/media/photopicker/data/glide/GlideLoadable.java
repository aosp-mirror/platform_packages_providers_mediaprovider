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

package com.android.providers.media.photopicker.data.glide;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.data.model.Category;
import com.android.providers.media.photopicker.data.model.Item;

import com.bumptech.glide.signature.ObjectKey;

import java.util.Optional;

/**
 * A data class to coalesce {@link Item} and {@link Category} into a common loadable glide object,
 * with the relevant data required for Glide loading.
 */
public class GlideLoadable {

    private final Optional<String> mCacheKey;

    @NonNull private final Uri mUri;

    public GlideLoadable(@NonNull Uri uri) {
        this(uri, /* cacheKey= */ null);
    }

    public GlideLoadable(@NonNull Uri uri, @Nullable String cacheKey) {
        this.mUri = uri;
        this.mCacheKey = Optional.ofNullable(cacheKey);
    }

    /**
     * Get a signature string to represent this item in the Glide cache.
     *
     * @param prefix Optional prefix to prepend to this item's signature.
     * @return A glide cache signature string.
     */
    @Nullable
    public ObjectKey getLoadableSignature(@Nullable String prefix) {
        return new ObjectKey(
                Optional.ofNullable(prefix).orElse("") + mUri.toString() + mCacheKey.orElse(""));
    }
    ;

    /**
     * @return A {@link Uri} object to locate the media for this loadable.
     */
    public Uri getLoadableUri() {
        return mUri;
    }
    ;
}
