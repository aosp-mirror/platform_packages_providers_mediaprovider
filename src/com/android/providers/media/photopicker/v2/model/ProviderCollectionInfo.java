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

package com.android.providers.media.photopicker.v2.model;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ProviderCollectionInfo implements Cloneable {
    @NonNull
    private final String mAuthority;
    @Nullable
    private String mCollectionId;
    @Nullable
    private String mAccountName;
    @Nullable
    private Intent mAccountConfigurationIntent;

    public ProviderCollectionInfo(String authority) {
        this(
                authority,
                /* collectionId */ null,
                /* accountName */ null,
                /* accountConfigurationIntent */ null
        );
    }

    public ProviderCollectionInfo(
            @NonNull String authority,
            @Nullable String collectionId,
            @Nullable String accountName,
            @Nullable Intent accountConfigurationIntent) {
        mAuthority = authority;
        mCollectionId = collectionId;
        mAccountName = accountName;
        mAccountConfigurationIntent = accountConfigurationIntent;
    }

    @Nullable
    public String getAuthority() {
        return mAuthority;
    }

    @Nullable
    public String getCollectionId() {
        return mCollectionId;
    }

    @Nullable
    public String getAccountName() {
        return mAccountName;
    }

    public Intent getAccountConfigurationIntent() {
        return mAccountConfigurationIntent;
    }

    @Override
    public String toString() {
        return "ProviderCollectionInfo = { "
                + " authority = " + mAuthority
                + ", collectionId = " + mCollectionId
                + ", accountName = " + mAccountName
                + " }";
    }

    @Override
    public Object clone() {
        return new ProviderCollectionInfo(
                this.mAuthority,
                this.mCollectionId,
                this.mAccountName,
                this.mAccountConfigurationIntent
        );
    }
}
