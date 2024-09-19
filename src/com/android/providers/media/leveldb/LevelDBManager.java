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

import com.google.common.base.Ascii;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates and manages connections to LevelDB.
 */
public final class LevelDBManager {
    private static final Map<String, LevelDBInstance> INSTANCES = new HashMap<>();

    private static final Object sLockObject = new Object();

    private LevelDBManager() {}

    /**
     * Creates leveldb instance. If already exists, returns a reference to it.
     *
     * @param path on which instance needs to be created
     */
    public static LevelDBInstance getInstance(String path) {
        synchronized (sLockObject) {
            path = Ascii.toLowerCase(path.trim());
            if (INSTANCES.containsKey(path)) {
                return INSTANCES.get(path);
            }

            LevelDBInstance instance = LevelDBInstance.createLevelDBInstance(path);
            INSTANCES.put(path, instance);
            return instance;
        }
    }

    /**
     * Deletes existing leveldb instance on given path and creates a new one.
     *
     * @param path on which instance needs to be re-created
     */
    public static LevelDBInstance recreate(String path) {
        delete(path);
        synchronized (sLockObject) {
            LevelDBInstance instance = LevelDBInstance.createLevelDBInstance(path);
            INSTANCES.put(path, instance);
            return instance;
        }
    }

    /**
     * Deletes existing leveldb instance on given path.
     *
     * @param path on which instance needs to be deleted
     */
    public static void delete(String path) {
        synchronized (sLockObject) {
            path = Ascii.toLowerCase(path.trim());
            if (INSTANCES.containsKey(path)) {
                INSTANCES.get(path).deleteInstance();
                INSTANCES.remove(path);
            }
        }
    }

}
