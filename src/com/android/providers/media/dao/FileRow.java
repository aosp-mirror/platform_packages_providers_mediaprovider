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

package com.android.providers.media.dao;

/** DAO object representing database row of Files table of a MediaProvider database. */
public class FileRow {

    private final long mId;
    private String mPath;
    private String mOwnerPackageName;
    private String mVolumeName;
    private int mMediaType;
    private boolean mIsDownload;
    private boolean mIsPending;
    private boolean mIsTrashed;
    private boolean mIsFavorite;
    private int mSpecialFormat;
    private int mUserId;
    // String data type used as value can be null
    private String mDateExpires;

    public static class Builder {
        private final long mId;
        private String mPath;
        private String mOwnerPackageName;
        private String mVolumeName;
        private int mMediaType;
        private boolean mIsDownload;
        private boolean mIsPending;
        private boolean mIsTrashed;
        private boolean mIsFavorite;
        private int mSpecialFormat;
        private int mUserId;
        private String mDateExpires;

        Builder(long id) {
            this.mId = id;
        }

        public Builder setPath(String path) {
            this.mPath = path;
            return this;
        }

        public Builder setOwnerPackageName(String ownerPackageName) {
            this.mOwnerPackageName = ownerPackageName;
            return this;
        }

        public Builder setVolumeName(String volumeName) {
            this.mVolumeName = volumeName;
            return this;
        }

        public Builder setMediaType(int mediaType) {
            this.mMediaType = mediaType;
            return this;
        }

        public Builder setIsDownload(boolean download) {
            mIsDownload = download;
            return this;
        }

        public Builder setIsPending(boolean pending) {
            mIsPending = pending;
            return this;
        }

        public Builder setIsTrashed(boolean trashed) {
            mIsTrashed = trashed;
            return this;
        }

        public Builder setIsFavorite(boolean favorite) {
            mIsFavorite = favorite;
            return this;
        }

        public Builder setSpecialFormat(int specialFormat) {
            this.mSpecialFormat = specialFormat;
            return this;
        }

        public Builder setUserId(int userId) {
            this.mUserId = userId;
            return this;
        }

        public Builder setDateExpires(String dateExpires) {
            this.mDateExpires = dateExpires;
            return this;
        }

        public FileRow build() {
            FileRow fileRow = new FileRow(this.mId);
            fileRow.mPath = this.mPath;
            fileRow.mOwnerPackageName = this.mOwnerPackageName;
            fileRow.mVolumeName = this.mVolumeName;
            fileRow.mMediaType = this.mMediaType;
            fileRow.mIsDownload = this.mIsDownload;
            fileRow.mIsPending = this.mIsPending;
            fileRow.mIsTrashed = this.mIsTrashed;
            fileRow.mIsFavorite = this.mIsFavorite;
            fileRow.mSpecialFormat = this.mSpecialFormat;
            fileRow.mUserId = this.mUserId;
            fileRow.mDateExpires = this.mDateExpires;

            return fileRow;
        }
    }

    public static Builder newBuilder(long id) {
        return new Builder(id);
    }

    private FileRow(long id) {
        this.mId = id;
    }

    public long getId() {
        return mId;
    }

    public String getPath() {
        return mPath;
    }

    public String getOwnerPackageName() {
        return mOwnerPackageName;
    }

    public String getVolumeName() {
        return mVolumeName;
    }

    public int getMediaType() {
        return mMediaType;
    }

    public boolean isDownload() {
        return mIsDownload;
    }

    public boolean isPending() {
        return mIsPending;
    }

    public boolean isTrashed() {
        return mIsTrashed;
    }

    public boolean isFavorite() {
        return mIsFavorite;
    }

    public int getSpecialFormat() {
        return mSpecialFormat;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getDateExpires() {
        return mDateExpires;
    }
}
