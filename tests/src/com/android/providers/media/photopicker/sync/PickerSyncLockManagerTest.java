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

import static com.android.providers.media.photopicker.sync.PickerSyncLockManager.CLOUD_ALBUM_SYNC_LOCK;
import static com.android.providers.media.photopicker.sync.PickerSyncLockManager.CLOUD_SYNC_LOCK;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.HandlerThread;

import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PickerSyncLockManagerTest {
    private PickerSyncLockManager mSyncLockManager;

    @Before
    public void setup() {
        mSyncLockManager = new PickerSyncLockManager();
    }

    @Test
    public void testLockIsCloseable() {
        try (CloseableReentrantLock lock = mSyncLockManager.lock(CLOUD_SYNC_LOCK)) {
            // Assert that the lock is help by the current thread.
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(lock.getHoldCount()).isEqualTo(1);

            try (CloseableReentrantLock lockInLock = mSyncLockManager.lock(CLOUD_SYNC_LOCK)) {
                // Assert that this is a reentrant lock and the thread was able to increment hold
                // count.
                assertThat(lock.isHeldByCurrentThread()).isTrue();
                assertThat(lock).isEqualTo(lockInLock);
                assertThat(lock.getHoldCount()).isEqualTo(2);
            }

            // Assert that the hold count has been decremented.
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(lock.getHoldCount()).isEqualTo(1);
            assertThat(lock).isEqualTo(mSyncLockManager.getLock(CLOUD_SYNC_LOCK));
        }

        assertThat(mSyncLockManager.getLock(CLOUD_SYNC_LOCK).isHeldByCurrentThread()).isFalse();
    }

    @Test
    public void testLockWithTimeoutIsCloseable() throws UnableToAcquireLockException {
        try (CloseableReentrantLock lock = mSyncLockManager.tryLock(CLOUD_ALBUM_SYNC_LOCK)) {
            // Assert that the lock is help by the current thread.
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(lock.getHoldCount()).isEqualTo(1);

            try (CloseableReentrantLock lockInLock =
                         mSyncLockManager.tryLock(CLOUD_ALBUM_SYNC_LOCK)) {
                // Assert that this is a reentrant lock and the thread was able to increment hold
                // count.
                assertThat(lock.isHeldByCurrentThread()).isTrue();
                assertThat(lock).isEqualTo(lockInLock);
                assertThat(lock.getHoldCount()).isEqualTo(2);
            }

            // Assert that the hold count has been decremented.
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            assertThat(lock.getHoldCount()).isEqualTo(1);
            assertThat(lock).isEqualTo(mSyncLockManager.getLock(CLOUD_ALBUM_SYNC_LOCK));
        }

        assertThat(mSyncLockManager.getLock(CLOUD_ALBUM_SYNC_LOCK).isHeldByCurrentThread())
                .isFalse();
    }

    @Test
    public void testLockTimeout() throws InterruptedException, TimeoutException {
        CloseableReentrantLock lock = new CloseableReentrantLock("testLock");
        try (CloseableReentrantLock ignored =
                     mSyncLockManager.tryLock(lock, 5, TimeUnit.MILLISECONDS)) {
            // it is expected that the lock is held by the current thread within timeout.
        } catch (UnableToAcquireLockException e) {
            throw new AssertionError(
                    "Should be able to acquire the lock since no other thread holds it.", e);
        }

        HandlerThread thread = new HandlerThread("PickerSyncLockTestThread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        acquireLock(handler, lock);

        try (CloseableReentrantLock ignored =
                     mSyncLockManager.tryLock(lock, 5, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("The lock should not be acquired by this thread because "
                    + "it is already held by a different thread");
        } catch (UnableToAcquireLockException e) {
            // The expectation is that lock is not acquired within the timeout and
            // UnableToAcquireLockException is thrown.
        }

        releaseLock(handler, lock);
        thread.quitSafely();
    }

    private void acquireLock(Handler handler, CloseableReentrantLock lock)
            throws InterruptedException, TimeoutException {
        handler.post(() -> lock.lock());
        waitForHandler(handler);
    }

    private void releaseLock(Handler handler, CloseableReentrantLock lock)
            throws InterruptedException, TimeoutException {
        handler.post(() -> lock.unlock());
        waitForHandler(handler);
    }

    private void waitForHandler(Handler handler) throws InterruptedException, TimeoutException {
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> latch.countDown());
        final boolean success = latch.await(30, TimeUnit.SECONDS);
        if (!success) {
            throw new TimeoutException("Could not wait for handler task to finish");
        }
    }
}
