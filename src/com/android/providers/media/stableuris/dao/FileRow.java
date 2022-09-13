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
public class FileRow implements Serializable {

    private long mId;
    private int mIsFavorite;
    private Integer mIsDrm;
    private long mGenerationModified;

    /**
     * Builder class for {@link FileRow}
     */
    public static class Builder {
        private long mId;
        private int mIsFavorite = 0;
        private long mGenerationModified;
        private Integer mIsDrm = null;

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
         * Sets the generationModified value
         */
        public Builder setGenerationModified(long generationModified) {
            this.mGenerationModified = generationModified;
            return this;
        }

        /**
         * Sets the isDrm value
         */
        public Builder setIsDrm(int isDrm) {
            this.mIsDrm = isDrm;
            return this;
        }

        /**
         * Builds {@link FileRow} object with the given values set
         */
        public FileRow build() {
            FileRow fileRow = new FileRow(this.mId);
            fileRow.mIsFavorite = this.mIsFavorite;
            fileRow.mIsDrm = this.mIsDrm;
            fileRow.mGenerationModified = this.mGenerationModified;

            return fileRow;
        }
    }

    public static Builder newBuilder(long id) {
        return new FileRow.Builder(id);
    }

    private FileRow(long id) {
        this.mId = id;
    }

    public long getId() {
        return mId;
    }

    public int getIsFavorite() {
        return mIsFavorite;
    }

    public long getGenerationModified() {
        return mGenerationModified;
    }

    public Integer getIsDrm() {
        return mIsDrm;
    }

    /**
     * Returns human-readable form of {@link FileRow} for easy debugging.
     */
    public String toString() {
        return "id = " + getId()
                + " is_favorite = " + getIsFavorite()
                + " is_drm = " + getIsDrm()
                + " generation_modified = " + mGenerationModified;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        FileRow that = (FileRow) obj;

        return Objects.equals(getId(), that.getId())
                && Objects.equals(getIsFavorite(), that.getIsFavorite())
                && Objects.equals(getGenerationModified(), that.getGenerationModified())
                && Objects.equals(getIsDrm(), that.getIsDrm());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getIsFavorite(), getGenerationModified(), getIsDrm());
    }

    /**
     * Serializes the given {@link FileRow} object to a string
     */
    public static String serialize(FileRow fileRow) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(fileRow);
        objectOutputStream.close();
        return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
    }

    /**
     * Deserializes the given string to {@link FileRow} object
     */
    public static FileRow deserialize(String s) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(s);
        ObjectInputStream objectInputStream = new ObjectInputStream(
                new ByteArrayInputStream(bytes));
        FileRow fileRow  = (FileRow) objectInputStream.readObject();
        objectInputStream.close();
        return fileRow;
    }
}
