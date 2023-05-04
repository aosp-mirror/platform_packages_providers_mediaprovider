/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.provider.MediaStore;

import com.android.providers.media.fuse.FuseDaemon;
import com.android.providers.media.stableuris.dao.BackupIdRow;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class TestDatabaseBackupAndRecovery extends DatabaseBackupAndRecovery {

    private Map<String, BackupIdRow> mBackedUpData = new HashMap<>();

    public TestDatabaseBackupAndRecovery(ConfigStore configStore, VolumeCache volumeCache,
            Map<String, BackupIdRow> backedUpData) {
        super(configStore, volumeCache);
        this.mBackedUpData = backedUpData;
    }

    public TestDatabaseBackupAndRecovery(ConfigStore configStore, VolumeCache volumeCache) {
        this(configStore, volumeCache, null);
    }

    @Override
    protected void updateNextRowIdXattr(DatabaseHelper helper, long id) {
        // Ignoring this as test app would not have access to update xattr.
    }

    @Override
    protected boolean isStableUrisEnabled(String volumeName) {
        if (MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
            return true;
        }
        return false;
    }

    @Override
    protected String[] readBackedUpFilePaths(String volumeName, String lastReadValue,
            int limit) {
        Object[] backedUpValues = mBackedUpData.keySet().toArray();
        return Arrays.copyOf(backedUpValues, backedUpValues.length, String[].class);
    }

    @Override
    protected Optional<BackupIdRow> readDataFromBackup(String volumeName,
            String filePath) {
        return Optional.ofNullable(mBackedUpData.get(filePath));
    }

    @Override
    protected boolean isBackupPresent() {
        return true;
    }

    @Override
    protected FuseDaemon getFuseDaemonForFileWithWait(File fuseFilePath, long waitTime)
            throws FileNotFoundException {
        return null;
    }

    @Override
    protected void setupVolumeDbBackupAndRecovery(String volumeName, File volumePath) {
        return;
    }

    public void setBackedUpData(Map<String, BackupIdRow> backedUpData) {
        this.mBackedUpData = backedUpData;
    }
}
