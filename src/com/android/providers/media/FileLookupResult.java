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

/**
 * Wrapper class which contains transforms, transforms completion status and ioPath for transform
 * lookup query for a file and uid pair.
 */
public final class FileLookupResult {
    public final int transforms;
    public final int transformsReason;
    public final int uid;
    public final boolean transformsComplete;
    public final boolean transformsSupported;
    public final String ioPath;

    public FileLookupResult(int transforms, int uid, String ioPath) {
        this (transforms, /* transformsReason */ 0, uid, /* transformsComplete */ true,
                /* transformsSupported */ transforms == 0 ? false : true, ioPath);
    }

    public FileLookupResult(int transforms, int transformsReason, int uid,
            boolean transformsComplete, boolean transformsSupported, String ioPath) {
        this.transforms = transforms;
        this.transformsReason = transformsReason;
        this.uid = uid;
        this.transformsComplete = transformsComplete;
        this.transformsSupported = transformsSupported;
        this.ioPath = ioPath;
    }
}
