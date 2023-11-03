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

import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.photopicker.util.exceptions.UnableToAcquireLockException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Reentrant lock that implements AutoCloseable interface.
 */
public class CloseableReentrantLock extends ReentrantLock implements AutoCloseable {
    private static final String TAG = CloseableReentrantLock.class.getSimpleName();
    private final String mLockName;

    public CloseableReentrantLock(@NonNull String lockName) {
        super();
        mLockName = lockName;
    }

    /**
     * Try to acquire lock with a timeout after running some validations.
     */
    public CloseableReentrantLock lockWithTimeout(long timeout, TimeUnit unit)
            throws UnableToAcquireLockException {
        try {
            final boolean success =
                    this.tryLock(timeout, unit);
            if (!success) {
                throw new UnableToAcquireLockException(
                        "Could not acquire the lock within timeout " + this);
            }
            Log.d(TAG, "Successfully acquired lock " + this);
            return this;
        } catch (InterruptedException e) {
            throw new UnableToAcquireLockException(
                    "Interrupted while waiting for lock " + this, e);
        }
    }

    @Override
    public void close() {
        unlock();
    }

    /**
     * Attempt to release the lock and swallow IllegalMonitorStateException, if thrown.
     */
    @Override
    public void lock() {
        super.lock();
        Log.d(TAG, "Successfully acquired lock " + this);
    }

    /**
     * Attempt to release the lock and swallow IllegalMonitorStateException, if thrown.
     */
    @Override
    public void unlock() {
        try {
            super.unlock();
            Log.d(TAG, "Successfully released lock " + this);
        } catch (IllegalMonitorStateException e) {
            Log.e(TAG, "Tried to release a lock that is not held by this thread - " + this);
        }
    }

    @Override
    public String toString() {
        return super.toString() + ". Lock Name = " + mLockName
                + ". Threads that may be waiting to acquire this lock = " + getQueuedThreads();
    }
}
