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

package com.android.providers.media.util;

import static com.android.providers.media.util.FileUtils.extractDisplayName;
import static com.android.providers.media.util.FileUtils.extractRelativePath;
import static com.android.providers.media.util.Logging.TAG;

import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.providers.media.FileAccessAttributes;

import java.nio.ByteOrder;
import java.util.Optional;

public class XAttrUtils {

    /**
     * Path on which {@link XAttrUtils#DATA_MEDIA_XATTR_DIRECTORY_PATH} is set.
     * /storage/emulated/.. can point to /data/media/.. on ext4/f2fs on modern devices. However, for
     * legacy devices with sdcardfs, it points to /mnt/runtime/.. which then points to
     * /data/media/.. sdcardfs does not support xattrs, hence xattrs are set on /data/media/.. path.
     *
     * TODO(b/220895679): Add logic to handle external sd cards with primary volume with paths
     * /mnt/expand/<volume>/media/<user-id>.
     */
    static final String DATA_MEDIA_XATTR_DIRECTORY_PATH = String.format(
            "/data/media/%s", UserHandle.myUserId());

    static final int SIZE_OF_FILE_ATTRIBUTES = 18;

    /**
     * Flag to turn on reading file metadata through xattr in FUSE file open calls
     */
    public static final boolean ENABLE_XATTR_METADATA_FOR_FUSE =
            SystemProperties.getBoolean("persist.sys.fuse.perf.xattr_metadata_enabled",
                    false);

    /**
     * XAttribute key against which the file metadata is stored
     */
    public static final String FILE_ACCESS_XATTR_KEY = "user.fattr";

    public static Optional<FileAccessAttributes> getFileAttributesFromXAttr(String path,
            String key) {
        Trace.beginSection("XAttrUtils.getFileAttributesFromXAttr");
        String relativePathWithDisplayName = DATA_MEDIA_XATTR_DIRECTORY_PATH + "/"
                + extractRelativePath(path) + extractDisplayName(path);
        try {
            return Optional.of(deserializeFileAccessAttributes(
                    Os.getxattr(relativePathWithDisplayName, key)));
        } catch (ErrnoException e) {
            Log.w(TAG,
                    String.format("Exception encountered while reading xattr:%s from path:%s.", key,
                            path));
            return Optional.empty();
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Serializes file access attributes into byte array that will be stored in the xattr.
     * This method serializes only the id, mediaType, isPending, isTrashed and ownerId fields.
     * @param fileAccessAttributes File attributes to be stored as byte[] in the file inode
     * @return byte[]
     */
    public static byte[] serializeFileAccessAttributes(
            FileAccessAttributes fileAccessAttributes) {
        byte[] bytes = new byte[SIZE_OF_FILE_ATTRIBUTES];
        int offset = 0;
        ByteOrder byteOrder = ByteOrder.nativeOrder();

        Memory.pokeLong(bytes, offset, fileAccessAttributes.getId(), byteOrder);
        offset += Long.BYTES;

        // TODO(b/227753174): Merge mediaType and the booleans in a single byte
        Memory.pokeInt(bytes, offset, fileAccessAttributes.getMediaType(), byteOrder);
        offset += Integer.BYTES;

        bytes[offset++] = (byte) (fileAccessAttributes.isPending() ? 1 : 0);
        bytes[offset++] = (byte) (fileAccessAttributes.isTrashed() ? 1 : 0);

        Memory.pokeInt(bytes, offset, fileAccessAttributes.getOwnerId(), byteOrder);
        offset += Integer.BYTES;
        if (offset != SIZE_OF_FILE_ATTRIBUTES) {
            Log.wtf(TAG, "Error: Serialized byte[] is of unexpected size");
        }
        return bytes;
    }

    /**
     * Deserialize the byte[] data into the corresponding fields - id, mediaType, isPending,
     * isTrashed and ownerId in that order, and returns an instance of FileAccessAttributes
     * containing this deserialized data.
     * @param data Data that is read from the file inode as a result of the xattr call
     * @return FileAccessAttributes
     */
    public static FileAccessAttributes deserializeFileAccessAttributes(byte[] data) {
        ByteOrder byteOrder = ByteOrder.nativeOrder();
        int offset = 0;

        long id = Memory.peekLong(data, offset, byteOrder);
        offset += Long.BYTES;

        int mediaType = Memory.peekInt(data, offset, byteOrder);
        offset += Integer.BYTES;

        boolean isPending = data[offset++] != 0;
        boolean isTrashed = data[offset++] != 0;

        int ownerId = Memory.peekInt(data, offset, byteOrder);
        offset += Integer.BYTES;
        if (offset != SIZE_OF_FILE_ATTRIBUTES) {
            Log.wtf(TAG, " Error: Deserialized attributes are of unexpected size");
        }
        return new FileAccessAttributes(id, mediaType, isPending, isTrashed,
                ownerId, null);
    }
}
