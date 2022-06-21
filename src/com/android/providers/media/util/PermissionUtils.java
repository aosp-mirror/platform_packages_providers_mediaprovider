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
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_READ_MEDIA_VIDEO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_AUDIO;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES;
import static android.app.AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.AppOpsManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

public class PermissionUtils {

    public static final String OPSTR_NO_ISOLATED_STORAGE = "android:no_isolated_storage";

    // Callers must hold both the old and new permissions, so that we can
    // handle obscure cases like when an app targets Q but was installed on
    // a device that was originally running on P before being upgraded to Q.

    private static ThreadLocal<String> sOpDescription = new ThreadLocal<>();

    public static void setOpDescription(@Nullable String description) {
        sOpDescription.set(description);
    }

    public static void clearOpDescription() { sOpDescription.set(null); }

    public static boolean checkPermissionSelf(@NonNull Context context, int pid, int uid) {
        return android.os.Process.myUid() == uid;
    }

    public static boolean checkPermissionShell(@NonNull Context context, int pid, int uid) {
        switch (uid) {
            case android.os.Process.ROOT_UID:
            case android.os.Process.SHELL_UID:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if the given package has been granted the "file manager" role on
     * the device, which should grant them certain broader access.
     */
    public static boolean checkPermissionManager(@NonNull Context context, int pid,
            int uid, @NonNull String packageName, @Nullable String attributionTag) {
        if (checkPermissionForDataDelivery(context, MANAGE_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()))) {
            return true;
        }
        // Fallback to OPSTR_NO_ISOLATED_STORAGE app op.
        return checkNoIsolatedStorageGranted(context, uid, packageName, attributionTag);
    }

    /**
     * Check if the given package has the ability to "delegate" the ownership of
     * media items that they own to other apps, typically when they've finished
     * performing operations on behalf of those apps.
     * <p>
     * One use-case for this is backup/restore apps, where the app restoring the
     * content needs to shift the ownership back to the app that originally
     * owned that media.
     * <p>
     * Another use-case is {@link DownloadManager}, which shifts ownership of
     * finished downloads to the app that originally requested them.
     */
    public static boolean checkPermissionDelegator(@NonNull Context context, int pid, int uid) {
        return (context.checkPermission(BACKUP, pid, uid) == PERMISSION_GRANTED)
                || (context.checkPermission(UPDATE_DEVICE_STATS, pid, uid) == PERMISSION_GRANTED);
    }

    public static boolean checkPermissionWriteStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, WRITE_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()));
    }

    public static boolean checkPermissionReadStorage(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkPermissionForDataDelivery(context, READ_EXTERNAL_STORAGE, pid, uid,
                packageName, attributionTag,
                generateAppOpMessage(packageName,sOpDescription.get()));
    }

    public static boolean checkIsLegacyStorageGranted(
            @NonNull Context context, int uid, String packageName) {
        return context.getSystemService(AppOpsManager.class)
                .unsafeCheckOp(OPSTR_LEGACY_STORAGE, uid, packageName) == MODE_ALLOWED;
    }

    public static boolean checkPermissionReadAudio(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionForPreflight(context, READ_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_AUDIO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteAudio(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAllowingNonLegacy(
                    context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_AUDIO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionReadVideo(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionForPreflight(context, READ_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_VIDEO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteVideo(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAllowingNonLegacy(
                context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_VIDEO, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionReadImages(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionForPreflight(context, READ_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_READ_MEDIA_IMAGES, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    public static boolean checkPermissionWriteImages(@NonNull Context context, int pid, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        if (!checkPermissionAllowingNonLegacy(
                context, WRITE_EXTERNAL_STORAGE, pid, uid, packageName)) {
            return false;
        }
        return checkAppOpAllowingLegacy(context, OPSTR_WRITE_MEDIA_IMAGES, pid,
                uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    /**
     * Returns {@code true} if the given package has write images or write video app op, which
     * indicates the package is a system gallery.
     */
    public static boolean checkWriteImagesOrVideoAppOps(@NonNull Context context, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        return checkAppOp(
                context, OPSTR_WRITE_MEDIA_IMAGES, uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()))
                || checkAppOp(
                        context, OPSTR_WRITE_MEDIA_VIDEO, uid, packageName, attributionTag,
                generateAppOpMessage(packageName, sOpDescription.get()));
    }

    @VisibleForTesting
    static boolean checkNoIsolatedStorageGranted(@NonNull Context context, int uid,
            @NonNull String packageName, @Nullable String attributionTag) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int ret = appOps.noteOpNoThrow(OPSTR_NO_ISOLATED_STORAGE, uid, packageName, attributionTag,
                generateAppOpMessage(packageName, "am instrument --no-isolated-storage"));
        return ret == AppOpsManager.MODE_ALLOWED;
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
     * Similar to {@link #checkPermissionForPreflight(Context, String, int, int, String)},
     * but also returns true for non-legacy apps.
     */
    private static boolean checkPermissionAllowingNonLegacy(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @NonNull String packageName) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

        // Allowing non legacy apps to bypass this check
        if (appOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE, uid,
                packageName) != AppOpsManager.MODE_ALLOWED) return true;

        // Seems like it's a legacy app, so it has to pass the permission check
        return checkPermissionForPreflight(context, permission, pid, uid, packageName);
    }

    /**
     * Checks *only* App Ops.
     */
    private static boolean checkAppOp(@NonNull Context context,
            @NonNull String op, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String opMessage) {
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode = appOps.noteOpNoThrow(op, uid, packageName, attributionTag, opMessage);
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
     * Checks *only* App Ops, also returns true for legacy apps.
     */
    private static boolean checkAppOpAllowingLegacy(@NonNull Context context,
            @NonNull String op, int pid, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String opMessage) {
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

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * preflight point where you will not deliver the permission protected data
     * to clients but schedule permission data delivery, apps register listeners,
     * etc.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use this method to determine if the app has or may have location
     * permission (if app has only foreground location the grant state depends on the app's
     * fg/gb state) and this check will not leave a trace that permission protected data
     * was delivered. When you are about to deliver the location data to a registered
     * listener you should use {@link #checkPermissionForDataDelivery(Context, String,
     * int, int, String, String, String)} which will evaluate the permission access based on the
     * current fg/bg state of the app and leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @return boolean if permission is {@link #PERMISSION_GRANTED}
     *
     * @see #checkPermissionForDataDelivery(Context, String, int, int, String, String, String)
     */
    private static boolean checkPermissionForPreflight(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName) {
        return checkPermissionCommon(context, permission, pid, uid, packageName,
                null /*attributionTag*/, null /*message*/,
                false /*forDataDelivery*/);
    }

    /**
     * Checks whether a given package in a UID and PID has a given permission
     * and whether the app op that corresponds to this permission is allowed.
     *
     * <strong>NOTE:</strong> Use this method only for permission checks at the
     * point where you will deliver the permission protected data to clients.
     *
     * <p>For example, if an app registers a location listener it should have the location
     * permission but no data is actually sent to the app at the moment of registration
     * and you should use {@link #checkPermissionForPreflight(Context, String, int, int, String)}
     * to determine if the app has or may have location permission (if app has only foreground
     * location the grant state depends on the app's fg/gb state) and this check will not
     * leave a trace that permission protected data was delivered. When you are about to
     * deliver the location data to a registered listener you should use this method which
     * will evaluate the permission access based on the current fg/bg state of the app and
     * leave a record that the data was accessed.
     *
     * @param context Context for accessing resources.
     * @param permission The permission to check.
     * @param pid The process id for which to check. Use {@link #PID_UNKNOWN} if the PID
     *    is not known.
     * @param uid The uid for which to check.
     * @param packageName The package name for which to check. If null the
     *     the first package for the calling UID will be used.
     * @param attributionTag attribution tag
     * @return boolean true if {@link #PERMISSION_GRANTED}
     * @param message A message describing the reason the permission was checked
     *
     * @see #checkPermissionForPreflight(Context, String, int, int, String)
     */
    private static boolean checkPermissionForDataDelivery(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return checkPermissionCommon(context, permission, pid, uid, packageName, attributionTag,
                message, true /*forDataDelivery*/);
    }

    private static boolean checkPermissionCommon(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        if (packageName == null) {
            String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
            if (packageNames != null && packageNames.length > 0) {
                packageName = packageNames[0];
            }
        }

        if (isAppOpPermission(permission)) {
            return checkAppOpPermission(context, permission, pid, uid, packageName, attributionTag,
                    message, forDataDelivery);
        }
        if (isRuntimePermission(permission)) {
            return checkRuntimePermission(context, permission, pid, uid, packageName,
                    attributionTag, message, forDataDelivery);
        }
        return context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
    }

    private static boolean isAppOpPermission(String permission) {
        switch (permission) {
            case MANAGE_EXTERNAL_STORAGE:
                return true;
        }
        return false;
    }

    private static boolean isRuntimePermission(String permission) {
        switch (permission) {
            case READ_EXTERNAL_STORAGE:
            case WRITE_EXTERNAL_STORAGE:
                return true;
        }
        return false;
    }

    private static boolean checkAppOpPermission(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return false;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteOpNoThrow(op, uid, packageName, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                return context.checkPermission(permission, pid, uid) == PERMISSION_GRANTED;
            default:
                return false;
        }
    }

    private static boolean checkRuntimePermission(@NonNull Context context,
            @NonNull String permission, int pid, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message, boolean forDataDelivery) {
        if (context.checkPermission(permission, pid, uid) == PackageManager.PERMISSION_DENIED) {
            return false;
        }

        final String op = AppOpsManager.permissionToOp(permission);
        if (op == null || packageName == null) {
            return true;
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final int opMode = (forDataDelivery)
                ? appOpsManager.noteOpNoThrow(op, uid, packageName, attributionTag, message)
                : appOpsManager.unsafeCheckOpRawNoThrow(op, uid, packageName);

        switch (opMode) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_FOREGROUND:
                return true;
            default:
                return false;
        }
    }
}
