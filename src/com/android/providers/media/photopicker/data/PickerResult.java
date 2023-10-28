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

package com.android.providers.media.photopicker.data;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is responsible for returning result to the caller of the PhotoPicker.
 */
public class PickerResult {

    /**
     * @return {@code Intent} which contains Uri that has been granted access on.
     */
    @NonNull
    public static Intent getPickerResponseIntent(boolean canSelectMultiple,
            @NonNull List<Item> selectedItems) {
        // 1. Get Picker Uris corresponding to the selected items
        List<Uri> selectedUris = getPickerUrisForItems(selectedItems);

        // 2. Grant read access to picker Uris and return
        Intent intent = new Intent();
        final int size = selectedUris.size();
        if (size < 1) {
            // TODO (b/168783994): check if this is ever possible. If yes, handle properly,
            // if not, remove this if block.
            return intent;
        }
        if (!canSelectMultiple) {
            intent.setData(selectedUris.get(0));
        }
        // TODO (b/169737761): use correct mime types
        String[] mimeTypes = new String[]{"image/*", "video/*"};
        final ClipData clipData = new ClipData(null /* label */, mimeTypes,
                new ClipData.Item(selectedUris.get(0)));
        for (int i = 1; i < size; i++) {
            clipData.addItem(new ClipData.Item(selectedUris.get(i)));
        }
        intent.setClipData(clipData);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        return intent;
    }

    @VisibleForTesting
    static Uri getPickerUri(Uri uri) {
        final String userInfo = uri.getUserInfo();
        final String userId = userInfo == null ? UserId.CURRENT_USER.toString() : userInfo;
        return PickerUriResolver.wrapProviderUri(uri, Integer.parseInt(userId));
    }

    /**
     * Returns list of PhotoPicker Uris corresponding to each {@link Item}
     *
     * @param items list of Item for which we return uri list.
     */
    @NonNull
    public static List<Uri> getPickerUrisForItems(@NonNull List<Item> items) {
        List<Uri> uris = new ArrayList<>();
        for (Item item : items) {
            uris.add(getPickerUri(item.getContentUri()));
        }

        return uris;
    }

}
