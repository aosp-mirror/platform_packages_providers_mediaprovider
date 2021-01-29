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

package com.android.providers.media;

import static android.Manifest.permission.ACCESS_MEDIA_LOCATION;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.permissionToOp;
import static android.content.pm.PackageManager.PERMISSION_DENIED;

import static com.android.providers.media.util.PermissionUtils.checkIsLegacyStorageGranted;
import static com.android.providers.media.util.PermissionUtils.checkPermissionDelegator;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManager;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVideo;
import static com.android.providers.media.util.PermissionUtils.checkPermissionSelf;
import static com.android.providers.media.util.PermissionUtils.checkPermissionShell;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionWriteVideo;
import static com.android.providers.media.util.PermissionUtils.checkWriteImagesOrVideoAppOps;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.providers.media.util.LongArray;

public class LocalCallingIdentity {
    public final Context context;
    public final int pid;
    public final int uid;
    public final String packageNameUnchecked;
    // Info used for logging permission checks
    public @Nullable String attributionTag;

    private LocalCallingIdentity(Context context, int pid, int uid, String packageNameUnchecked,
            @Nullable String attributionTag) {
        this.context = context;
        this.pid = pid;
        this.uid = uid;
        this.packageNameUnchecked = packageNameUnchecked;
        this.attributionTag = attributionTag;
    }

    /**
     * See definition in {@link android.os.Environment}
     */
    private static final long DEFAULT_SCOPED_STORAGE = 149924527L;

    /**
     * See definition in {@link android.os.Environment}
     */
    private static final long FORCE_ENABLE_SCOPED_STORAGE = 132649864L;

    private static final long UNKNOWN_ROW_ID = -1;

    public static LocalCallingIdentity fromBinder(Context context, ContentProvider provider) {
        String callingPackage = provider.getCallingPackageUnchecked();
        if (callingPackage == null) {
            callingPackage = context.getOpPackageName();
        }
        String callingAttributionTag = provider.getCallingAttributionTag();
        if (callingAttributionTag == null) {
            callingAttributionTag = context.getAttributionTag();
        }
        return new LocalCallingIdentity(context, Binder.getCallingPid(), Binder.getCallingUid(),
                callingPackage, callingAttributionTag);
    }

    public static LocalCallingIdentity fromExternal(Context context, int uid) {
        final String[] sharedPackageNames = context.getPackageManager().getPackagesForUid(uid);
        if (sharedPackageNames == null || sharedPackageNames.length == 0) {
            throw new IllegalArgumentException("UID " + uid + " has no associated package");
        }
        LocalCallingIdentity ident =  fromExternal(context, uid, sharedPackageNames[0], null);
        ident.sharedPackageNames = sharedPackageNames;
        ident.sharedPackageNamesResolved = true;
        if (uid == Process.SHELL_UID) {
            // This is useful for debugging/testing/development
            if (SystemProperties.getBoolean("persist.sys.fuse.shell.redaction-needed", false)) {
                ident.hasPermission |= PERMISSION_IS_REDACTION_NEEDED;
                ident.hasPermissionResolved = PERMISSION_IS_REDACTION_NEEDED;
            }
        }
        return ident;
    }

    public static LocalCallingIdentity fromExternal(Context context, int uid, String packageName,
            @Nullable String attributionTag) {
        return new LocalCallingIdentity(context, -1, uid, packageName, attributionTag);
    }

    public static LocalCallingIdentity fromSelf(Context context) {
        final LocalCallingIdentity ident = new LocalCallingIdentity(
                context,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                context.getOpPackageName(),
                context.getAttributionTag());

        ident.packageName = ident.packageNameUnchecked;
        ident.packageNameResolved = true;
        // Use ident.attributionTag from context, hence no change
        ident.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        ident.targetSdkVersionResolved = true;
        ident.hasPermission = ~(PERMISSION_IS_LEGACY_GRANTED | PERMISSION_IS_LEGACY_WRITE
                | PERMISSION_IS_LEGACY_READ | PERMISSION_IS_REDACTION_NEEDED
                | PERMISSION_IS_SHELL | PERMISSION_IS_DELEGATOR);
        ident.hasPermissionResolved = ~0;
        return ident;
    }

