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


/**
 * Represents a leveldb entry which has a key and a value as string data type.
 */
public final class LevelDBEntry {
    private final String mKey;
    private final String mValue;

    public LevelDBEntry(String key, String value) {
        this.mKey = key;
        this.mValue = value;
    }

    public String getKey() {
        return mKey;
    }

    public String getValue() {
        return mValue;
    }
}
