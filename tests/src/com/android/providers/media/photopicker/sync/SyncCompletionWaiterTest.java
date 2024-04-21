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

package com.android.providers.media.photopicker.sync;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;


public class SyncCompletionWaiterTest {
    private WorkManager mMockWorkManager;

    @Before
    public void setup() {
        mMockWorkManager = mock(WorkManager.class);
    }

    @Test
    public void testWaitForSyncWhenSyncFutureIsComplete()
            throws ExecutionException, InterruptedException {
        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.complete(null);

        final int inputRetryCount = 3;
        assertThat(SyncCompletionWaiter
                .waitForSync(mMockWorkManager, completableFuture,
                        "work-name", inputRetryCount))
                .isEqualTo(inputRetryCount);
    }

    @Test
    public void testWaitForSyncWhenSyncFutureNeverCompletes()
            throws ExecutionException, InterruptedException, TimeoutException {
        final String workName = "work-name";
        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        listenableFuture.set(List.of(getWorkInfo(WorkInfo.State.RUNNING)));
        when(mMockWorkManager.getWorkInfosForUniqueWork(eq(workName)))
                .thenReturn(listenableFuture);

        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        final int inputRetryCount = 3;
        assertThat(SyncCompletionWaiter
                .waitForSync(mMockWorkManager, completableFuture,
                        workName, inputRetryCount))
                .isEqualTo(0);
    }

    @Test
    public void testWaitForSyncWhenWorkerFails()
            throws ExecutionException, InterruptedException, TimeoutException {
        final String workName = "work-name";
        final SettableFuture<List<WorkInfo>> listenableFuture = SettableFuture.create();
        listenableFuture.set(List.of(getWorkInfo(WorkInfo.State.SUCCEEDED)));
        when(mMockWorkManager.getWorkInfosForUniqueWork(eq(workName)))
                .thenReturn(listenableFuture);

        final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        final int inputRetryCount = 3;
        assertThat(SyncCompletionWaiter
                .waitForSync(mMockWorkManager, completableFuture,
                        workName, inputRetryCount))
                .isEqualTo(inputRetryCount);
    }

    @Test
    public void testUniqueWorkStatusForPendingWork() {
        final String workName = "testWorkName";
        final SettableFuture<List<WorkInfo>> future = SettableFuture.create();
        final List<WorkInfo> futureResult = List.of(
                getWorkInfo(WorkInfo.State.SUCCEEDED),
                getWorkInfo(WorkInfo.State.ENQUEUED)
        );
        future.set(futureResult);
        when(mMockWorkManager.getWorkInfosForUniqueWork(workName)).thenReturn(future);

        assertThat(SyncCompletionWaiter.isUniqueWorkPending(mMockWorkManager, workName)).isTrue();
    }

    @Test
    public void testUniqueWorkStatusForCompletedWork() {
        final String workName = "testWorkName";
        final SettableFuture<List<WorkInfo>> future = SettableFuture.create();
        final List<WorkInfo> futureResult = List.of(
                getWorkInfo(WorkInfo.State.SUCCEEDED),
                getWorkInfo(WorkInfo.State.FAILED),
                getWorkInfo(WorkInfo.State.CANCELLED)
        );
        future.set(futureResult);
        when(mMockWorkManager.getWorkInfosForUniqueWork(workName)).thenReturn(future);

        assertThat(SyncCompletionWaiter.isUniqueWorkPending(mMockWorkManager, workName)).isFalse();
    }

    private WorkInfo getWorkInfo(WorkInfo.State state) {
        return new WorkInfo(UUID.randomUUID(), state, new HashSet<>());
    }
}
