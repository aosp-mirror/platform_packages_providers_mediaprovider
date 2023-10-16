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

import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.modules.utils.build.SdkLevel;

import java.io.File;
import java.util.Objects;

/**
 * MediaVolume is a MediaProvider-internal representation of a storage volume.
 *
 * Before MediaVolume, volumes inside MediaProvider were represented by their name;
 * but now that MediaProvider handles volumes on behalf on multiple users, the name of a volume
 * might no longer be unique. So MediaVolume holds both a name and a user. The user may be
 * null on volumes without an owner (eg public volumes).
 *
 * In addition to that, we keep the path and ID of the volume cached in here as well
 * for easy access.
 */
public final class MediaVolume implements Parcelable {
    /**
     * Name of the volume.
     */
    private final @NonNull String mName;

    /**
     * User to which the volume belongs to; might be null in case of public volumes.
     */
    private final @Nullable UserHandle mUser;

    /**
     * Path on which the volume is mounted.
     */
    private final @Nullable File mPath;

    /**
     * Unique ID of the volume; eg "external;0"
     */
    private final @Nullable String mId;

    /**
     * Whether the volume is managed from outside Android.
     */
    private final boolean mExternallyManaged;

    /**
     * Whether the volume is public.
     */
    private final boolean mPublicVolume;

    public @NonNull String getName() {
        return mName;
    }

    public @Nullable UserHandle getUser() {
        return mUser;
    }

    public @Nullable File getPath() {
        return mPath;
    }

    public @Nullable String getId() {
        return mId;
    }

    private boolean isExternallyManaged() {
        return mExternallyManaged;
    }

    public boolean isPublicVolume() {
        return mPublicVolume;
    }

    private MediaVolume (@NonNull String name, UserHandle user, File path, String id,
                         boolean externallyManaged, boolean mPublicVolume) {
        this.mName = name;
        this.mUser = user;
        this.mPath = path;
        this.mId = id;
        this.mExternallyManaged = externallyManaged;
        this.mPublicVolume = mPublicVolume;
    }

    private MediaVolume (Parcel in) {
        this.mName = in.readString();
        this.mUser = in.readParcelable(null);
        this.mPath  = new File(in.readString());
        this.mId = in.readString();
        this.mExternallyManaged = in.readInt() != 0;
        this.mPublicVolume = in.readInt() != 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        MediaVolume that = (MediaVolume) obj;
        // We consciously don't compare the path, because:
        // 1. On unmount events, the returned path for StorageVolumes is
        // 'null', and different from a mounted volume.
        // 2. A volume with a certain ID should never be mounted in two different paths, anyway
        return Objects.equals(mName, that.mName) &&
                Objects.equals(mUser, that.mUser) &&
                Objects.equals(mId, that.mId) &&
                (mExternallyManaged == that.mExternallyManaged);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mUser, mId, mExternallyManaged);
    }

    public boolean isVisibleToUser(UserHandle user) {
        return mUser == null || user.equals(mUser);
    }

    /**
     * Should skip default dir creating for externally managed volumes and for unreliable
     * public volumes.
     */
    public boolean shouldSkipDefaultDirCreation() {
        return isExternallyManaged() || isUnreliablePublicVolume();
    }

    private boolean isUnreliablePublicVolume() {
        return isPublicVolume() && getPath() != null
                && getPath().getAbsolutePath().startsWith("/mnt/");
    }

    /**
     * Adding NewApi Suppress Lint to fix some build errors after making
     * {@link StorageVolume#getOwner()} a public Api
     */
    // TODO(b/213658045) : Remove this once the related changes are submitted.
    @SuppressLint("NewApi")
    @NonNull
    public static MediaVolume fromStorageVolume(StorageVolume storageVolume) {
        String name = storageVolume.getMediaStoreVolumeName();
        UserHandle user = storageVolume.getOwner();
        File path = storageVolume.getDirectory();
        String id = storageVolume.getId();
        boolean externallyManaged =
                SdkLevel.isAtLeastT() ? storageVolume.isExternallyManaged() : false;
        boolean publicVolume = !externallyManaged && !storageVolume.isPrimary();
        return new MediaVolume(name, user, path, id, externallyManaged, publicVolume);
    }

    public static MediaVolume fromInternal() {
        String name = MediaStore.VOLUME_INTERNAL;
        return new MediaVolume(name, null, null, null, false, false);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeParcelable(mUser, flags);
        dest.writeString(mPath.toString());
        dest.writeString(mId);
        dest.writeInt(mExternallyManaged ? 1 : 0);
        dest.writeInt(mPublicVolume ? 1 : 0);
    }

    @Override
    public String toString() {
        return "MediaVolume name: [" + mName + "] id: [" + mId + "] user: [" + mUser + "] path: ["
                + mPath + "] externallyManaged: [" + mExternallyManaged + "] mPublicVolume: ["
                + mPublicVolume + "]";
    }

    public static final @android.annotation.NonNull Creator<MediaVolume> CREATOR
            = new Creator<MediaVolume>() {
        @Override
        public MediaVolume createFromParcel(Parcel in) {
            return new MediaVolume(in);
        }

        @Override
        public MediaVolume[] newArray(int size) {
            return new MediaVolume[size];
        }
    };
}
