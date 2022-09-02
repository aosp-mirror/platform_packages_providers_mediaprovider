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
 * Wrapper class which contains the result of an open.
 */
public final class FileOpenResult {
    public final int status;
    public final int uid;
    public final int transformsUid;
    public final int nativeFd;
    public final long[] redactionRanges;

    public FileOpenResult(int status, int uid, int transformsUid, long[] redactionRanges) {
        this(status, uid, transformsUid, /* nativeFd */ -1, redactionRanges);
    }

    public FileOpenResult(int status, int uid, int transformsUid, int nativeFd,
            long[] redactionRanges) {
        this.status = status;
        this.uid = uid;
        this.transformsUid = transformsUid;
        this.nativeFd = nativeFd;
        this.redactionRanges = redactionRanges;
    }

    public static FileOpenResult createError(int errorCode, int uid) {
        return new FileOpenResult(errorCode, uid, /* transformsUid */ 0, new long[0]);
    }
}
