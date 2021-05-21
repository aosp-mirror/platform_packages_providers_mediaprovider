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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.photopicker.data.model.Item;

import java.util.ArrayList;
import java.util.Collections;
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
            @NonNull Item selectedItem) {
        return getPickerResponseIntent(context, Collections.singletonList(selectedItem));
    }

    /**
     * @return {@code Intent} which contains Uri that has been granted access on.
     * (TODO (b/169737798): Support Multi-select)
     */
    @NonNull
    public static Intent getPickerResponseIntent(@NonNull Context context,
            @NonNull List<Item> selectedItems) {
        // 1. Get mediaStore Uris corresponding to the selected items
        ArrayList<Uri> selectedUris = getUrisFromItems(selectedItems);

        // 2. Get redacted Uris for all selected items. We grant read access on redacted Uris for
        // initial release of the photo picker.
        ArrayList<Uri> redactedUris = new ArrayList<>(getRedactedUri(
                context.getContentResolver(), selectedUris));

        // 3. Grant read access to redacted Uris and return
        Intent intent = new Intent();
        if (selectedItems.size() == 1) {
            intent.setData(redactedUris.get(0));
        } else {
            // TODO (b/169737798): Support Multi-select
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        return intent;
    }

    private static List<Uri> getRedactedUri(ContentResolver contentResolver, ArrayList<Uri> uris) {
        if (SdkLevel.isAtLeastS()) {
            return getRedactedUriFromMediaStoreAPI(contentResolver, uris);
        } else {
            // TODO (b/168783994): directly call redacted uri code logic or explore other solution.
            // This will be addressed in a follow up CL.
            return new ArrayList<>();
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static List<Uri> getRedactedUriFromMediaStoreAPI(ContentResolver contentResolver,
            ArrayList<Uri> uris) {
        return MediaStore.getRedactedUri(contentResolver, uris);
    }

    /**
     * Returns list of {@link MediaStore} Uris corresponding to each {@link Item}
     *
     * @param ItemList list of Item for which we return uri list.
     */
    @NonNull
    private static ArrayList<Uri> getUrisFromItems(@NonNull List<Item> ItemList) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Item item : ItemList) {
            uris.add(item.getContentUri());
        }

        return uris;
    }
}
