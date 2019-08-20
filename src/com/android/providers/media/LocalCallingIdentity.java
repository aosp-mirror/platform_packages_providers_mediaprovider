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
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_MEDIA_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_LEGACY_STORAGE;
import static android.app.AppOpsManager.OP_WRITE_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.storage.StorageManager;

import com.android.providers.media.util.LongArray;

public class LocalCallingIdentity {
    public final Context context;
    public final int pid;
    public final int uid;
    public final String packageNameUnchecked;

    private LocalCallingIdentity(Context context, int pid, int uid, String packageNameUnchecked) {
        this.context = context;
        this.pid = pid;
        this.uid = uid;
        this.packageNameUnchecked = packageNameUnchecked;
    }

    public static LocalCallingIdentity fromBinder(Context context, ContentProvider provider) {
        String callingPackage = provider.getCallingPackageUnchecked();
        if (callingPackage == null) {
            callingPackage = context.getOpPackageName();
        }
        return new LocalCallingIdentity(context, Binder.getCallingPid(), Binder.getCallingUid(),
                callingPackage);
    }

    public static LocalCallingIdentity fromExternal(Context context, int uid) {
        final String[] sharedPackageNames = context.getPackageManager().getPackagesForUid(uid);
        if (sharedPackageNames == null || sharedPackageNames.length == 0) {
            throw new IllegalArgumentException("UID " + uid + " has no associated package");
        }
        return fromExternal(context, uid, sharedPackageNames[0]);
    }

    public static LocalCallingIdentity fromExternal(Context context, int uid, String packageName) {
        return new LocalCallingIdentity(context, -1, uid, packageName);
    }

    public static LocalCallingIdentity fromSelf(Context context) {
        final LocalCallingIdentity ident = new LocalCallingIdentity(
                context,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                context.getOpPackageName());

        ident.packageName = ident.packageNameUnchecked;
        ident.packageNameResolved = true;
        ident.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        ident.targetSdkVersionResolved = true;
        ident.hasPermission = ~(PERMISSION_IS_LEGACY | PERMISSION_IS_REDACTION_NEEDED);
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

    public static final int PERMISSION_IS_SYSTEM = 1 << 0;
    public static final int PERMISSION_IS_LEGACY = 1 << 1;
    public static final int PERMISSION_IS_REDACTION_NEEDED = 1 << 2;
    public static final int PERMISSION_READ_AUDIO = 1 << 3;
    public static final int PERMISSION_READ_VIDEO = 1 << 4;
    public static final int PERMISSION_READ_IMAGES = 1 << 5;
    public static final int PERMISSION_WRITE_AUDIO = 1 << 6;
    public static final int PERMISSION_WRITE_VIDEO = 1 << 7;
    public static final int PERMISSION_WRITE_IMAGES = 1 << 8;

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
        switch (permission) {
            case PERMISSION_IS_SYSTEM:
                return isSystemInternal();
            case PERMISSION_IS_LEGACY:
                return isLegacyInternal();
            case PERMISSION_IS_REDACTION_NEEDED:
                return isRedactionNeededInternal();
            case PERMISSION_READ_AUDIO:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionReadAudio(false, pid, uid, getPackageName());
            case PERMISSION_READ_VIDEO:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionReadVideo(false, pid, uid, getPackageName());
            case PERMISSION_READ_IMAGES:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionReadImages(false, pid, uid, getPackageName());
            case PERMISSION_WRITE_AUDIO:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionWriteAudio(false, pid, uid, getPackageName());
            case PERMISSION_WRITE_VIDEO:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionWriteVideo(false, pid, uid, getPackageName());
            case PERMISSION_WRITE_IMAGES:
                return context.getSystemService(StorageManager.class)
                        .checkPermissionWriteImages(false, pid, uid, getPackageName());
            default:
                return false;
        }
    }

    private boolean isSystemInternal() {
        if (uid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        // Special case to speed up when MediaProvider is calling itself; we
        // know it always has system permissions
        if (uid == android.os.Process.myUid()) {
            return true;
        }

        // Determine if caller is holding runtime permission
        final boolean hasStorage = StorageManager.checkPermissionAndAppOp(context, false, 0,
                uid, getPackageName(), WRITE_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE);

        // We're only willing to give out broad access if they also hold
        // runtime permission; this is a firm CDD requirement
        final boolean hasFull = context
                .checkPermission(WRITE_MEDIA_STORAGE, pid, uid) == PERMISSION_GRANTED;

        return hasFull && hasStorage;
    }

    private boolean isLegacyInternal() {
        // TODO: keep this logic in sync with StorageManagerService
        final boolean hasStorage = StorageManager.checkPermissionAndAppOp(context, false, 0,
                uid, getPackageName(), WRITE_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE);
        final boolean hasLegacy = context.getSystemService(AppOpsManager.class)
                .checkOp(OP_LEGACY_STORAGE, uid, getPackageName()) == MODE_ALLOWED;
        return (hasLegacy && hasStorage);
    }

    private boolean isRedactionNeededInternal() {
        // System internals or callers holding permission have no redaction
        if (hasPermission(PERMISSION_IS_SYSTEM)
                || context.checkPermission(ACCESS_MEDIA_LOCATION, pid, uid) == PERMISSION_GRANTED) {
            return false;
        }
        return true;
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
}
