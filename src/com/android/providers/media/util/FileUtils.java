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

import static android.os.ParcelFileDescriptor.MODE_APPEND;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.system.OsConstants.F_OK;
import static android.system.OsConstants.O_ACCMODE;
import static android.system.OsConstants.O_APPEND;
import static android.system.OsConstants.O_CLOEXEC;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_NOFOLLOW;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.O_TRUNC;
import static android.system.OsConstants.O_WRONLY;
import static android.system.OsConstants.R_OK;
import static android.system.OsConstants.S_IRWXG;
import static android.system.OsConstants.S_IRWXU;
import static android.system.OsConstants.W_OK;

import static com.android.providers.media.util.DatabaseUtils.getAsBoolean;
import static com.android.providers.media.util.DatabaseUtils.getAsLong;
import static com.android.providers.media.util.DatabaseUtils.parseBoolean;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {
    /**
     * Drop-in replacement for {@link ParcelFileDescriptor#open(File, int)}
     * which adds security features like {@link OsConstants#O_CLOEXEC} and
     * {@link OsConstants#O_NOFOLLOW}.
     */
    public static @NonNull ParcelFileDescriptor openSafely(@NonNull File file, int pfdFlags)
            throws FileNotFoundException {
        final int posixFlags = translateModePfdToPosix(pfdFlags) | O_CLOEXEC | O_NOFOLLOW;
        try {
            final FileDescriptor fd = Os.open(file.getAbsolutePath(), posixFlags,
                    S_IRWXU | S_IRWXG);
            try {
                return ParcelFileDescriptor.dup(fd);
            } finally {
                closeQuietly(fd);
            }
        } catch (IOException | ErrnoException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    public static void closeQuietly(@Nullable AutoCloseable closeable) {
        android.os.FileUtils.closeQuietly(closeable);
    }

    public static void closeQuietly(@Nullable FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    public static long copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        return android.os.FileUtils.copy(in, out);
    }

    public static File buildPath(File base, String... segments) {
        File cur = base;
        for (String segment : segments) {
            if (cur == null) {
                cur = new File(segment);
            } else {
                cur = new File(cur, segment);
            }
        }
        return cur;
    }

    /**
     * Delete older files in a directory until only those matching the given
     * constraints remain.
     *
     * @param minCount Always keep at least this many files.
     * @param minAgeMs Always keep files younger than this age, in milliseconds.
     * @return if any files were deleted.
     */
    public static boolean deleteOlderFiles(File dir, int minCount, long minAgeMs) {
        if (minCount < 0 || minAgeMs < 0) {
            throw new IllegalArgumentException("Constraints must be positive or 0");
        }

        final File[] files = dir.listFiles();
        if (files == null) return false;

        // Sort with newest files first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return Long.compare(rhs.lastModified(), lhs.lastModified());
            }
        });

        // Keep at least minCount files
        boolean deleted = false;
        for (int i = minCount; i < files.length; i++) {
            final File file = files[i];

            // Keep files newer than minAgeMs
            final long age = System.currentTimeMillis() - file.lastModified();
            if (age > minAgeMs) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old file " + file);
                    deleted = true;
                }
            }
        }
        return deleted;
    }

    /**
     * Shamelessly borrowed from {@code android.os.FileUtils}.
     */
    public static int translateModeStringToPosix(String mode) {
        // Sanity check for invalid chars
        for (int i = 0; i < mode.length(); i++) {
            switch (mode.charAt(i)) {
                case 'r':
                case 'w':
                case 't':
                case 'a':
                    break;
                default:
                    throw new IllegalArgumentException("Bad mode: " + mode);
            }
        }

        int res = 0;
        if (mode.startsWith("rw")) {
            res = O_RDWR | O_CREAT;
        } else if (mode.startsWith("w")) {
            res = O_WRONLY | O_CREAT;
        } else if (mode.startsWith("r")) {
            res = O_RDONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if (mode.indexOf('t') != -1) {
            res |= O_TRUNC;
        }
        if (mode.indexOf('a') != -1) {
            res |= O_APPEND;
        }
        return res;
    }

    /**
     * Shamelessly borrowed from {@code android.os.FileUtils}.
     */
    public static String translateModePosixToString(int mode) {
        String res = "";
        if ((mode & O_ACCMODE) == O_RDWR) {
            res = "rw";
        } else if ((mode & O_ACCMODE) == O_WRONLY) {
            res = "w";
        } else if ((mode & O_ACCMODE) == O_RDONLY) {
            res = "r";
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & O_TRUNC) == O_TRUNC) {
            res += "t";
        }
        if ((mode & O_APPEND) == O_APPEND) {
            res += "a";
        }
        return res;
    }

    /**
     * Shamelessly borrowed from {@code android.os.FileUtils}.
     */
    public static int translateModePosixToPfd(int mode) {
        int res = 0;
        if ((mode & O_ACCMODE) == O_RDWR) {
            res = MODE_READ_WRITE;
        } else if ((mode & O_ACCMODE) == O_WRONLY) {
            res = MODE_WRITE_ONLY;
        } else if ((mode & O_ACCMODE) == O_RDONLY) {
            res = MODE_READ_ONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & O_CREAT) == O_CREAT) {
            res |= MODE_CREATE;
        }
        if ((mode & O_TRUNC) == O_TRUNC) {
            res |= MODE_TRUNCATE;
        }
        if ((mode & O_APPEND) == O_APPEND) {
            res |= MODE_APPEND;
        }
        return res;
    }

    /**
     * Shamelessly borrowed from {@code android.os.FileUtils}.
     */
    public static int translateModePfdToPosix(int mode) {
        int res = 0;
        if ((mode & MODE_READ_WRITE) == MODE_READ_WRITE) {
            res = O_RDWR;
        } else if ((mode & MODE_WRITE_ONLY) == MODE_WRITE_ONLY) {
            res = O_WRONLY;
        } else if ((mode & MODE_READ_ONLY) == MODE_READ_ONLY) {
            res = O_RDONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & MODE_CREATE) == MODE_CREATE) {
            res |= O_CREAT;
        }
        if ((mode & MODE_TRUNCATE) == MODE_TRUNCATE) {
            res |= O_TRUNC;
        }
        if ((mode & MODE_APPEND) == MODE_APPEND) {
            res |= O_APPEND;
        }
        return res;
    }

    /**
     * Shamelessly borrowed from {@code android.os.FileUtils}.
     */
    public static int translateModeAccessToPosix(int mode) {
        if (mode == F_OK) {
            // There's not an exact mapping, so we attempt a read-only open to
            // determine if a file exists
            return O_RDONLY;
        } else if ((mode & (R_OK | W_OK)) == (R_OK | W_OK)) {
            return O_RDWR;
        } else if ((mode & R_OK) == R_OK) {
            return O_RDONLY;
        } else if ((mode & W_OK) == W_OK) {
            return O_WRONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    public static boolean contains(File[] dirs, File file) {
        for (File dir : dirs) {
            if (contains(dir, file)) {
                return true;
            }
        }
        return false;
    }

    /** {@hide} */
    public static boolean contains(Collection<File> dirs, File file) {
        for (File dir : dirs) {
            if (contains(dir, file)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    public static boolean contains(File dir, File file) {
        if (dir == null || file == null) return false;
        return contains(dir.getAbsolutePath(), file.getAbsolutePath());
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    public static boolean contains(String dirPath, String filePath) {
        if (dirPath.equals(filePath)) {
            return true;
        }
        if (!dirPath.endsWith("/")) {
            dirPath += "/";
        }
        return filePath.startsWith(dirPath);
    }

    /**
     * Write {@link String} to the given {@link File}. Deletes any existing file
     * when the argument is {@link Optional#empty()}.
     */
    public static void writeString(@NonNull File file, @NonNull Optional<String> value)
            throws IOException {
        if (value.isPresent()) {
            Files.write(file.toPath(), value.get().getBytes(StandardCharsets.UTF_8));
        } else {
            file.delete();
        }
    }

    /**
     * Read given {@link File} as a single {@link String}. Returns
     * {@link Optional#empty()} when the file doesn't exist.
     */
    public static @NonNull Optional<String> readString(@NonNull File file) throws IOException {
        try {
            final String value = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            return Optional.of(value);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /**
     * Recursively walk the contents of the given {@link Path}, invoking the
     * given {@link Consumer} for every file and directory encountered. This is
     * typically used for recursively deleting a directory tree.
     * <p>
     * Gracefully attempts to process as much as possible in the face of any
     * failures.
     */
    public static void walkFileTreeContents(@NonNull Path path, @NonNull Consumer<Path> operation) {
        try {
            Files.walkFileTree(path, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Objects.equals(path, file)) {
                        operation.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    Log.w(TAG, "Failed to visit " + file, e);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (!Objects.equals(path, dir)) {
                        operation.accept(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.w(TAG, "Failed to walk " + path, e);
        }
    }

    /**
     * Recursively delete all contents inside the given directory. Gracefully
     * attempts to delete as much as possible in the face of any failures.
     *
     * @deprecated if you're calling this from inside {@code MediaProvider}, you
     *             likely want to call {@link #forEach} with a separate
     *             invocation to invalidate FUSE entries.
     */
    @Deprecated
    public static void deleteContents(@NonNull File dir) {
        walkFileTreeContents(dir.toPath(), (path) -> {
            path.toFile().delete();
        });
    }

    private static boolean isValidFatFilenameChar(char c) {
        if ((0x00 <= c && c <= 0x1f)) {
            return false;
        }
        switch (c) {
            case '"':
            case '*':
            case '/':
            case ':':
            case '<':
            case '>':
            case '?':
            case '\\':
            case '|':
            case 0x7F:
                return false;
            default:
                return true;
        }
    }

    /**
     * Check if given filename is valid for a FAT filesystem.
     *
     * @hide
     */
    public static boolean isValidFatFilename(String name) {
        return (name != null) && name.equals(buildValidFatFilename(name));
    }

    /**
     * Mutate the given filename to make it valid for a FAT filesystem,
     * replacing any invalid characters with "_".
     *
     * @hide
     */
    public static String buildValidFatFilename(String name) {
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) {
            return "(invalid)";
        }
        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (isValidFatFilenameChar(c)) {
                res.append(c);
            } else {
                res.append('_');
            }
        }
        // Even though vfat allows 255 UCS-2 chars, we might eventually write to
        // ext4 through a FUSE layer, so use that limit.
        trimFilename(res, 255);
        return res.toString();
    }

    /** {@hide} */
    // @VisibleForTesting
    public static String trimFilename(String str, int maxBytes) {
        final StringBuilder res = new StringBuilder(str);
        trimFilename(res, maxBytes);
        return res.toString();
    }

    /** {@hide} */
    private static void trimFilename(StringBuilder res, int maxBytes) {
        byte[] raw = res.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) {
            maxBytes -= 3;
            while (raw.length > maxBytes) {
                res.deleteCharAt(res.length() / 2);
                raw = res.toString().getBytes(StandardCharsets.UTF_8);
            }
            res.insert(res.length() / 2, "...");
        }
    }

    /** {@hide} */
    private static File buildUniqueFileWithExtension(File parent, String name, String ext)
            throws FileNotFoundException {
        final Iterator<String> names = buildUniqueNameIterator(parent, name);
        while (names.hasNext()) {
            File file = buildFile(parent, names.next(), ext);
            if (!file.exists()) {
                return file;
            }
        }
        throw new FileNotFoundException("Failed to create unique file");
    }

    private static final Pattern PATTERN_DCF_STRICT = Pattern
            .compile("([A-Z0-9_]{4})([0-9]{4})");
    private static final Pattern PATTERN_DCF_RELAXED = Pattern
            .compile("((?:IMG|MVIMG|VID)_[0-9]{8}_[0-9]{6})(?:~([0-9]+))?");

    private static boolean isDcim(@NonNull File dir) {
        while (dir != null) {
            if (Objects.equals("DCIM", dir.getName())) {
                return true;
            }
            dir = dir.getParentFile();
        }
        return false;
    }

    private static @NonNull Iterator<String> buildUniqueNameIterator(@NonNull File parent,
            @NonNull String name) {
        if (isDcim(parent)) {
            final Matcher dcfStrict = PATTERN_DCF_STRICT.matcher(name);
            if (dcfStrict.matches()) {
                // Generate names like "IMG_1001"
                final String prefix = dcfStrict.group(1);
                return new Iterator<String>() {
                    int i = Integer.parseInt(dcfStrict.group(2));
                    @Override
                    public String next() {
                        final String res = String.format("%s%04d", prefix, i);
                        i++;
                        return res;
                    }
                    @Override
                    public boolean hasNext() {
                        return i <= 9999;
                    }
                };
            }

            final Matcher dcfRelaxed = PATTERN_DCF_RELAXED.matcher(name);
            if (dcfRelaxed.matches()) {
                // Generate names like "IMG_20190102_030405~2"
                final String prefix = dcfRelaxed.group(1);
                return new Iterator<String>() {
                    int i = TextUtils.isEmpty(dcfRelaxed.group(2)) ? 1
                            : Integer.parseInt(dcfRelaxed.group(2));
                    @Override
                    public String next() {
                        final String res = (i == 1) ? prefix : String.format("%s~%d", prefix, i);
                        i++;
                        return res;
                    }
                    @Override
                    public boolean hasNext() {
                        return i <= 99;
                    }
                };
            }
        }

        // Generate names like "foo (2)"
        return new Iterator<String>() {
            int i = 0;
            @Override
            public String next() {
                final String res = (i == 0) ? name : name + " (" + i + ")";
                i++;
                return res;
            }
            @Override
            public boolean hasNext() {
                return i < 32;
            }
        };
    }

    /**
     * Generates a unique file name under the given parent directory. If the display name doesn't
     * have an extension that matches the requested MIME type, the default extension for that MIME
     * type is appended. If a file already exists, the name is appended with a numerical value to
     * make it unique.
     *
     * For example, the display name 'example' with 'text/plain' MIME might produce
     * 'example.txt' or 'example (1).txt', etc.
     *
     * @throws FileNotFoundException
     * @hide
     */
    public static File buildUniqueFile(File parent, String mimeType, String displayName)
            throws FileNotFoundException {
        final String[] parts = splitFileName(mimeType, displayName);
        return buildUniqueFileWithExtension(parent, parts[0], parts[1]);
    }

    /** {@hide} */
    public static File buildNonUniqueFile(File parent, String mimeType, String displayName) {
        final String[] parts = splitFileName(mimeType, displayName);
        return buildFile(parent, parts[0], parts[1]);
    }

    /**
     * Generates a unique file name under the given parent directory, keeping
     * any extension intact.
     *
     * @hide
     */
    public static File buildUniqueFile(File parent, String displayName)
            throws FileNotFoundException {
        final String name;
        final String ext;

        // Extract requested extension from display name
        final int lastDot = displayName.lastIndexOf('.');
        if (lastDot >= 0) {
            name = displayName.substring(0, lastDot);
            ext = displayName.substring(lastDot + 1);
        } else {
            name = displayName;
            ext = null;
        }

        return buildUniqueFileWithExtension(parent, name, ext);
    }

    /**
     * Splits file name into base name and extension.
     * If the display name doesn't have an extension that matches the requested MIME type, the
     * extension is regarded as a part of filename and default extension for that MIME type is
     * appended.
     *
     * @hide
     */
    public static String[] splitFileName(String mimeType, String displayName) {
        String name;
        String ext;

        {
            String mimeTypeFromExt;

            // Extract requested extension from display name
            final int lastDot = displayName.lastIndexOf('.');
            if (lastDot > 0) {
                name = displayName.substring(0, lastDot);
                ext = displayName.substring(lastDot + 1);
                mimeTypeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        ext.toLowerCase(Locale.ROOT));
            } else {
                name = displayName;
                ext = null;
                mimeTypeFromExt = null;
            }

            if (mimeTypeFromExt == null) {
                mimeTypeFromExt = ClipDescription.MIMETYPE_UNKNOWN;
            }

            final String extFromMimeType;
            if (ClipDescription.MIMETYPE_UNKNOWN.equalsIgnoreCase(mimeType)) {
                extFromMimeType = null;
            } else {
                extFromMimeType = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            }

            if (MimeUtils.equalIgnoreCase(mimeType, mimeTypeFromExt)
                    || MimeUtils.equalIgnoreCase(ext, extFromMimeType)) {
                // Extension maps back to requested MIME type; allow it
            } else {
                // No match; insist that create file matches requested MIME
                name = displayName;
                ext = extFromMimeType;
            }
        }

        if (ext == null) {
            ext = "";
        }

        return new String[] { name, ext };
    }

    /** {@hide} */
    private static File buildFile(File parent, String name, String ext) {
        if (TextUtils.isEmpty(ext)) {
            return new File(parent, name);
        } else {
            return new File(parent, name + "." + ext);
        }
    }

    public static @Nullable String extractDisplayName(@Nullable String data) {
        if (data == null) return null;
        if (data.indexOf('/') == -1) {
            return data;
        }
        if (data.endsWith("/")) {
            data = data.substring(0, data.length() - 1);
        }
        return data.substring(data.lastIndexOf('/') + 1);
    }

    public static @Nullable String extractFileName(@Nullable String data) {
        if (data == null) return null;
        data = extractDisplayName(data);

        final int lastDot = data.lastIndexOf('.');
        if (lastDot == -1) {
            return data;
        } else {
            return data.substring(0, lastDot);
        }
    }

    public static @Nullable String extractFileExtension(@Nullable String data) {
        if (data == null) return null;
        data = extractDisplayName(data);

        final int lastDot = data.lastIndexOf('.');
        if (lastDot == -1) {
            return null;
        } else {
            return data.substring(lastDot + 1);
        }
    }

    /**
     * Return list of paths that should be scanned with
     * {@link com.android.providers.media.scan.MediaScanner} for the given
     * volume name.
     */
    public static @NonNull Collection<File> getVolumeScanPaths(@NonNull Context context,
            @NonNull String volumeName) throws FileNotFoundException {
        final ArrayList<File> res = new ArrayList<>();
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL: {
                res.addAll(Environment.getInternalMediaDirectories());
                break;
            }
            case MediaStore.VOLUME_EXTERNAL: {
                for (String resolvedVolumeName : MediaStore.getExternalVolumeNames(context)) {
                    res.add(getVolumePath(context, resolvedVolumeName));
                }
                break;
            }
            default: {
                res.add(getVolumePath(context, volumeName));
            }
        }
        return res;
    }

    /**
     * Return path where the given volume name is mounted.
     */
    public static @NonNull File getVolumePath(@NonNull Context context,
            @NonNull String volumeName) throws FileNotFoundException {
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
            case MediaStore.VOLUME_EXTERNAL:
                throw new FileNotFoundException(volumeName + " has no associated path");
        }

        final Uri uri = MediaStore.Files.getContentUri(volumeName);
        final File path = context.getSystemService(StorageManager.class).getStorageVolume(uri)
                .getDirectory();
        if (path != null) {
            return path;
        } else {
            throw new FileNotFoundException(volumeName + " has no associated path");
        }
    }

    /**
     * Returns the content URI for the volume that contains the given path.
     *
     * <p>{@link MediaStore.Files#getContentUriForPath(String)} can't detect public volumes and can
     * only return the URI for the primary external storage, that's why this utility should be used
     * instead.
     */
    public static @NonNull Uri getContentUriForPath(@NonNull String path) {
        Objects.requireNonNull(path);
        return MediaStore.Files.getContentUri(extractVolumeName(path));
    }

    /**
     * Return volume name which hosts the given path.
     */
    public static @NonNull String getVolumeName(@NonNull Context context, @NonNull File path) {
        if (contains(Environment.getStorageDirectory(), path)) {
            return context.getSystemService(StorageManager.class).getStorageVolume(path)
                    .getMediaStoreVolumeName();
        } else {
            return MediaStore.VOLUME_INTERNAL;
        }
    }

    public static final Pattern PATTERN_DOWNLOADS_FILE = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?(?:Android/sandbox/[^/]+/)?Download/.+");
    public static final Pattern PATTERN_DOWNLOADS_DIRECTORY = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?(?:Android/sandbox/[^/]+/)?Download/?");
    public static final Pattern PATTERN_EXPIRES_FILE = Pattern.compile(
            "(?i)^\\.(pending|trashed)-(\\d+)-([^/]+)$");
    public static final Pattern PATTERN_PENDING_FILEPATH_FOR_SQL = Pattern.compile(
            ".*/\\.pending-(\\d+)-([^/]+)$");

    /**
     * File prefix indicating that the file {@link MediaColumns#IS_PENDING}.
     */
    public static final String PREFIX_PENDING = "pending";

    /**
     * File prefix indicating that the file {@link MediaColumns#IS_TRASHED}.
     */
    public static final String PREFIX_TRASHED = "trashed";

    /**
     * Default duration that {@link MediaColumns#IS_PENDING} items should be
     * preserved for until automatically cleaned by {@link #runIdleMaintenance}.
     */
    public static final long DEFAULT_DURATION_PENDING = 7 * DateUtils.DAY_IN_MILLIS;

    /**
     * Default duration that {@link MediaColumns#IS_TRASHED} items should be
     * preserved for until automatically cleaned by {@link #runIdleMaintenance}.
     */
    public static final long DEFAULT_DURATION_TRASHED = 30 * DateUtils.DAY_IN_MILLIS;

    public static boolean isDownload(@NonNull String path) {
        return PATTERN_DOWNLOADS_FILE.matcher(path).matches();
    }

    public static boolean isDownloadDir(@NonNull String path) {
        return PATTERN_DOWNLOADS_DIRECTORY.matcher(path).matches();
    }

    /**
     * Regex that matches paths in all well-known package-specific directories,
     * and which captures the package name as the first group.
     */
    public static final Pattern PATTERN_OWNED_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|media|obb|sandbox)/([^/]+)(/?.*)?");

    /**
     * Regex that matches Android/obb or Android/data path.
     */
    public static final Pattern PATTERN_DATA_OR_OBB_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|obb)/?$");

    @VisibleForTesting
    public static final String[] DEFAULT_FOLDER_NAMES = {
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_RINGTONES,
            Environment.DIRECTORY_ALARMS,
            Environment.DIRECTORY_NOTIFICATIONS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_AUDIOBOOKS,
    };

    /**
     * Regex that matches paths for {@link MediaColumns#RELATIVE_PATH}; it
     * captures both top-level paths and sandboxed paths.
     */
    private static final Pattern PATTERN_RELATIVE_PATH = Pattern.compile(
            "(?i)^/storage/(?:emulated/[0-9]+/|[^/]+/)(Android/sandbox/([^/]+)/)?");

    /**
     * Regex that matches paths under well-known storage paths.
     */
    private static final Pattern PATTERN_VOLUME_NAME = Pattern.compile(
            "(?i)^/storage/([^/]+)");

    private static final String CAMERA_RELATIVE_PATH =
            String.format("%s/%s/", Environment.DIRECTORY_DCIM, "Camera");

    private static @Nullable String normalizeUuid(@Nullable String fsUuid) {
        return fsUuid != null ? fsUuid.toLowerCase(Locale.ROOT) : null;
    }

    public static @Nullable String extractVolumePath(@Nullable String data) {
        if (data == null) return null;
        final Matcher matcher = PATTERN_RELATIVE_PATH.matcher(data);
        if (matcher.find()) {
            return data.substring(0, matcher.end());
        } else {
            return null;
        }
    }

    public static @Nullable String extractVolumeName(@Nullable String data) {
        if (data == null) return null;
        final Matcher matcher = PATTERN_VOLUME_NAME.matcher(data);
        if (matcher.find()) {
            final String volumeName = matcher.group(1);
            if (volumeName.equals("emulated")) {
                return MediaStore.VOLUME_EXTERNAL_PRIMARY;
            } else {
                return normalizeUuid(volumeName);
            }
        } else {
            return MediaStore.VOLUME_INTERNAL;
        }
    }

    public static @Nullable String extractRelativePath(@Nullable String data) {
        if (data == null) return null;
        final Matcher matcher = PATTERN_RELATIVE_PATH.matcher(data);
        if (matcher.find()) {
            final int lastSlash = data.lastIndexOf('/');
            if (lastSlash == -1 || lastSlash < matcher.end()) {
                // This is a file in the top-level directory, so relative path is "/"
                // which is different than null, which means unknown path
                return "/";
            } else {
                return data.substring(matcher.end(), lastSlash + 1);
            }
        } else {
            return null;
        }
    }

    /**
     * Returns relative path for the directory.
     */
    @VisibleForTesting
    public static @Nullable String extractRelativePathForDirectory(@Nullable String directoryPath) {
        if (directoryPath == null) return null;

        if (directoryPath.equals("/storage/emulated") ||
                directoryPath.equals("/storage/emulated/")) {
            // This path is not reachable for MediaProvider.
            return null;
        }

        // We are extracting relative path for the directory itself, we add "/" so that we can use
        // same PATTERN_RELATIVE_PATH to match relative path for directory. For example, relative
        // path of '/storage/<volume_name>' is null where as relative path for directory is "/", for
        // PATTERN_RELATIVE_PATH to match '/storage/<volume_name>', it should end with "/".
        if (!directoryPath.endsWith("/")) {
            // Relative path for directory should end with "/".
            directoryPath += "/";
        }

        final Matcher matcher = PATTERN_RELATIVE_PATH.matcher(directoryPath);
        if (matcher.find()) {
            if (matcher.end() == directoryPath.length()) {
                // This is the top-level directory, so relative path is "/"
                return "/";
            }
            return directoryPath.substring(matcher.end());
        }
        return null;
    }

    public static @Nullable String extractPathOwnerPackageName(@Nullable String path) {
        if (path == null) return null;
        final Matcher m = PATTERN_OWNED_PATH.matcher(path);
        if (m.matches()) {
            return m.group(1);
        } else {
            return null;
        }
    }

    /**
     * Returns true if relative path is Android/data or Android/obb path.
     */
    public static boolean isDataOrObbPath(String path) {
        if (path == null) return false;
        final Matcher m = PATTERN_DATA_OR_OBB_PATH.matcher(path);
        return m.matches();
    }

    /**
     * Returns the name of the top level directory, or null if the path doesn't go through the
     * external storage directory.
     */
    @Nullable
    public static String extractTopLevelDir(String path) {
        final String relativePath = extractRelativePath(path);
        if (relativePath == null) {
            return null;
        }
        final String[] relativePathSegments = relativePath.split("/");
        return relativePathSegments.length > 0 ? relativePathSegments[0] : null;
    }

    public static boolean isDefaultDirectoryName(@Nullable String dirName) {
        for (String defaultDirName : DEFAULT_FOLDER_NAMES) {
            if (defaultDirName.equalsIgnoreCase(dirName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute the value of {@link MediaColumns#DATE_EXPIRES} based on other
     * columns being modified by this operation.
     */
    public static void computeDateExpires(@NonNull ContentValues values) {
        // External apps have no ability to change this field
        values.remove(MediaColumns.DATE_EXPIRES);

        // Only define the field when this modification is actually adjusting
        // one of the flags that should influence the expiration
        final Object pending = values.get(MediaColumns.IS_PENDING);
        if (pending != null) {
            if (parseBoolean(pending, false)) {
                values.put(MediaColumns.DATE_EXPIRES,
                        (System.currentTimeMillis() + DEFAULT_DURATION_PENDING) / 1000);
            } else {
                values.putNull(MediaColumns.DATE_EXPIRES);
            }
        }
        final Object trashed = values.get(MediaColumns.IS_TRASHED);
        if (trashed != null) {
            if (parseBoolean(trashed, false)) {
                values.put(MediaColumns.DATE_EXPIRES,
                        (System.currentTimeMillis() + DEFAULT_DURATION_TRASHED) / 1000);
            } else {
                values.putNull(MediaColumns.DATE_EXPIRES);
            }
        }
    }

    /**
     * Compute several scattered {@link MediaColumns} values from
     * {@link MediaColumns#DATA}. This method performs no enforcement of
     * argument validity.
     */
    public static void computeValuesFromData(@NonNull ContentValues values, boolean isForFuse) {
        // Worst case we have to assume no bucket details
        values.remove(MediaColumns.VOLUME_NAME);
        values.remove(MediaColumns.RELATIVE_PATH);
        values.remove(MediaColumns.IS_TRASHED);
        values.remove(MediaColumns.DATE_EXPIRES);
        values.remove(MediaColumns.DISPLAY_NAME);
        values.remove(MediaColumns.BUCKET_ID);
        values.remove(MediaColumns.BUCKET_DISPLAY_NAME);

        final String data = values.getAsString(MediaColumns.DATA);
        if (TextUtils.isEmpty(data)) return;

        final File file = new File(data);
        final File fileLower = new File(data.toLowerCase(Locale.ROOT));

        values.put(MediaColumns.VOLUME_NAME, extractVolumeName(data));
        values.put(MediaColumns.RELATIVE_PATH, extractRelativePath(data));
        final String displayName = extractDisplayName(data);
        final Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(displayName);
        if (matcher.matches()) {
            values.put(MediaColumns.IS_PENDING,
                    matcher.group(1).equals(FileUtils.PREFIX_PENDING) ? 1 : 0);
            values.put(MediaColumns.IS_TRASHED,
                    matcher.group(1).equals(FileUtils.PREFIX_TRASHED) ? 1 : 0);
            values.put(MediaColumns.DATE_EXPIRES, Long.parseLong(matcher.group(2)));
            values.put(MediaColumns.DISPLAY_NAME, matcher.group(3));
        } else {
            if (isForFuse) {
                // Allow Fuse thread to set IS_PENDING when using DATA column.
                // TODO(b/156867379) Unset IS_PENDING when Fuse thread doesn't explicitly specify
                // IS_PENDING. It can't be done now because we scan after create. Scan doesn't
                // explicitly specify the value of IS_PENDING.
            } else {
                values.put(MediaColumns.IS_PENDING, 0);
            }
            values.put(MediaColumns.IS_TRASHED, 0);
            values.putNull(MediaColumns.DATE_EXPIRES);
            values.put(MediaColumns.DISPLAY_NAME, displayName);
        }

        // Buckets are the parent directory
        final String parent = fileLower.getParent();
        if (parent != null) {
            values.put(MediaColumns.BUCKET_ID, parent.hashCode());
            // The relative path for files in the top directory is "/"
            if (!"/".equals(values.getAsString(MediaColumns.RELATIVE_PATH))) {
                values.put(MediaColumns.BUCKET_DISPLAY_NAME, file.getParentFile().getName());
            }
        }
    }

    /**
     * Compute {@link MediaColumns#DATA} from several scattered
     * {@link MediaColumns} values.  This method performs no enforcement of
     * argument validity.
     */
    public static void computeDataFromValues(@NonNull ContentValues values,
            @NonNull File volumePath, boolean isForFuse) {
        values.remove(MediaColumns.DATA);

        final String displayName = values.getAsString(MediaColumns.DISPLAY_NAME);
        final String resolvedDisplayName;
        // Pending file path shouldn't be rewritten for files inserted via filepath.
        if (!isForFuse && getAsBoolean(values, MediaColumns.IS_PENDING, false)) {
            final long dateExpires = getAsLong(values, MediaColumns.DATE_EXPIRES,
                    (System.currentTimeMillis() + DEFAULT_DURATION_PENDING) / 1000);
            resolvedDisplayName = String.format(".%s-%d-%s",
                    FileUtils.PREFIX_PENDING, dateExpires, displayName);
        } else if (getAsBoolean(values, MediaColumns.IS_TRASHED, false)) {
            final long dateExpires = getAsLong(values, MediaColumns.DATE_EXPIRES,
                    (System.currentTimeMillis() + DEFAULT_DURATION_TRASHED) / 1000);
            resolvedDisplayName = String.format(".%s-%d-%s",
                    FileUtils.PREFIX_TRASHED, dateExpires, displayName);
        } else {
            resolvedDisplayName = displayName;
        }

        final File filePath = buildPath(volumePath,
                values.getAsString(MediaColumns.RELATIVE_PATH), resolvedDisplayName);
        values.put(MediaColumns.DATA, filePath.getAbsolutePath());
    }

    public static void sanitizeValues(@NonNull ContentValues values,
            boolean rewriteHiddenFileName) {
        final String[] relativePath = values.getAsString(MediaColumns.RELATIVE_PATH).split("/");
        for (int i = 0; i < relativePath.length; i++) {
            relativePath[i] = sanitizeDisplayName(relativePath[i], rewriteHiddenFileName);
        }
        values.put(MediaColumns.RELATIVE_PATH,
                String.join("/", relativePath) + "/");

        final String displayName = values.getAsString(MediaColumns.DISPLAY_NAME);
        values.put(MediaColumns.DISPLAY_NAME,
                sanitizeDisplayName(displayName, rewriteHiddenFileName));
    }

    /** {@hide} **/
    @Nullable
    public static String getAbsoluteSanitizedPath(String path) {
        final String[] pathSegments = sanitizePath(path);
        if (pathSegments.length == 0) {
            return null;
        }
        return path = "/" + String.join("/",
                Arrays.copyOfRange(pathSegments, 1, pathSegments.length));
    }

    /** {@hide} */
    public static @NonNull String[] sanitizePath(@Nullable String path) {
        if (path == null) {
            return new String[0];
        } else {
            final String[] segments = path.split("/");
            // If the path corresponds to the top level directory, then we return an empty path
            // which denotes the top level directory
            if (segments.length == 0) {
                return new String[] { "" };
            }
            for (int i = 0; i < segments.length; i++) {
                segments[i] = sanitizeDisplayName(segments[i]);
            }
            return segments;
        }
    }

    /**
     * Sanitizes given name by mutating the file name to make it valid for a FAT filesystem.
     * @hide
     */
    public static @Nullable String sanitizeDisplayName(@Nullable String name) {
        return sanitizeDisplayName(name, /*rewriteHiddenFileName*/ false);
    }

    /**
     * Sanitizes given name by appending '_' to make it non-hidden and mutating the file name to
     * make it valid for a FAT filesystem.
     * @hide
     */
    public static @Nullable String sanitizeDisplayName(@Nullable String name,
            boolean rewriteHiddenFileName) {
        if (name == null) {
            return null;
        } else if (rewriteHiddenFileName && name.startsWith(".")) {
            // The resulting file must not be hidden.
            return "_" + name;
        } else {
            return buildValidFatFilename(name);
        }
    }

    /**
     * Test if this given directory should be considered hidden.
     */
    @VisibleForTesting
    public static boolean isDirectoryHidden(@NonNull File dir) {
        final String name = dir.getName();
        if (name.startsWith(".")) {
            return true;
        }

        final File nomedia = new File(dir, ".nomedia");

        // check for .nomedia presence
        if (!nomedia.exists()) {
            return false;
        }

        // Handle top-level default directories. These directories should always be visible,
        // regardless of .nomedia presence.
        final String[] relativePath = sanitizePath(extractRelativePath(dir.getAbsolutePath()));
        final boolean isTopLevelDir =
                relativePath.length == 1 && TextUtils.isEmpty(relativePath[0]);
        if (isTopLevelDir && isDefaultDirectoryName(name)) {
            nomedia.delete();
            return false;
        }

        // DCIM/Camera should always be visible regardless of .nomedia presence.
        if (CAMERA_RELATIVE_PATH.equalsIgnoreCase(
                extractRelativePathForDirectory(dir.getAbsolutePath()))) {
            nomedia.delete();
            return false;
        }

        // .nomedia is present which makes this directory as hidden directory
        Logging.logPersistent("Observed non-standard " + nomedia);
        return true;
    }

    /**
     * Test if this given file should be considered hidden.
     */
    @VisibleForTesting
    public static boolean isFileHidden(@NonNull File file) {
        final String name = file.getName();

        // Handle well-known file names that are pending or trashed; they
        // normally appear hidden, but we give them special treatment
        if (PATTERN_EXPIRES_FILE.matcher(name).matches()) {
            return false;
        }

        // Otherwise fall back to file name
        if (name.startsWith(".")) {
            return true;
        }
        return false;
    }

    /**
     * Clears all app's external cache directories, i.e. for each app we delete
     * /sdcard/Android/data/app/cache/* but we keep the directory itself.
     *
     * @return 0 in case of success, or {@link OsConstants#EIO} if any error occurs.
     *
     * <p>This method doesn't perform any checks, so make sure that the calling package is allowed
     * to clear cache directories first.
     *
     * <p>If this method returned {@link OsConstants#EIO}, then we can't guarantee whether all, none
     * or part of the directories were cleared.
     */
    public static int clearAppCacheDirectories() {
        int status = 0;
        Log.i(TAG, "Clearing cache for all apps");
        final File rootDataDir = buildPath(Environment.getExternalStorageDirectory(),
                "Android", "data");
        for (File appDataDir : rootDataDir.listFiles()) {
            try {
                final File appCacheDir = new File(appDataDir, "cache");
                if (appCacheDir.isDirectory()) {
                    FileUtils.deleteContents(appCacheDir);
                }
            } catch (Exception e) {
                // We want to avoid crashing MediaProvider at all costs, so we handle all "generic"
                // exceptions here, and just report to the caller that an IO exception has occurred.
                // We still try to clear the rest of the directories.
                Log.e(TAG, "Couldn't delete all app cache dirs!", e);
                status = OsConstants.EIO;
            }
        }
        return status;
    }
}
