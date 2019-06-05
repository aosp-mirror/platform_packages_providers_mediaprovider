/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.ExifInterface;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.LongArray;

import libcore.io.IoBridge;
import libcore.io.Memory;

import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

/**
 * Simple parser for ISO base media file format. Designed to mirror ergonomics
 * of {@link ExifInterface}.
 */
public class IsoInterface {
    private static final String TAG = "IsoInterface";
    private static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    public static final int BOX_FTYP = 0x66747970;
    public static final int BOX_UUID = 0x75756964;
    public static final int BOX_META = 0x6d657461;
    public static final int BOX_XMP = 0x584d505f;

    public static final int BOX_LOCI = 0x6c6f6369;
    public static final int BOX_XYZ = 0xa978797a;
    public static final int BOX_GPS = 0x67707320;
    public static final int BOX_GPS0 = 0x67707330;

    /**
     * Test if given box type is a well-known parent box type.
     */
    private static boolean isBoxParent(int type) {
        switch (type) {
            case 0x6d6f6f76: // moov
            case 0x6d6f6f66: // moof
            case 0x74726166: // traf
            case 0x6d667261: // mfra
            case 0x7472616b: // trak
            case 0x74726566: // tref
            case 0x6d646961: // mdia
            case 0x6d696e66: // minf
            case 0x64696e66: // dinf
            case 0x7374626c: // stbl
            case 0x65647473: // edts
            case 0x75647461: // udta
            case 0x6970726f: // ipro
            case 0x73696e66: // sinf
            case 0x686e7469: // hnti
            case 0x68696e66: // hinf
            case 0x6a703268: // jp2h
            case 0x696c7374: // ilst
            case 0x6d657461: // meta
                return true;
            default:
                return false;
        }
    }

    /** Top-level boxes */
    private List<Box> mRoots = new ArrayList<>();
    /** Flattened view of all boxes */
    private List<Box> mFlattened = new ArrayList<>();

    private static class Box {
        public final int type;
        public final long[] range;
        public UUID uuid;
        public byte[] data;
        public List<Box> children;

        public Box(int type, long[] range) {
            this.type = type;
            this.range = range;
        }
    }

    private static String typeToString(int type) {
        final byte[] buf = new byte[4];
        Memory.pokeInt(buf, 0, type, ByteOrder.BIG_ENDIAN);
        return new String(buf);
    }

    private static int readInt(@NonNull FileDescriptor fd)
            throws ErrnoException, IOException {
        final byte[] buf = new byte[4];
        if (Os.read(fd, buf, 0, 4) == 4) {
            return Memory.peekInt(buf, 0, ByteOrder.BIG_ENDIAN);
        } else {
            throw new EOFException();
        }
    }

    private static @NonNull UUID readUuid(@NonNull FileDescriptor fd)
            throws ErrnoException, IOException {
        final long high = (((long) readInt(fd)) << 32L) | ((long) readInt(fd)) & 0xffffffffL;
        final long low = (((long) readInt(fd)) << 32L) | ((long) readInt(fd)) & 0xffffffffL;
        return new UUID(high, low);
    }

    private static @Nullable Box parseNextBox(@NonNull FileDescriptor fd, long end,
            @NonNull String prefix) throws ErrnoException, IOException {
        final long pos = Os.lseek(fd, 0, OsConstants.SEEK_CUR);
        if (pos == end) {
            return null;
        }

        final long len = Integer.toUnsignedLong(readInt(fd));
        if (len <= 0 || pos + len > end) {
            Log.w(TAG, "Invalid box at " + pos + " of length " + len
                    + " reached beyond end of parent " + end);
            return null;
        }

        // Skip past legacy data on 'meta' box
        final int type = readInt(fd);
        if (type == BOX_META) {
            readInt(fd);
        }

        final Box box = new Box(type, new long[] { pos, len });
        if (LOGV) {
            Log.v(TAG, prefix + "Found box " + typeToString(type)
                    + " at " + pos + " length " + len);
        }

        // Parse UUID box
        if (type == BOX_UUID) {
            box.uuid = readUuid(fd);
            if (LOGV) {
                Log.v(TAG, prefix + "  UUID " + box.uuid);
            }

            box.data = new byte[(int) (len - 8 - 16)];
            IoBridge.read(fd, box.data, 0, box.data.length);
        }

        // Parse XMP box
        if (type == BOX_XMP) {
            box.data = new byte[(int) (len - 8)];
            IoBridge.read(fd, box.data, 0, box.data.length);
        }

        // Recursively parse any children boxes
        if (isBoxParent(type)) {
            box.children = new ArrayList<>();

            Box child;
            while ((child = parseNextBox(fd, pos + len, prefix + "  ")) != null) {
                box.children.add(child);
            }
        }

        // Skip completely over ourselves
        Os.lseek(fd, pos + len, OsConstants.SEEK_SET);
        return box;
    }

    private IsoInterface(@NonNull FileDescriptor fd) throws IOException {
        try {
            Os.lseek(fd, 4, OsConstants.SEEK_SET);
            if (readInt(fd) != BOX_FTYP) {
                if (LOGV) {
                    Log.w(TAG, "Missing 'ftyp' header");
                }
                return;
            }

            final long end = Os.lseek(fd, 0, OsConstants.SEEK_END);
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
            Box box;
            while ((box = parseNextBox(fd, end, "")) != null) {
                mRoots.add(box);
            }
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }

        // Also create a flattened structure to speed up searching
        final Queue<Box> queue = new LinkedList<>(mRoots);
        while (!queue.isEmpty()) {
            final Box box = queue.poll();
            mFlattened.add(box);
            if (box.children != null) {
                queue.addAll(box.children);
            }
        }
    }

    public static @NonNull IsoInterface fromFile(@NonNull File file)
            throws IOException {
        try (FileInputStream is = new FileInputStream(file)) {
            return fromFileDescriptor(is.getFD());
        }
    }

    public static @NonNull IsoInterface fromFileDescriptor(@NonNull FileDescriptor fd)
            throws IOException {
        return new IsoInterface(fd);
    }

    /**
     * Return a list of content ranges of all boxes of requested type.
     * <p>
     * This is always an array of even length, and all values are in exact file
     * positions (no relative values).
     */
    public @NonNull long[] getBoxRanges(int type) {
        LongArray res = new LongArray();
        for (Box box : mFlattened) {
            if (box.type == type) {
                res.add(box.range[0] + 8);
                res.add(box.range[0] + box.range[1]);
            }
        }
        return res.toArray();
    }

    public @NonNull long[] getBoxRanges(@NonNull UUID uuid) {
        LongArray res = new LongArray();
        for (Box box : mFlattened) {
            if (box.type == BOX_UUID && Objects.equals(box.uuid, uuid)) {
                res.add(box.range[0] + 8 + 16);
                res.add(box.range[0] + box.range[1]);
            }
        }
        return res.toArray();
    }

    /**
     * Return contents of the first box of requested type.
     */
    public @Nullable byte[] getBoxBytes(int type) {
        for (Box box : mFlattened) {
            if (box.type == type) {
                return box.data;
            }
        }
        return null;
    }

    /**
     * Return contents of the first UUID box of requested type.
     */
    public @Nullable byte[] getBoxBytes(@NonNull UUID uuid) {
        for (Box box : mFlattened) {
            if (box.type == BOX_UUID && Objects.equals(box.uuid, uuid)) {
                return box.data;
            }
        }
        return null;
    }
}
