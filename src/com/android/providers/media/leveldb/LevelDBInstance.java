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

package com.android.providers.media.leveldb;

import java.io.File;
import java.util.List;


/**
 * Represents an instance of leveldb connection.
 */
public final class LevelDBInstance {

    // Max limit of bulk insert
    public static final int MAX_BULK_INSERT_ENTRIES = 100;

    private static boolean sIsLibraryLoaded = false;

    private long mNativePtr;

    private final String mLevelDBPath;

    private LevelDBInstance(long ptr, String path) {
        this.mNativePtr = ptr;
        this.mLevelDBPath = path;
    }

    static LevelDBInstance createLevelDBInstance(String path) {
        if (!sIsLibraryLoaded) {
            System.loadLibrary("leveldb_jni");
            sIsLibraryLoaded = true;
        }

        long ptr = nativeCreateInstance(path);
        if (ptr == 0) {
            throw new IllegalStateException("Leveldb connection is missing");
        }

        return new LevelDBInstance(ptr, path);
    }

    /**
     * Returns path of leveldb file
     */
    public String getLevelDBPath() {
        return mLevelDBPath;
    }

    /**
     * Fetch value for given key from leveldb
     *
     * @param key for entry
     */
    public LevelDBResult query(String key) {
        synchronized (this) {
            if (mNativePtr == 0) {
                throw new IllegalStateException("Leveldb connection is missing");
            }

            return nativeQuery(mNativePtr, key);
        }
    }

    /**
     * Inserts key,value entry in leveldb.
     *
     * @param levelDbEntry contains key and value
     */
    public LevelDBResult insert(LevelDBEntry levelDbEntry) {
        synchronized (this) {
            if (mNativePtr == 0) {
                throw new IllegalStateException("Leveldb connection is missing");
            }

            return nativeInsert(mNativePtr, levelDbEntry);
        }
    }

    /**
     * Inserts key,value entry list in leveldb.
     *
     * @param entryList contains list of LevelDbEntry
     * @throws java.lang.IllegalArgumentException if entries size is zero or greater than 1000.
     */
    public LevelDBResult bulkInsert(List<LevelDBEntry> entryList) {
        synchronized (this) {
            if (entryList == null || entryList.size() == 0) {
                throw new IllegalArgumentException("No entries provided to insert");
            }

            if (entryList.size() > MAX_BULK_INSERT_ENTRIES) {
                throw new IllegalArgumentException(
                        "Entry size is greater than max size: " + MAX_BULK_INSERT_ENTRIES);
            }

            if (mNativePtr == 0) {
                throw new IllegalStateException("Leveldb connection is missing");
            }

            return nativeBulkInsert(mNativePtr, entryList);
        }
    }

    /**
     * Deletes entry for given key in leveldb.
     *
     * @param key to be deleted
     */
    public LevelDBResult delete(String key) {
        synchronized (this) {
            if (mNativePtr == 0) {
                throw new IllegalStateException("Leveldb connection is missing");
            }

            return nativeDelete(mNativePtr, key);
        }
    }

    /**
     * Deletes entry for given key in leveldb.
     *
     */
    public void deleteInstance() {
        synchronized (this) {
            if (mNativePtr == 0) {
                throw new IllegalStateException("Leveldb connection is missing");
            }

            mNativePtr = 0;
            new File(getLevelDBPath()).delete();
        }
    }

    private static native long nativeCreateInstance(String path);

    private native LevelDBResult nativeQuery(long nativePtr, String key);

    private native LevelDBResult nativeInsert(long nativePtr, LevelDBEntry levelDbEntry);

    private native LevelDBResult nativeBulkInsert(long nativePtr, List<LevelDBEntry> entryList);

    private native LevelDBResult nativeDelete(long nativePtr, String key);
}
