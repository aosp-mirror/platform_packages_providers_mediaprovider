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

import static com.android.providers.media.util.Logging.TAG;

import android.content.Context;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.UserCache;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The VolumeCache class keeps track of all the volumes that are available,
 * as well as their scan paths.
 */
public class VolumeCache {
    private final Context mContext;

    private final Object mLock = new Object();

    private final UserManager mUserManager;
    private final UserCache mUserCache;

    @GuardedBy("mLock")
    private final ArrayList<MediaVolume> mExternalVolumes = new ArrayList<>();

    @GuardedBy("mLock")
    private final Map<MediaVolume, Collection<File>> mCachedVolumeScanPaths = new ArrayMap<>();

    @GuardedBy("mLock")
    private Collection<File> mCachedInternalScanPaths;

    public VolumeCache(Context context, UserCache userCache) {
        mContext = context;
        mUserManager = context.getSystemService(UserManager.class);
        mUserCache = userCache;
    }

    public @NonNull List<MediaVolume> getExternalVolumes() {
        synchronized(mLock) {
            return new ArrayList<>(mExternalVolumes);
        }
    }

    public @NonNull Set<String> getExternalVolumeNames() {
        synchronized (mLock) {
            ArraySet<String> volNames = new ArraySet<String>();
            for (MediaVolume vol : mExternalVolumes) {
                volNames.add(vol.getName());
            }
            return volNames;
        }
    }

    public @NonNull MediaVolume findVolume(@NonNull String volumeName, @NonNull UserHandle user)
            throws FileNotFoundException {
        synchronized (mLock) {
            for (MediaVolume vol : mExternalVolumes) {
                if (vol.getName().equals(volumeName) && vol.isVisibleToUser(user)) {
                    return vol;
                }
            }
        }

        throw new FileNotFoundException("Couldn't find volume with name " + volumeName);
    }

    public @NonNull File getVolumePath(@NonNull String volumeName, @NonNull UserHandle user)
            throws FileNotFoundException {
        synchronized (mLock) {
            try {
                MediaVolume volume = findVolume(volumeName, user);
                return volume.getPath();
            } catch (FileNotFoundException e) {
                if (!MediaStore.isKnownVolume(volumeName)) {
                    Log.w(TAG, "getVolumePath for unknown volume: " + volumeName);
                }
                // Try again by using FileUtils below
            }

            final Context userContext = mUserCache.getContextForUser(user);
            return FileUtils.getVolumePath(userContext, volumeName);
        }
    }

    public @NonNull Collection<File> getVolumeScanPaths(@NonNull String volumeName,
            @NonNull UserHandle user) throws FileNotFoundException {
        synchronized (mLock) {
            if (MediaStore.VOLUME_INTERNAL.equals(volumeName)) {
                // Internal is shared by all users
                return mCachedInternalScanPaths;
            }
            try {
                MediaVolume volume = findVolume(volumeName, user);
                if (mCachedVolumeScanPaths.containsKey(volume)) {
                    return mCachedVolumeScanPaths.get(volume);
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Didn't find cached volume scan paths for " + volumeName);
            }

            // Nothing found above; let's ask directly
            final Context userContext = mUserCache.getContextForUser(user);
            final Collection<File> res = FileUtils.getVolumeScanPaths(userContext, volumeName);

            return res;
        }
    }

    public @NonNull MediaVolume findVolumeForFile(@NonNull File file) throws FileNotFoundException {
        synchronized (mLock) {
            for (MediaVolume volume : mExternalVolumes) {
                if (FileUtils.contains(volume.getPath(), file)) {
                    return volume;
                }
            }
        }

        Log.w(TAG, "Didn't find any volume for getVolume(" + file.getPath() + ")");
        // Nothing found above; let's ask directly
        final StorageManager sm = mContext.getSystemService(StorageManager.class);
        final StorageVolume volume = sm.getStorageVolume(file);
        if (volume == null) {
            throw new FileNotFoundException("Missing volume for " + file);
        }

        return MediaVolume.fromStorageVolume(volume);
    }

    public @NonNull String getVolumeId(@NonNull File file) throws FileNotFoundException {
        MediaVolume volume = findVolumeForFile(file);

        return volume.getId();
    }

    @GuardedBy("mLock")
    private void updateExternalVolumesForUserLocked(Context userContext) {
        final StorageManager sm = userContext.getSystemService(StorageManager.class);
        for (String volumeName : MediaStore.getExternalVolumeNames(userContext)) {
            try {
                final Uri uri = MediaStore.Files.getContentUri(volumeName);
                final StorageVolume storageVolume = sm.getStorageVolume(uri);
                MediaVolume volume = MediaVolume.fromStorageVolume(storageVolume);
                if (volume.getPath() != null) {
                    mExternalVolumes.add(volume);
                    mCachedVolumeScanPaths.put(volume, FileUtils.getVolumeScanPaths(userContext,
                            volume.getName()));
                } else {
                    Log.w(TAG, "Volume:" + volumeName + " has NULL path.");
                }
            } catch (IllegalStateException | FileNotFoundException e) {
                Log.wtf(TAG, "Failed to update volume " + volumeName, e);
            }
        }
    }

    public void update() {
        synchronized (mLock) {
            mCachedVolumeScanPaths.clear();
            try {
                mCachedInternalScanPaths = FileUtils.getVolumeScanPaths(mContext,
                        MediaStore.VOLUME_INTERNAL);
            } catch (FileNotFoundException e) {
                Log.wtf(TAG, "Failed to update volume " + MediaStore.VOLUME_INTERNAL,e );
            }
            mExternalVolumes.clear();
            List<UserHandle> users = mUserCache.updateAndGetUsers();
            for (UserHandle user : users) {
                Context userContext = mUserCache.getContextForUser(user);
                updateExternalVolumesForUserLocked(userContext);
            }
        }
    }

    public void dump(PrintWriter writer) {
        writer.println("Volume cache state:");
        synchronized (mLock) {
            for (MediaVolume volume : mExternalVolumes)  {
                writer.println("  " + volume.toString());
            }
        }
    }
}
