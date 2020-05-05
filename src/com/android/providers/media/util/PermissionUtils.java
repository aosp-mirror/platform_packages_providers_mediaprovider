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

import static android.Manifest.permission.BACKUP;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.app.AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.AppOpsManager;
import android.content.Context;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class PermissionUtils {
    // Callers must hold both the old and new permissions, so that we can
    // handle obscure cases like when an app targets Q but was installed on
    // a device that was originally running on P before being upgraded to Q.

    private static volatile int sLegacyMediaProviderUid = -1;

    private static ThreadLocal<String> sOpDescription = new ThreadLocal<>();

    public static void setOpDescription(@Nullable String description) {
        sOpDescription.set(description);
    }

    public static void clearOpDescription() { sOpDescription.set(null); }

    public static boolean checkPermissionSystem(
            @NonNull Context context, int pid, int uid, String packageName) {
        // Apps sharing legacy MediaProvider's uid like DownloadProvider and MTP are treated as
        // system.
        return uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.myUid()
                || uid == android.os.Process.SHELL_UID || uid == android.os.Process.ROOT_UID
                || isLegacyMediaProvider(context, uid);
    }

    public static boolean checkPermissionBackup(@NonNull Context context, int pid, int uid) {
        return context.checkPermission(BACKUP, pid, uid) == PERMISSION_GRANTED;
    }

    public static boolean checkPermissionManageExternalStorage(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag) {
        return noteAppOpPermission(context, pid, uid, packageName, OPSTR_MANAGE_EXTERNAL_STORAGE,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return noteAppOpPermission(context, pid, uid, packageName, OPSTR_WRITE_EXTERNAL_STORAGE,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionReadStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return noteAppOpPermission(context, pid, uid, packageName, OPSTR_READ_EXTERNAL_STORAGE,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkIsLegacyStorageGranted(
            @NonNull Context context, int uid, String packageName) {
        return context.getSystemService(AppOpsManager.class)
                .unsafeCheckOp(OPSTR_LEGACY_STORAGE, uid, packageName) == MODE_ALLOWED;
    }

    public static boolean checkPermissionReadAudio(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOp(context, pid, uid, packageName, OPSTR_READ_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_READ_MEDIA_AUDIO,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteAudio(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOpAllowingNonLegacy(
                    context, pid, uid, packageName, OPSTR_WRITE_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_WRITE_MEDIA_AUDIO,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionReadVideo(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOp(context, pid, uid, packageName, OPSTR_READ_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_READ_MEDIA_VIDEO,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteVideo(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOpAllowingNonLegacy(
                    context, pid, uid, packageName, OPSTR_WRITE_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_WRITE_MEDIA_VIDEO,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionReadImages(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOp(context, pid, uid, packageName, OPSTR_READ_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_READ_MEDIA_IMAGES,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteImages(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAppOpAllowingNonLegacy(
                    context, pid, uid, packageName, OPSTR_WRITE_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(context, pid, uid, packageName, OPSTR_WRITE_MEDIA_IMAGES,
                attributionTag, generateAppOpMessage(packageName, sOpDescription.get()));
    }

    /**
     * Generates a message to be used with the different {@link AppOpsManager#noteOp} variations.
     * If the supplied description is {@code null}, the returned message will be {@code null}.
     */
    private static String generateAppOpMessage(
            @NonNull String packageName, @Nullable String description) {
        if (description == null) {
            return null;
        }
        return "Package: " + packageName + ". Description: " + description + ".";
    }

    /**
     * Checks the permission associated with the given app-op, if it's not granted, returns false.
     * Else, checks the app-op and returns true iff it's {@link AppOpsManager#MODE_ALLOWED}.
     * The permission is retrieved from {@link AppOpsManager#opToPermission(String)}.
     */
    private static boolean checkPermissionAppOp(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @NonNull String op) {
        final String permission = AppOpsManager.opToPermission(op);
        if (permission != null
                && context.checkPermission(permission, pid, uid) != PERMISSION_GRANTED) {
            return false;
        }
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        try {
            appOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            return false;
        }

        final int mode = appOps.unsafeCheckOpNoThrow(op, uid, packageName);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    /**
     * Similar to {@link #checkPermissionAppOp(Context, int, int, String, String)}, but also returns
     * true for non-legacy apps.
     * @see #checkPermissionAppOp
     */
    private static boolean checkPermissionAppOpAllowingNonLegacy(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @NonNull String op) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

        // Allowing non legacy apps to bypass this check
        if (appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                packageName) != AppOpsManager.MODE_ALLOWED) return true;

        // Seems like it's a legacy app, so it has to pass the permission and app-op check
        return checkPermissionAppOp(context, pid, uid, packageName, op);
    }

    /**
     * Notes app-op for the callings package. If its app-op mode is
     * {@link AppOpsManager#MODE_DEFAULT} then it falls back to checking the appropriate permission
     * for the app-op. The permission is retrieved from
     * {@link AppOpsManager#opToPermission(String)}.
     */
    private static boolean noteAppOpPermission(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @NonNull String op, @Nullable String attributionTag,
            @Nullable String opMessage) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = appOps.noteOpNoThrow(op, uid, packageName, attributionTag, opMessage);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                final String permission = AppOpsManager.opToPermission(op);
                return permission != null
                        && context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    /**
     * Similar to  {@link #noteAppOpPermission(Context, int, int, String, String, String, String)},
     * but also returns true for legacy apps.
     */
    private static boolean noteAppOpAllowingLegacy(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @NonNull String op, @Nullable String attributionTag,
            @Nullable String opMessage) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = appOps.noteOpNoThrow(op, uid, packageName, attributionTag, opMessage);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                // Legacy apps technically have the access granted by this op,
                // even when the op is denied
                if ((appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                        packageName) == AppOpsManager.MODE_ALLOWED)) return true;

                return false;
            default:
                throw new IllegalStateException(op + " has unknown mode " + mode);
        }
    }

    private static boolean isLegacyMediaProvider(Context context, int uid) {
        if (sLegacyMediaProviderUid == -1) {
            // Uid stays constant while legacy Media Provider stays installed. Cache legacy
            // MediaProvider's uid for the first time.
            sLegacyMediaProviderUid = context.getPackageManager()
                    .resolveContentProvider(MediaStore.AUTHORITY_LEGACY, 0)
                    .applicationInfo.uid;
        }
        return (uid == sLegacyMediaProviderUid);
    }
}
