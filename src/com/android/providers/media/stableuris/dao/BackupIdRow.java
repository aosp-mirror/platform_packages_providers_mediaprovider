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
    private boolean mIsDirty;

    /**
     * Builder class for {@link BackupIdRow}
     */
    public static class Builder {
        private long mId;
        private int mIsFavorite = 0;
        private boolean mIsDirty;

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
            backupIdRow.mIsDirty = this.mIsDirty;

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

    public boolean getIsDirty() {
        return mIsDirty;
    }

    /**
     * Returns human-readable form of {@link BackupIdRow} for easy debugging.
     */
    public String toString() {
        return "id = " + getId()
                + " is_favorite = " + getIsFavorite()
                + " is_dirty = " + getIsDirty();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        BackupIdRow that = (BackupIdRow) obj;

        return Objects.equals(getId(), that.getId())
                && Objects.equals(getIsFavorite(), that.getIsFavorite())
                && Objects.equals(getIsDirty(), that.getIsDirty());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getIsFavorite(), getIsDirty());
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
