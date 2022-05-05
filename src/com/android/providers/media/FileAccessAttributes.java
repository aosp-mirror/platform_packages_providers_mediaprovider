/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.database.Cursor;

/**
 * Class to represent the file metadata stored in the database (SQLite/xAttr)
 */
public final class FileAccessAttributes {
    private final long mId;
    private final int mMediaType;
    private final boolean mIsPending;
    private final boolean mIsTrashed;
    // TODO(b/227348809): Remove ownerId field when we add the logic to check ownerId from xattr
    private final int mOwnerId;
    private final String mOwnerPackageName;

    public FileAccessAttributes(long id, int mediaType, boolean isPending,
            boolean isTrashed, int ownerId, String ownerPackageName) {
        this.mId = id;
        this.mMediaType = mediaType;
        this.mIsPending = isPending;
        this.mIsTrashed = isTrashed;
        this.mOwnerId = ownerId;
        this.mOwnerPackageName = ownerPackageName;
    }

    public static FileAccessAttributes fromCursor(Cursor c) {
        final long id  = c.getLong(0);
        String ownerPackageName = c.getString(1);
        final boolean isPending = c.getInt(2) != 0;
        final int mediaType = c.getInt(3);
        final boolean isTrashed = c.getInt(4) != 0;
        return new FileAccessAttributes(id, mediaType, isPending, isTrashed, -1,
                ownerPackageName);
    }

    public String toString() {
        return String.format("Id: %s, Mediatype: %s, isPending: %s, "
                        + "isTrashed: %s, ownerpackageName: %s", this.mId, this.mMediaType,
                mIsPending, mIsTrashed, mOwnerId);
    }

    public long getId() {
        return this.mId;
    }

    public int getMediaType() {
        return this.mMediaType;
    }

    public int getOwnerId() {
        return this.mOwnerId;
    }

    public boolean isTrashed() {
        return this.mIsTrashed;
    }

    public boolean isPending() {
        return this.mIsPending;
    }

    public String getOwnerPackageName() {
        return this.mOwnerPackageName;
    }
}
