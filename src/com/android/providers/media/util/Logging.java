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

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.Optional;
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

    private static Path sPersistentDir;
    private static SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Initialize persistent logging which is then available through
     * {@link #logPersistent(String)} and {@link #dumpPersistent(PrintWriter)}.
     */
    public static void initPersistent(@NonNull File persistentDir) {
        sPersistentDir = persistentDir.toPath();
    }

    /**
     * Write the given message to persistent logs.
     */
    public static void logPersistent(@NonNull String msg) {
        Log.i(TAG, msg);

        if (sPersistentDir == null) return;
        try (Writer w = Files.newBufferedWriter(resolveCurrentPersistentFile(), CREATE, APPEND)) {
            w.write(sDateFormat.format(new Date()) + " " + msg + "\n");
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist: " + e);
        }
    }

    /**
     * Trim any persistent logs, typically called during idle maintenance.
     */
    public static void trimPersistent() {
        if (sPersistentDir == null) return;
        FileUtils.deleteOlderFiles(sPersistentDir.toFile(), PERSISTENT_COUNT, PERSISTENT_AGE);
    }

    /**
     * Dump any persistent logs.
     */
    public static void dumpPersistent(@NonNull PrintWriter pw) {
        if (sPersistentDir == null) return;
        try {
            Files.list(sPersistentDir).sorted().forEach((path) -> {
                dumpPersistentFile(path, pw);
            });
        } catch (IOException e) {
            pw.println(e.getMessage());
            pw.println();
        }
    }

    private static void dumpPersistentFile(@NonNull Path path, @NonNull PrintWriter pw) {
        pw.println("Persistent logs in " + path + ":");
        try {
            Files.lines(path).forEach((line) -> {
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
    private static @NonNull Path resolveCurrentPersistentFile() throws IOException {
        try (Stream<Path> stream = Files.list(sPersistentDir)) {
            Optional<Path> latest = stream.max(Comparator.naturalOrder());
            if (latest.isPresent() && latest.get().toFile().length() < PERSISTENT_SIZE) {
                return latest.get();
            } else {
                return sPersistentDir.resolve(String.valueOf(System.currentTimeMillis()));
            }
        }
    }
}
