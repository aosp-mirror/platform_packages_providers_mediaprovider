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

package com.android.providers.media;

import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.createNewPublicVolume;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.deletePublicVolumes;
import static com.android.providers.media.tests.utils.PublicVolumeSetupHelper.partitionPublicVolume;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.tests.utils.PublicVolumeSetupHelper;
import com.android.providers.media.util.FileUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PublicVolumeTest {
    static final int POLL_DELAY_MS = 500;
    static final int WAIT_FOR_DEFAULT_FOLDERS_MS = 30000;

    @BeforeClass
    public static void setUp() throws Exception {
        createNewPublicVolume();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        deletePublicVolumes();
        // Make sure External Storage is mounted so that this does not crash other tests
        // in the suite.
        PublicVolumeSetupHelper.pollForExternalStorageStateMounted();
        // Make sure all work is completed so that the device is left in a stable state
        Context context = InstrumentationRegistry.getTargetContext();
        MediaStore.waitForIdle(context.getContentResolver());
    }

    @Test
    @Ignore("Re-enable with fix b/281794265")
    public void testPublicVolumeDefaultFolders() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();

        // Reformat the volume, which should make sure we have default folders
        partitionPublicVolume();

        List<StorageVolume> volumes = context.
                getSystemService(StorageManager.class).getStorageVolumes();
        for (StorageVolume volume : volumes) {
            // We only want to verify reliable public volumes (not OTG)
            if (!volume.isPrimary() && volume.getPath().startsWith("/storage")) {
                assertTrue(pollContainsDefaultFolders(volume.getPath()));
            }
        }

        // We had a bug before where we didn't re-create public volumes when the same
        // volume was re-formatted. Repartition it and try again.
        partitionPublicVolume();

        volumes = context.getSystemService(StorageManager.class).getStorageVolumes();
        for (StorageVolume volume : volumes) {
            // We only want to verify reliable public volumes (not OTG)
            if (!volume.isPrimary() && volume.getPath().startsWith("/storage")) {
                assertTrue(pollContainsDefaultFolders(volume.getPath()));
            }
        }
    }

    private boolean containsDefaultFolders(String rootPath) {
        for (String dirName : FileUtils.DEFAULT_FOLDER_NAMES) {
            final File defaultFolder = new File(rootPath, dirName);
            if (!defaultFolder.exists()) {
                return false;
            }
        }
        return true;
    }

    private boolean pollContainsDefaultFolders(String rootPath) {
        // Default folders are created by MediaProvider after receiving a callback from
        // the StorageManagerService that the volume has been mounted.
        // Unfortunately, we don't have a reliable way to determine when this callback has
        // happened, so poll here.
        for (int i = 0; i < WAIT_FOR_DEFAULT_FOLDERS_MS / POLL_DELAY_MS; i++) {
            if (containsDefaultFolders(rootPath)) {
                return true;
            }
            try {
                Thread.sleep(POLL_DELAY_MS);
            } catch (InterruptedException e) {
            }
        }
        return false;
    }
}

