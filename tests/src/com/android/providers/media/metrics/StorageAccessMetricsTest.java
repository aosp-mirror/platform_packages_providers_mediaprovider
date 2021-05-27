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

package com.android.providers.media.metrics;

import static com.android.providers.media.metrics.StorageAccessMetrics.PackageStorageAccessStats;
import static com.android.providers.media.metrics.StorageAccessMetrics.UID_SAMPLES_COUNT_LIMIT;

import static com.google.common.truth.Truth.assertThat;

import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class StorageAccessMetricsTest {

    private static StorageAccessMetrics storageAccessMetrics;

    @Before
    public void setUp() {
        storageAccessMetrics = new StorageAccessMetrics();
    }

    @Test
    public void testLogMimeType() {
        storageAccessMetrics.logMimeType(3, "my-mime-type");
        storageAccessMetrics.logMimeType(3, null);
        storageAccessMetrics.logMimeType(3, "my-mime-type-2");
        storageAccessMetrics.logMimeType(3, "my-mime-type-2");
        storageAccessMetrics.logMimeType(3, "my-mime-type-3");
        storageAccessMetrics.logMimeType(3, "my-mime-type-2");
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).hasSize(1);

        PackageStorageAccessStats stats = statsList.get(0);
        assertThat(stats.getUid()).isEqualTo(3);
        assertThat(stats.mTotalAccesses).isEqualTo(0);
        assertThat(stats.mFilePathAccesses).isEqualTo(0);
        assertThat(stats.mSecondaryStorageAccesses).isEqualTo(0);
        assertThat(stats.mMimeTypes.stream().toArray())
                .asList()
                .containsExactly("my-mime-type", "my-mime-type-2", "my-mime-type-3");
    }

    @Test
    public void testIncrementAccessViaFuse() {
        storageAccessMetrics.logAccessViaFuse(3, "filename.txt");
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).hasSize(1);

        PackageStorageAccessStats stats = statsList.get(0);
        assertThat(stats.getUid()).isEqualTo(3);
        assertThat(stats.mTotalAccesses).isEqualTo(0);
        assertThat(stats.mFilePathAccesses).isEqualTo(1);
        assertThat(stats.mSecondaryStorageAccesses).isEqualTo(0);
        assertThat(stats.mMimeTypes.stream().toArray())
                .asList()
                .containsExactly("text/plain");
    }

    @Test
    public void testIncrementAccessViaMediaProvider_externalVolumes() {
        storageAccessMetrics.logAccessViaMediaProvider(3, MediaStore.VOLUME_EXTERNAL);
        storageAccessMetrics.logAccessViaMediaProvider(
                3, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).hasSize(1);

        PackageStorageAccessStats stats = statsList.get(0);
        assertThat(stats.getUid()).isEqualTo(3);
        assertThat(stats.mTotalAccesses).isEqualTo(2);
        assertThat(stats.mFilePathAccesses).isEqualTo(0);
        assertThat(stats.mSecondaryStorageAccesses).isEqualTo(0);
        assertThat(stats.mMimeTypes.size()).isEqualTo(0);
    }

    @Test
    public void testIncrementAccessViaMediaProvider_ignoredVolumes() {
        storageAccessMetrics.logAccessViaMediaProvider(3, MediaStore.VOLUME_INTERNAL);
        storageAccessMetrics.logAccessViaMediaProvider(3, MediaStore.VOLUME_DEMO);
        storageAccessMetrics.logAccessViaMediaProvider(3, MediaStore.MEDIA_SCANNER_VOLUME);
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).isEmpty();
    }

    @Test
    public void testIncrementAccessViaMediaProvider_secondaryVolumes() {
        storageAccessMetrics.logAccessViaMediaProvider(3, "my-volume");
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).hasSize(1);

        PackageStorageAccessStats stats = statsList.get(0);
        assertThat(stats.getUid()).isEqualTo(3);
        assertThat(stats.mTotalAccesses).isEqualTo(1);
        assertThat(stats.mFilePathAccesses).isEqualTo(0);
        assertThat(stats.mSecondaryStorageAccesses).isEqualTo(1);
        assertThat(stats.mMimeTypes.size()).isEqualTo(0);
    }

    @Test
    public void testUidCountGreaterThanLimit() {
        int i = 0;
        for (; i < UID_SAMPLES_COUNT_LIMIT; i++) {
            storageAccessMetrics.logAccessViaFuse(i, "myfile.txt");
        }
        // Add 1 more
        storageAccessMetrics.logAccessViaFuse(i, "myfile.txt");
        // Pull stats
        List<PackageStorageAccessStats> statsList =
                storageAccessMetrics.getSampleStats();

        assertThat(statsList).hasSize(UID_SAMPLES_COUNT_LIMIT);
    }
}
