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

import static com.android.providers.media.util.Logging.TAG;

import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {
    public static void closeQuietly(@Nullable AutoCloseable closeable) {
        android.os.FileUtils.closeQuietly(closeable);
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

    /** {@hide} */
    public static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete " + file);
                    success = false;
                }
            }
        }
        return success;
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
            if (lastDot >= 0) {
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
            if (ClipDescription.MIMETYPE_UNKNOWN.equals(mimeType)) {
                extFromMimeType = null;
            } else {
                extFromMimeType = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            }

            if (Objects.equals(mimeType, mimeTypeFromExt) || Objects.equals(ext, extFromMimeType)) {
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
        return context.getSystemService(StorageManager.class).getStorageVolume(uri)
                .getDirectory();
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

    public static boolean isDownload(@NonNull String path) {
        return PATTERN_DOWNLOADS_FILE.matcher(path).matches();
    }

    public static boolean isDownloadDir(@NonNull String path) {
        return PATTERN_DOWNLOADS_DIRECTORY.matcher(path).matches();
    }

    /**
     * Regex that matches any valid path in external storage,
     * and captures the top-level directory as the first group.
     */
    private static final Pattern PATTERN_TOP_LEVEL_DIR = Pattern.compile(
            "(?i)^/storage/[^/]+/[0-9]+/([^/]+)(/.*)?");
    /**
     * Regex that matches paths in all well-known package-specific directories,
     * and which captures the package name as the first group.
     */
    public static final Pattern PATTERN_OWNED_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|media|obb|sandbox)/([^/]+)(/.*)?");

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

    private static @Nullable String normalizeUuid(@Nullable String fsUuid) {
        return fsUuid != null ? fsUuid.toLowerCase(Locale.US) : null;
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
        final Matcher matcher = PATTERN_RELATIVE_PATH.matcher(directoryPath);
        if (matcher.find()) {
            if (matcher.end() == directoryPath.length() - 1) {
                // This is the top-level directory, so relative path is "/"
                return "/";
            }
            return directoryPath.substring(matcher.end()) + "/";
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
     * Returns the name of the top level directory, or null if the path doesn't go through the
     * external storage directory.
     */
    @Nullable
    public static String extractTopLevelDir(String path) {
        Matcher m = PATTERN_TOP_LEVEL_DIR.matcher(path);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    public static void computeDataValues(@NonNull ContentValues values) {
        // Worst case we have to assume no bucket details
        values.remove(ImageColumns.BUCKET_ID);
        values.remove(ImageColumns.BUCKET_DISPLAY_NAME);
        values.remove(ImageColumns.VOLUME_NAME);
        values.remove(ImageColumns.RELATIVE_PATH);

        final String data = values.getAsString(MediaColumns.DATA);
        if (TextUtils.isEmpty(data)) return;

        final File file = new File(data);
        final File fileLower = new File(data.toLowerCase(Locale.ROOT));

        values.put(ImageColumns.VOLUME_NAME, extractVolumeName(data));
        values.put(ImageColumns.RELATIVE_PATH, extractRelativePath(data));
        values.put(ImageColumns.DISPLAY_NAME, extractDisplayName(data));

        // Buckets are the parent directory
        final String parent = fileLower.getParent();
        if (parent != null) {
            values.put(ImageColumns.BUCKET_ID, parent.hashCode());
            // The relative path for files in the top directory is "/"
            if (!"/".equals(values.getAsString(ImageColumns.RELATIVE_PATH))) {
                values.put(ImageColumns.BUCKET_DISPLAY_NAME, file.getParentFile().getName());
            }
        }
    }
}
