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

package com.android.providers.media.photopicker.sync;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SyncTrackerTests {
    private static final UUID PLACEHOLDER_UUID = UUID.randomUUID();

    @Test
    public void testCreateSyncFuture() {
        SyncTracker syncTracker = new SyncTracker();
        syncTracker.createSyncFuture(PLACEHOLDER_UUID);

        Collection<CompletableFuture<Object>> futures = syncTracker.pendingSyncFutures();
        assertThat(futures.size()).isEqualTo(1);
    }

    @Test
    public void testMarkSyncAsComplete() {
        SyncTracker syncTracker = new SyncTracker();
        syncTracker.createSyncFuture(PLACEHOLDER_UUID);
        syncTracker.markSyncCompleted(PLACEHOLDER_UUID);

        Collection<CompletableFuture<Object>> futures = syncTracker.pendingSyncFutures();
        assertThat(futures.size()).isEqualTo(0);
    }

    @Test
    public void testCompleteOnTimeoutSyncFuture()
            throws InterruptedException, ExecutionException, TimeoutException {
        SyncTracker syncTracker = new SyncTracker();
        syncTracker.createSyncFuture(PLACEHOLDER_UUID, 100L, TimeUnit.MILLISECONDS);

        Collection<CompletableFuture<Object>> pendingSyncFutures = syncTracker.pendingSyncFutures();
        for (CompletableFuture<Object> future : pendingSyncFutures) {
            future.get(200, TimeUnit.MILLISECONDS);
        }
        assertThat(syncTracker.pendingSyncFutures().size()).isEqualTo(0);
    }

    @Test
    public void getSyncTrackerFromRegistry() {
        assertThat(SyncTrackerRegistry.getSyncTracker(/* isLocal */ true))
                .isEqualTo(SyncTrackerRegistry.getLocalSyncTracker());
        assertThat(SyncTrackerRegistry.getSyncTracker(/* isLocal */ false))
                .isEqualTo(SyncTrackerRegistry.getCloudSyncTracker());
        assertThat(SyncTrackerRegistry.getAlbumSyncTracker(/* isLocal */ true))
                .isEqualTo(SyncTrackerRegistry.getLocalAlbumSyncTracker());
        assertThat(SyncTrackerRegistry.getAlbumSyncTracker(/* isLocal */ false))
                .isEqualTo(SyncTrackerRegistry.getCloudAlbumSyncTracker());
    }
}
