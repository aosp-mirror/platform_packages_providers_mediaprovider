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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;

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
    public static Intent getPickerResponseIntent(@NonNull Context context,
            @NonNull List<Item> selectedItems) {
        // 1. Get Picker Uris corresponding to the selected items
        ArrayList<Uri> selectedUris = getPickerUrisForItems(selectedItems);

        // 2. Grant read access to picker Uris and return
        Intent intent = new Intent();
        final int size = selectedUris.size();
        if (size == 1) {
            intent.setData(selectedUris.get(0));
        } else if (size > 1) {
            // TODO (b/169737761): use correct mime types
            String[] mimeTypes = new String[]{"image/*", "video/*"};
            final ClipData clipData = new ClipData(null /* label */, mimeTypes,
                    new ClipData.Item(selectedUris.get(0)));
            for (int i = 1; i < size; i++) {
                clipData.addItem(new ClipData.Item(selectedUris.get(i)));
            }
            intent.setClipData(clipData);
        } else {
            // TODO (b/168783994): check if this is ever possible. If yes, handle properly,
            // if not, change the above "else if" block to "else" block.
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        return intent;
    }

    private static Uri getPickerUri(Uri uri, long id) {
        final String userInfo = uri.getUserInfo();
        final String userId = userInfo == null ? UserId.CURRENT_USER.toString() : userInfo;
        final Uri uriWithUserId =
                PickerUriResolver.URI_PREFIX.buildUpon().appendPath(userId).build();
        return uriWithUserId.buildUpon().appendPath(String.valueOf(id)).build();
    }

    /**
     * Returns list of PhotoPicker Uris corresponding to each {@link Item}
     *
     * @param ItemList list of Item for which we return uri list.
     */
    @NonNull
    private static ArrayList<Uri> getPickerUrisForItems(@NonNull List<Item> ItemList) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Item item : ItemList) {
            uris.add(getPickerUri(item.getContentUri(), item.getId()));
        }

        return uris;
    }
}
