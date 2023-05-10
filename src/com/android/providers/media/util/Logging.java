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

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import android.os.SystemProperties;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.stream.Stream;

public class Logging {
    public static final String TAG = "MediaProvider";
    public static final boolean LOGW = Log.isLoggable(TAG, Log.WARN);
    public static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    public static final boolean IS_DEBUGGABLE =
            SystemProperties.getInt("ro.debuggable", 0) == 1;

    /** Size limit of each persistent log file, in bytes */
    private static final int PERSISTENT_SIZE = 32 * 1024;
    private static final int PERSISTENT_COUNT = 4;
    private static final long PERSISTENT_AGE = DateUtils.WEEK_IN_MILLIS;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final Object LOCK = new Object();

    @GuardedBy("LOCK")
    private static Path sPersistentDir;
    @GuardedBy("LOCK")
    private static Path sPersistentFile;
    @GuardedBy("LOCK")
    private static Writer sWriter;

    /**
     * Initialize persistent logging which is then available through
     * {@link #logPersistent(String)} and {@link #dumpPersistent(PrintWriter)}.
     */
    public static void initPersistent(@NonNull File persistentDir) {
        synchronized (LOCK) {
            sPersistentDir = persistentDir.toPath();
            closeWriterAndUpdatePathLocked(null);
        }
    }

    /**
     * Write the given message to persistent logs.
     */
    public static void logPersistent(@NonNull String format, @Nullable Object ... args) {
        final String msg = (args == null || args.length == 0)
                ? format : String.format(Locale.ROOT, format, args);

        Log.i(TAG, msg);

        synchronized (LOCK) {
            if (sPersistentDir == null) return;

            try {
                Path path = resolveCurrentPersistentFileLocked();
                if (!path.equals(sPersistentFile)) {
                    closeWriterAndUpdatePathLocked(path);
                }

                if (sWriter == null) {
                    sWriter = Files.newBufferedWriter(path, CREATE, APPEND);
                }

                sWriter.write(DATE_FORMAT.format(new Date()) + " " + msg + "\n");
                // Flush to guarantee that all our writes have been sent to the filesystem
                sWriter.flush();
            } catch (IOException e) {
                closeWriterAndUpdatePathLocked(null);
                Log.w(TAG, "Failed to write: " + sPersistentFile, e);
            }
        }
    }

    @GuardedBy("LOCK")
    private static void closeWriterAndUpdatePathLocked(@Nullable Path newPath) {
        if (sWriter != null) {
            try {
                sWriter.close();
            } catch (IOException ignored) {
                Log.w(TAG, "Failed to close: " + sPersistentFile, ignored);
            }
            sWriter = null;
        }
        sPersistentFile = newPath;
    }

    /**
     * Trim any persistent logs, typically called during idle maintenance.
     */
    public static void trimPersistent() {
        File persistentDir = null;
        synchronized (LOCK) {
            if (sPersistentDir == null) return;
            persistentDir = sPersistentDir.toFile();

            closeWriterAndUpdatePathLocked(sPersistentFile);
        }

        FileUtils.deleteOlderFiles(persistentDir, PERSISTENT_COUNT, PERSISTENT_AGE);
    }

    /**
     * Dump any persistent logs.
     */
    public static void dumpPersistent(@NonNull PrintWriter pw) {
        Path persistentDir = null;
        synchronized (LOCK) {
            if (sPersistentDir == null) return;
            persistentDir = sPersistentDir;
        }

        try (Stream<Path> stream = Files.list(persistentDir)) {
            stream.sorted().forEach((path) -> {
                dumpPersistentFile(path, pw);
            });
        } catch (IOException e) {
            pw.println(e.getMessage());
            pw.println();
        }
    }

    private static void dumpPersistentFile(@NonNull Path path, @NonNull PrintWriter pw) {
        pw.println("Persistent logs in " + path + ":");
        try (Stream<String> stream = Files.lines(path)) {
            stream.forEach((line) -> {
                pw.println("  " + line);
            });
            pw.println();
        } catch (IOException e) {
            pw.println("  " + e.getMessage());
            pw.println();
        }
    }

    /**
     * Resolve the current log file to write new entries into. Automatically
     * starts new files when the current file is larger than
     * {@link #PERSISTENT_SIZE}.
     */
    @GuardedBy("LOCK")
    private static @NonNull Path resolveCurrentPersistentFileLocked() throws IOException {
        if (sPersistentFile != null && sPersistentFile.toFile().length() < PERSISTENT_SIZE) {
            return sPersistentFile;
        }

        return sPersistentDir.resolve(String.valueOf(System.currentTimeMillis()));
    }
}
