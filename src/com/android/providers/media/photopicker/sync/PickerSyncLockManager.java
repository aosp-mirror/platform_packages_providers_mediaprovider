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

import android.annotation.IntDef;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages Java locks acquired during the sync process to ensure that the cloud sync is thread safe.
 */
public class PickerSyncLockManager {
    private static final String TAG = PickerSyncLockManager.class.getSimpleName();
    private static final Integer LOCK_ACQUIRE_TIMEOUT_MINS = 4;
    private static final TimeUnit LOCK_ACQUIRE_TIMEOUT_UNIT = TimeUnit.MINUTES;

    @IntDef(value = {CLOUD_SYNC_LOCK, CLOUD_ALBUM_SYNC_LOCK, CLOUD_PROVIDER_LOCK, DB_CLOUD_LOCK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LockType {}
    public static final int CLOUD_SYNC_LOCK = 0;
    public static final int CLOUD_ALBUM_SYNC_LOCK = 1;
    public static final int CLOUD_PROVIDER_LOCK = 2;
    public static final int DB_CLOUD_LOCK = 3;

    private final CloseableReentrantLock mCloudSyncLock =
            new CloseableReentrantLock("CLOUD_SYNC_LOCK");
    private final CloseableReentrantLock mCloudAlbumSyncLock =
            new CloseableReentrantLock("CLOUD_ALBUM_SYNC_LOCK");
    private final CloseableReentrantLock mCloudProviderLock =
            new CloseableReentrantLock("CLOUD_PROVIDER_LOCK");
    private final CloseableReentrantLock mDbCloudLock =
            new CloseableReentrantLock("DB_CLOUD_LOCK");

    /**
     * Try to acquire lock with a default timeout after running some validations.
     */
    public CloseableReentrantLock tryLock(@LockType int lockType)
            throws UnableToAcquireLockException {
        return tryLock(lockType, LOCK_ACQUIRE_TIMEOUT_MINS, LOCK_ACQUIRE_TIMEOUT_UNIT);
    }

    /**
     * Try to acquire lock with the provided timeout after running some validations.
     */
    public CloseableReentrantLock tryLock(@LockType int lockType, long timeout, TimeUnit unit)
            throws UnableToAcquireLockException {
        return tryLock(getLock(lockType), timeout, unit);
    }

    /**
     * Try to acquire the given lock with the provided timeout after running some validations.
     */
    @VisibleForTesting
    public CloseableReentrantLock tryLock(@NonNull CloseableReentrantLock lock,
            long timeout, TimeUnit unit) throws UnableToAcquireLockException {
        Log.d(TAG, "Trying to acquire lock " + lock + " with timeout.");
        validateLockOrder(lock);
        return lock.lockWithTimeout(timeout, unit);
    }

    /**
     * Try to acquire the lock after running some validations.
     */
    public CloseableReentrantLock lock(@LockType int lockType) {
        final CloseableReentrantLock reentrantLock = getLock(lockType);
        Log.d(TAG, "Trying to acquire lock " + reentrantLock);
        validateLockOrder(reentrantLock);
        reentrantLock.lock();
        return reentrantLock;
    }

    /**
     * Return the {@link CloseableReentrantLock} corresponding to the given {@link LockType}.
     * Throws a {@link RuntimeException} if the lock is not recognized.
     */
    @VisibleForTesting
    public CloseableReentrantLock getLock(@LockType int lockType) {
        switch (lockType) {
            case CLOUD_SYNC_LOCK:
                return mCloudSyncLock;
            case CLOUD_ALBUM_SYNC_LOCK:
                return mCloudAlbumSyncLock;
            case CLOUD_PROVIDER_LOCK:
                return mCloudProviderLock;
            case DB_CLOUD_LOCK:
                return mDbCloudLock;
            default:
                throw new RuntimeException("Unrecognizable lock type " + lockType);
        }
    }

    private void validateLockOrder(@NonNull ReentrantLock lockToBeAcquired) {
        if (lockToBeAcquired.equals(mCloudSyncLock)) {
            validateLockOrder(lockToBeAcquired, mCloudAlbumSyncLock);
            validateLockOrder(lockToBeAcquired, mCloudProviderLock);
            validateLockOrder(lockToBeAcquired, mDbCloudLock);
        } else if (lockToBeAcquired.equals(mCloudAlbumSyncLock)) {
            validateLockOrder(lockToBeAcquired, mCloudSyncLock);
            validateLockOrder(lockToBeAcquired, mCloudProviderLock);
            validateLockOrder(lockToBeAcquired, mDbCloudLock);
        } else if (lockToBeAcquired.equals(mCloudProviderLock)) {
            validateLockOrder(lockToBeAcquired, mDbCloudLock);
        }
    }

    private void validateLockOrder(@NonNull ReentrantLock lockToBeAcquired,
            @NonNull ReentrantLock lockThatShouldNotBeHeld) {
        if (lockThatShouldNotBeHeld.isHeldByCurrentThread()) {
            Log.e(TAG, String.format("Lock {%s} should not be held before acquiring lock {%s}"
                            + " This could lead to a deadlock.",
                    lockThatShouldNotBeHeld, lockToBeAcquired));
        }
    }
}
