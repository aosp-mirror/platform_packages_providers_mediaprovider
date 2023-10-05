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

package com.android.providers.media;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.CloudMediaProvider;
import android.provider.MediaStore;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import com.android.providers.media.cloudproviders.CloudProviderPrimary;
import com.android.providers.media.photopicker.PhotoPickerProvider;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.util.Optional;

/**
 * Class to support mocking Context class for tests.
 */
public class IsolatedContext extends ContextWrapper {
    private final File mDir;
    private final MockContentResolver mResolver;
    private final MediaProvider mMediaProvider;
    private final UserHandle mUserHandle;

    public IsolatedContext(Context base, String tag, boolean asFuseThread) {
        this(base, tag, asFuseThread, base.getUser());
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            UserHandle userHandle) {
        this(base, tag, asFuseThread, userHandle, new TestConfigStore());
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            UserHandle userHandle, ConfigStore configStore) {
        super(base);
        mDir = new File(base.getFilesDir(), tag);
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);

        mResolver = new MockContentResolver(this);
        mUserHandle = userHandle;

        mMediaProvider = getMockedMediaProvider(asFuseThread, configStore);
        attachInfoAndAddProvider(base, mMediaProvider, MediaStore.AUTHORITY);

        MediaDocumentsProvider documentsProvider = new MediaDocumentsProvider();
        attachInfoAndAddProvider(base, documentsProvider, MediaDocumentsProvider.AUTHORITY);

        mResolver.addProvider(Settings.AUTHORITY, new MockContentProvider() {
            @Override
            public Bundle call(String method, String request, Bundle args) {
                return Bundle.EMPTY;
            }
        });

        PhotoPickerProvider photoPickerProvider = new PhotoPickerProvider();
        attachInfoAndAddProvider(base, photoPickerProvider,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);

        final CloudMediaProvider cmp = new CloudProviderPrimary();
        attachInfoAndAddProvider(base, cmp, CloudProviderPrimary.AUTHORITY);

        MediaStore.waitForIdle(mResolver);
    }

    private MediaProvider getMockedMediaProvider(boolean asFuseThread,
            ConfigStore configStore) {
        return new MediaProvider() {
            @Override
            public boolean isFuseThread() {
                return asFuseThread;
            }

            @Override
            protected ConfigStore provideConfigStore() {
                return configStore;
            }

            @Override
            protected DatabaseBackupAndRecovery createDatabaseBackupAndRecovery() {
                return new TestDatabaseBackupAndRecovery(configStore, getVolumeCache());
            }

            @Override
            protected void storageNativeBootPropertyChangeListener() {
                // Ignore this as test app cannot read device config
            }
        };
    }

    @Override
    public File getDatabasePath(String name) {
        return new File(mDir, name);
    }

    @Override
    public ContentResolver getContentResolver() {
        return mResolver;
    }

    @Override
    public UserHandle getUser() {
        return mUserHandle;
    }

    public void setPickerUriResolver(PickerUriResolver resolver) {
        mMediaProvider.setUriResolver(resolver);
    }

    private void attachInfoAndAddProvider(Context base, ContentProvider provider,
            String authority) {
        final ProviderInfo info = base.getPackageManager().resolveContentProvider(authority, 0);
        provider.attachInfo(this, info);
        mResolver.addProvider(authority, provider);
    }

    /**
     * @return {@link DatabaseHelper} The external database helper used by the test {@link
     * IsolatedContext}
     */
    public DatabaseHelper getExternalDatabase() throws IllegalStateException {
        Optional<DatabaseHelper> helper =
                mMediaProvider.getDatabaseHelper(DatabaseHelper.EXTERNAL_DATABASE_NAME);
        if (helper.isPresent()) {
            return helper.get();
        } else {
            throw new IllegalStateException("Failed to get Database helper");
        }
    }

}