    private String packageName;
    private boolean packageNameResolved;

    public String getPackageName() {
        if (!packageNameResolved) {
            packageName = getPackageNameInternal();
            packageNameResolved = true;
        }
        return packageName;
    }

    private String getPackageNameInternal() {
        // Verify that package name is actually owned by UID
        context.getSystemService(AppOpsManager.class)
                .checkPackage(uid, packageNameUnchecked);
        return packageNameUnchecked;
    }

    private String[] sharedPackageNames;
    private boolean sharedPackageNamesResolved;

    public String[] getSharedPackageNames() {
        if (!sharedPackageNamesResolved) {
            sharedPackageNames = getSharedPackageNamesInternal();
            sharedPackageNamesResolved = true;
        }
        return sharedPackageNames;
    }

    private String[] getSharedPackageNamesInternal() {
        final String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
        return (packageNames != null) ? packageNames : new String[0];
    }

    private int targetSdkVersion;
    private boolean targetSdkVersionResolved;

    public int getTargetSdkVersion() {
        if (!targetSdkVersionResolved) {
            targetSdkVersion = getTargetSdkVersionInternal();
            targetSdkVersionResolved = true;
        }
        return targetSdkVersion;
    }

    private int getTargetSdkVersionInternal() {
        try {
            final ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(getPackageName(), 0);
            if (ai != null) {
                return ai.targetSdkVersion;
            }
        } catch (NameNotFoundException ignored) {
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    public static final int PERMISSION_IS_SELF = 1 << 0;
    public static final int PERMISSION_IS_SHELL = 1 << 1;
    public static final int PERMISSION_IS_MANAGER = 1 << 2;
    public static final int PERMISSION_IS_DELEGATOR = 1 << 3;

    public static final int PERMISSION_IS_REDACTION_NEEDED = 1 << 8;
    public static final int PERMISSION_IS_LEGACY_GRANTED = 1 << 9;
    public static final int PERMISSION_IS_LEGACY_READ = 1 << 10;
    public static final int PERMISSION_IS_LEGACY_WRITE = 1 << 11;

    public static final int PERMISSION_READ_AUDIO = 1 << 16;
    public static final int PERMISSION_READ_VIDEO = 1 << 17;
    public static final int PERMISSION_READ_IMAGES = 1 << 18;
    public static final int PERMISSION_WRITE_AUDIO = 1 << 19;
    public static final int PERMISSION_WRITE_VIDEO = 1 << 20;
    public static final int PERMISSION_WRITE_IMAGES = 1 << 21;

    public static final int PERMISSION_IS_SYSTEM_GALLERY = 1 <<22;

    private int hasPermission;
    private int hasPermissionResolved;

    public boolean hasPermission(int permission) {
        if ((hasPermissionResolved & permission) == 0) {
            if (hasPermissionInternal(permission)) {
                hasPermission |= permission;
            }
            hasPermissionResolved |= permission;
        }
        return (hasPermission & permission) != 0;
    }

    private boolean hasPermissionInternal(int permission) {
        // While we're here, enforce any broad user-level restrictions
        if ((uid == Process.SHELL_UID) && context.getSystemService(UserManager.class)
                .hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)) {
            throw new SecurityException(
                    "Shell user cannot access files for user " + UserHandle.myUserId());
        }

        switch (permission) {
            case PERMISSION_IS_SELF:
                return checkPermissionSelf(context, pid, uid);
            case PERMISSION_IS_SHELL:
                return checkPermissionShell(context, pid, uid);
            case PERMISSION_IS_MANAGER:
                return checkPermissionManager(context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_IS_DELEGATOR:
                return checkPermissionDelegator(context, pid, uid);

            case PERMISSION_IS_REDACTION_NEEDED:
                return isRedactionNeededInternal();
            case PERMISSION_IS_LEGACY_GRANTED:
                return isLegacyStorageGranted();
            case PERMISSION_IS_LEGACY_READ:
                return isLegacyReadInternal();
            case PERMISSION_IS_LEGACY_WRITE:
                return isLegacyWriteInternal();

            case PERMISSION_READ_AUDIO:
                return checkPermissionReadAudio(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_READ_VIDEO:
                return checkPermissionReadVideo(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_READ_IMAGES:
                return checkPermissionReadImages(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_WRITE_AUDIO:
                return checkPermissionWriteAudio(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_WRITE_VIDEO:
                return checkPermissionWriteVideo(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_WRITE_IMAGES:
                return checkPermissionWriteImages(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_IS_SYSTEM_GALLERY:
                return checkWriteImagesOrVideoAppOps(
                        context, uid, getPackageName(), attributionTag);
            default:
                return false;
        }
    }

    private boolean isLegacyStorageGranted() {
        boolean defaultScopedStorage = CompatChanges.isChangeEnabled(
                DEFAULT_SCOPED_STORAGE, getPackageName(), UserHandle.getUserHandleForUid(uid));
        boolean forceEnableScopedStorage = CompatChanges.isChangeEnabled(
                FORCE_ENABLE_SCOPED_STORAGE, getPackageName(), UserHandle.getUserHandleForUid(uid));

        // if Scoped Storage is strictly enforced, the app does *not* have legacy storage access
        if (isScopedStorageEnforced(defaultScopedStorage, forceEnableScopedStorage)) {
            return false;
        }
        // if Scoped Storage is strictly disabled, the app has legacy storage access
        if (isScopedStorageDisabled(defaultScopedStorage, forceEnableScopedStorage)) {
            return true;
        }

        return checkIsLegacyStorageGranted(context, uid, getPackageName());
    }

    private boolean isScopedStorageEnforced(boolean defaultScopedStorage,
            boolean forceEnableScopedStorage) {
        return defaultScopedStorage && forceEnableScopedStorage;
    }

    private boolean isScopedStorageDisabled(boolean defaultScopedStorage,
            boolean forceEnableScopedStorage) {
        return !defaultScopedStorage && !forceEnableScopedStorage;
    }

    private boolean isLegacyWriteInternal() {
        return hasPermission(PERMISSION_IS_LEGACY_GRANTED)
                && checkPermissionWriteStorage(context, pid, uid, getPackageName(), attributionTag);
    }

    private boolean isLegacyReadInternal() {
        return hasPermission(PERMISSION_IS_LEGACY_GRANTED)
                && checkPermissionReadStorage(context, pid, uid, getPackageName(), attributionTag);
    }

    /** System internals or callers holding permission have no redaction */
    private boolean isRedactionNeededInternal() {
        if (hasPermission(PERMISSION_IS_SELF) || hasPermission(PERMISSION_IS_SHELL)) {
            return false;
        }

        if (context.checkPermission(ACCESS_MEDIA_LOCATION, pid, uid) == PERMISSION_DENIED
                || context.getSystemService(AppOpsManager.class).noteProxyOpNoThrow(
                permissionToOp(ACCESS_MEDIA_LOCATION), getPackageName(), uid, attributionTag, null)
                != MODE_ALLOWED) {
            return true;
        }

        return false;
    }

    private LongArray ownedIds = new LongArray();

    public boolean isOwned(long id) {
        return ownedIds.indexOf(id) != -1;
    }

    public void setOwned(long id, boolean owned) {
        final int index = ownedIds.indexOf(id);
        if (owned) {
            if (index == -1) {
                ownedIds.add(id);
            }
        } else {
            if (index != -1) {
                ownedIds.remove(index);
            }
        }
    }

    private ArrayMap<String, Long> rowIdOfDeletedPaths = new ArrayMap<>();

    public void addDeletedRowId(@NonNull String path, long id) {
        rowIdOfDeletedPaths.put(path, id);
    }

    public boolean removeDeletedRowId(long id) {
        int index = rowIdOfDeletedPaths.indexOfValue(id);
        final boolean isDeleted = index > -1;
        while (index > -1) {
            rowIdOfDeletedPaths.removeAt(index);
            index = rowIdOfDeletedPaths.indexOfValue(id);
        }
        return isDeleted;
    }

    public long getDeletedRowId(@NonNull String path) {
        return rowIdOfDeletedPaths.getOrDefault(path, UNKNOWN_ROW_ID);
    }
}
