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

package com.android.providers.media.stableuris.dao;

import android.provider.MediaStore.MediaColumns;

import com.android.providers.media.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.Objects;

/**
 * DAO object representing database row of leveldb for volume database backup and recovery.
 *
 * Warning: Do not change/modify existing field names as it will affect deserialization of existing
 * rows.
 */
public final class BackupIdRow implements Serializable {

    private long mId;
    private int mIsFavorite;
    private int mIsPending;
    private int mIsTrashed;
    private boolean mIsDirty;

    /**
     * This is not Owner Package name but a unique identifier to it
     */
    private int mOwnerPackageId;

    /**
     * Same as {@link MediaColumns#DATE_EXPIRES}. Will be null if media row does not have
     * expiry time set.
     */
    private String mDateExpires;

    /**
     * This is required to support cloned user data.
     */
    private int mUserId;
    private int mMediaType;

    /**
     * Builder class for {@link BackupIdRow}
     */
    public static class Builder {
        private long mId;
        private int mIsFavorite;
        private int mIsPending;
        private int mIsTrashed;
        private boolean mIsDirty;
        private int mOwnerPackageId;
        private String mDateExpires;
        private int mUserId;
        private int mMediaType;

        Builder(long id) {
            this.mId = id;
        }

        /**
         * Sets the isFavorite value
         */
        public Builder setIsFavorite(int isFavorite) {
            this.mIsFavorite = isFavorite;
            return this;
        }

        /**
         * Sets the isPending value
         */
        public Builder setIsPending(int isPending) {
            this.mIsPending = isPending;
            return this;
        }

        /**
         * Sets the isTrashed value
         */
        public Builder setIsTrashed(int isTrashed) {
            this.mIsTrashed = isTrashed;
            return this;
        }

        /**
         * Sets the ownerPackagedId value
         */
        public Builder setOwnerPackagedId(int ownerPackagedId) {
            this.mOwnerPackageId = ownerPackagedId;
            return this;
        }

        /**
         * Sets the dateExpires value
         */
        public Builder setDateExpires(String dateExpires) {
            if (StringUtils.isNullOrEmpty(dateExpires)) {
                this.mDateExpires = null;
            } else {
                long value = Long.parseLong(dateExpires);
                this.mDateExpires = String.valueOf(value);
            }

            return this;
        }

        /**
         * Sets the userId value
         */
        public Builder setUserId(int userId) {
            this.mUserId = userId;
            return this;
        }

        /**
         * Sets the mediatype value
         */
        public Builder setMediaType(int mediaType) {
            this.mMediaType = mediaType;
            return this;
        }

        /**
         * Sets the isDirty value
         */
        public Builder setIsDirty(boolean isDirty) {
            this.mIsDirty = isDirty;
            return this;
        }

        /**
         * Builds {@link BackupIdRow} object with the given values set
         */
        public BackupIdRow build() {
            BackupIdRow backupIdRow = new BackupIdRow(this.mId);
            backupIdRow.mIsFavorite = this.mIsFavorite;
            backupIdRow.mIsPending = this.mIsPending;
            backupIdRow.mIsTrashed = this.mIsTrashed;
            backupIdRow.mIsDirty = this.mIsDirty;
            backupIdRow.mOwnerPackageId = this.mOwnerPackageId;
            backupIdRow.mDateExpires = this.mDateExpires;
            backupIdRow.mUserId = this.mUserId;
            backupIdRow.mMediaType = this.mMediaType;

            return backupIdRow;
        }
    }

    public static Builder newBuilder(long id) {
        return new BackupIdRow.Builder(id);
    }

    private BackupIdRow(long id) {
        this.mId = id;
    }

    public long getId() {
        return mId;
    }

    public int getIsFavorite() {
        return mIsFavorite;
    }

    public int getIsPending() {
        return mIsPending;
    }

    public int getIsTrashed() {
        return mIsTrashed;
    }

    public int getOwnerPackageId() {
        return mOwnerPackageId;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getDateExpires() {
        return mDateExpires;
    }

    public boolean getIsDirty() {
        return mIsDirty;
    }

    public int getMediaType() {
        return mMediaType;
    }

    /**
     * Returns human-readable form of {@link BackupIdRow} for easy debugging.
     */
    @Override
    public String toString() {
        return "BackupIdRow{" +
                "mId=" + mId +
                ", mIsFavorite=" + mIsFavorite +
                ", mIsPending=" + mIsPending +
                ", mIsTrashed=" + mIsTrashed +
                ", mIsDirty=" + mIsDirty +
                ", mOwnerPackageId=" + mOwnerPackageId +
                ", mDateExpires=" + mDateExpires +
                ", mUserId=" + mUserId +
                ", mMediaType=" + mMediaType +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackupIdRow)) return false;
        BackupIdRow that = (BackupIdRow) o;
        return mId == that.mId && mIsFavorite == that.mIsFavorite && mIsPending == that.mIsPending
                && mIsTrashed == that.mIsTrashed && mIsDirty == that.mIsDirty
                && mOwnerPackageId == that.mOwnerPackageId && mUserId == that.mUserId
                && mMediaType == that.mMediaType && Objects.equals(mDateExpires,
                that.mDateExpires);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mIsFavorite, mIsPending, mIsTrashed, mIsDirty, mOwnerPackageId,
                mDateExpires, mUserId, mMediaType);
    }

    /**
     * Serializes the given {@link BackupIdRow} object to a string
     */
    public static String serialize(BackupIdRow backupIdRow) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(backupIdRow);
        objectOutputStream.close();
        return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
    }

    /**
     * Deserializes the given string to {@link BackupIdRow} object
     */
    public static BackupIdRow deserialize(String s) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(s);
        ObjectInputStream objectInputStream = new ObjectInputStream(
                new ByteArrayInputStream(bytes));
        BackupIdRow backupIdRow = (BackupIdRow) objectInputStream.readObject();
        objectInputStream.close();
        return backupIdRow;
    }
}
