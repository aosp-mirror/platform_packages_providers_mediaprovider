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

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.Process;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.v2.model.MediaSource;

public class PickerDataLayerV2 {
    static final String AVAILABLE_PROVIDER_AUTHORITY_COLUMN = "authority";
    static final String AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN = "media_source";
    static final String AVAILABLE_PROVIDERS_UID_COLUMN = "uid";

    private static final String[] AVAILABLE_PROVIDER_COLUMNS = new String[]{
            AVAILABLE_PROVIDER_AUTHORITY_COLUMN,
            AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN,
            AVAILABLE_PROVIDERS_UID_COLUMN,
    };

    static Cursor queryMedia(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    static Cursor queryAlbum(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    static Cursor queryAlbumContent(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }

    /**
     * @return a cursor with the available providers.
     */
    @NonNull
    public static Cursor queryAvailableProviders(@NonNull Context context) {
        try {
            final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
            final MatrixCursor matrixCursor =
                    new MatrixCursor(AVAILABLE_PROVIDER_COLUMNS, /*initialCapacity */ 2);
            final String localAuthority = syncController.getLocalProvider();
            addAvailableProvidersToCursor(matrixCursor,
                    localAuthority,
                    MediaSource.LOCAL,
                    Process.myUid());

            final String cloudAuthority = syncController.getCloudProvider();
            if (cloudAuthority != null) {
                final PackageManager packageManager = context.getPackageManager();
                final int uid = packageManager.getPackageUid(
                        packageManager
                                .resolveContentProvider(cloudAuthority, /* flags */ 0)
                                .packageName,
                        /* flags */ 0
                );
                addAvailableProvidersToCursor(
                        matrixCursor,
                        cloudAuthority,
                        MediaSource.REMOTE,
                        uid);
            }

            return matrixCursor;
        } catch (IllegalStateException | NameNotFoundException e) {
            throw new RuntimeException("Unexpected internal error occurred", e);
        }
    }

    private static void addAvailableProvidersToCursor(
            @NonNull MatrixCursor cursor,
            @NonNull String authority,
            @NonNull MediaSource source,
            @UserIdInt int uid) {
        cursor.newRow()
                .add(AVAILABLE_PROVIDER_AUTHORITY_COLUMN, authority)
                .add(AVAILABLE_PROVIDERS_MEDIA_SOURCE_COLUMN, source.name())
                .add(AVAILABLE_PROVIDERS_UID_COLUMN, uid);
    }

    /**
     * @return a Bundle with the details of the requested cloud provider.
     */
    public static Bundle getCloudProviderDetails(Bundle queryArgs) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
    }
}
