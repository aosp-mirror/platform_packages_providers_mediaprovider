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

import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.Logging.TAG;
import static com.android.providers.media.util.PermissionUtils.checkAppOpRequestInstallPackagesForSharedUid;
import static com.android.providers.media.util.PermissionUtils.checkIsLegacyStorageGranted;
import static com.android.providers.media.util.PermissionUtils.checkPermissionAccessMediaLocation;
import static com.android.providers.media.util.PermissionUtils.checkPermissionAccessMtp;
import static com.android.providers.media.util.PermissionUtils.checkPermissionDelegator;
import static com.android.providers.media.util.PermissionUtils.checkPermissionInstallPackages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionManager;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadAudio;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadImages;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadStorage;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVideo;
import static com.android.providers.media.util.PermissionUtils.checkPermissionReadVisualUserSelected;
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
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
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
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.util.Logging;
import com.android.providers.media.util.LongArray;
import com.android.providers.media.util.UserCache;

import java.io.PrintWriter;
import java.util.Locale;

public class LocalCallingIdentity {

    public final int pid;
    public final int uid;
    private final UserHandle user;
    private final Context context;
    private final String packageNameUnchecked;
    // Info used for logging permission checks
    private final @Nullable String attributionTag;
    private final Object lock = new Object();

    @GuardedBy("lock")
    private int mDeletedFileCountBypassingDatabase = 0;

    private LocalCallingIdentity(Context context, int pid, int uid, UserHandle user,
            String packageNameUnchecked, @Nullable String attributionTag) {
        this.context = context;
        this.pid = pid;
        this.uid = uid;
        this.user = user;
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

    public static LocalCallingIdentity fromBinder(Context context, ContentProvider provider,
            UserCache userCache) {
        String callingPackage = provider.getCallingPackageUnchecked();
        int binderUid = Binder.getCallingUid();
        if (callingPackage == null) {
            if (binderUid == Process.SYSTEM_UID || binderUid == Process.myUid()) {
                // If UID is system assume we are running as ourself and not handling IPC
                // Otherwise, we'd crash when we attempt AppOpsManager#checkPackage
                // in LocalCallingIdentity#getPackageName
                return fromSelf(context);
            }
            // Package will be resolved during getPackageNameInternal()
            callingPackage = null;
        }
        String callingAttributionTag = provider.getCallingAttributionTag();
        if (callingAttributionTag == null) {
            callingAttributionTag = context.getAttributionTag();
        }
        UserHandle user;
        if (binderUid == Process.SHELL_UID || binderUid == Process.ROOT_UID) {
            // For requests coming from the shell (eg `content query`), assume they are
            // for the user we are running as.
            user = Process.myUserHandle();
        } else {
            user = UserHandle.getUserHandleForUid(binderUid);
        }
        // We need to use the cached variant here, because the uncached version may
        // make a binder transaction, which would cause infinite recursion here.
        // Using the cached variant is fine, because we shouldn't be getting any binder
        // requests for this volume before it has been mounted anyway, at which point
        // we must already know about the new user.
        if (!userCache.userSharesMediaWithParentCached(user)) {
            // It's possible that we got a cross-profile intent from a regular work profile; in
            // that case, the request was explicitly targeted at the media database of the owner
            // user; reflect that here.
            user = Process.myUserHandle();
        }
        return new LocalCallingIdentity(context, Binder.getCallingPid(), binderUid,
                user, callingPackage, callingAttributionTag);
    }

    public static LocalCallingIdentity fromExternal(Context context, @Nullable UserCache userCache,
            int uid) {
        final String[] sharedPackageNames = context.getPackageManager().getPackagesForUid(uid);
        if (sharedPackageNames == null || sharedPackageNames.length == 0) {
            throw new IllegalArgumentException("UID " + uid + " has no associated package");
        }
        LocalCallingIdentity ident = fromExternal(context, userCache, uid, sharedPackageNames[0],
                null);
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

    public static LocalCallingIdentity fromExternal(Context context, @Nullable UserCache userCache,
            int uid, String packageName, @Nullable String attributionTag) {
        UserHandle user = UserHandle.getUserHandleForUid(uid);
        if (userCache != null && !userCache.userSharesMediaWithParentCached(user)) {
            // This can happen on some proprietary app clone solutions, where the owner
            // and clone user each have their own MediaProvider instance, but refer to
            // each other for cross-user file access through the use of bind mounts.
            // In this case, assume the access is for the owner user, since that is
            // the only user for which we manage volumes anyway.
            user = Process.myUserHandle();
        }
        return new LocalCallingIdentity(context, -1, uid, user, packageName, attributionTag);
    }

    public static LocalCallingIdentity fromSelf(Context context) {
        return fromSelfAsUser(context, Process.myUserHandle());
    }

    public static LocalCallingIdentity fromSelfAsUser(Context context, UserHandle user) {
        final LocalCallingIdentity ident = new LocalCallingIdentity(
                context,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                user,
                context.getOpPackageName(),
                context.getAttributionTag());

        ident.packageName = ident.packageNameUnchecked;
        ident.packageNameResolved = true;
        // Use ident.attributionTag from context, hence no change
        ident.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        ident.targetSdkVersionResolved = true;
        ident.shouldBypass = false;
        ident.shouldBypassResolved = true;
        ident.hasPermission = ~(PERMISSION_IS_LEGACY_GRANTED | PERMISSION_IS_LEGACY_WRITE
                | PERMISSION_IS_LEGACY_READ | PERMISSION_IS_REDACTION_NEEDED
                | PERMISSION_IS_SHELL | PERMISSION_IS_DELEGATOR);
        ident.hasPermissionResolved = ~0;
        return ident;
    }

    /**
     * Returns mocked {@link LocalCallingIdentity} for testing
     */
    @VisibleForTesting
    public static LocalCallingIdentity forTest(Context context, int uid, int permission) {
        final String[] sharedPackageNames = context.getPackageManager().getPackagesForUid(uid);
        if (sharedPackageNames == null || sharedPackageNames.length == 0) {
            throw new IllegalArgumentException("UID " + uid + " has no associated package");
        }
        LocalCallingIdentity ident = new LocalCallingIdentity(context, -1, uid,
                Process.myUserHandle(), sharedPackageNames[0], null);
        ident.sharedPackageNames = sharedPackageNames;
        ident.sharedPackageNamesResolved = true;
        ident.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        ident.targetSdkVersionResolved = true;
        ident.shouldBypass = false;
        ident.shouldBypassResolved = true;
        ident.hasPermission = permission;
        ident.hasPermissionResolved = ~0;
        return ident;
    }

    private volatile String packageName;
    private volatile boolean packageNameResolved;

    public String getPackageName() {
        if (!packageNameResolved) {
            packageName = getPackageNameInternal();
            packageNameResolved = true;
        }
        return packageName;
    }

    public boolean isValidProviderOrFuseCallingIdentity() {
        return packageNameUnchecked != null;
    }

    private String getPackageNameInternal() {
        // TODO(b/263480773): The packageNameUnchecked can be null when
        //  ContentProvider#getCallingPackageUnchecked returns null and the binder UID is not system
        //  or MediaProvider. In such scenarios, previously an exception was thrown in the
        //  checkPackage() call below. This was fixed for b/261444895 however, we still need to
        //  investigate if we should explicitly throw an exception in such cases.
        if (packageNameUnchecked == null) {
            return context.getPackageManager().getNameForUid(uid);
        }
        // Verify that package name is actually owned by UID
        context.getSystemService(AppOpsManager.class)
                .checkPackage(uid, packageNameUnchecked);
        return packageNameUnchecked;
    }

    private volatile String[] sharedPackageNames;
    private volatile boolean sharedPackageNamesResolved;

    /**
     * Returns an array of package names that share the {@code uid}
     */
    public String[] getSharedPackageNamesArray() {
        if (!sharedPackageNamesResolved) {
            sharedPackageNames = getSharedPackageNamesListInternal();
            sharedPackageNamesResolved = true;
        }
        return sharedPackageNames;
    }

    /**
     * Returns comma separated string of package names that share the {@code uid}
     */
    public String getSharedPackagesAsString() {
        final String[] sharedPackageNames = getSharedPackageNamesArray();
        return bindList((Object[]) sharedPackageNames);
    }

    private String[] getSharedPackageNamesListInternal() {
        final String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
        return (packageNames != null) ? packageNames : new String[0];
    }

    private volatile int targetSdkVersion;
    private volatile boolean targetSdkVersionResolved;

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

    public UserHandle getUser() {
        return user;
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

    public static final int PERMISSION_IS_SYSTEM_GALLERY = 1 << 22;
    /**
     * Explicitly checks **only** for INSTALL_PACKAGES runtime permission.
     */
    public static final int PERMISSION_INSTALL_PACKAGES = 1 << 23;
    public static final int PERMISSION_WRITE_EXTERNAL_STORAGE = 1 << 24;

    /**
     * Checks if REQUEST_INSTALL_PACKAGES app-op is allowed for any package sharing this UID.
     */
    public static final int APPOP_REQUEST_INSTALL_PACKAGES_FOR_SHARED_UID = 1 << 25;
    public static final int PERMISSION_ACCESS_MTP = 1 << 26;

    public static final int PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED = 1 << 27;

    private volatile int hasPermission;
    private volatile int hasPermissionResolved;

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
        boolean targetSdkIsAtLeastT = getTargetSdkVersion() > Build.VERSION_CODES.S_V2;
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
                return checkPermissionShell(uid);
            case PERMISSION_IS_MANAGER:
                return checkPermissionManager(context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_IS_DELEGATOR:
                return checkPermissionDelegator(context, pid, uid);

            case PERMISSION_IS_REDACTION_NEEDED:
                return isRedactionNeededInternal(targetSdkIsAtLeastT);
            case PERMISSION_IS_LEGACY_GRANTED:
                return isLegacyStorageGranted();
            case PERMISSION_IS_LEGACY_READ:
                return isLegacyReadInternal();
            case PERMISSION_IS_LEGACY_WRITE:
                return isLegacyWriteInternal();

            case PERMISSION_WRITE_EXTERNAL_STORAGE:
                return checkPermissionWriteStorage(
                        context, pid, uid, getPackageName(), attributionTag);

            case PERMISSION_READ_AUDIO:
                return checkPermissionReadAudio(
                        context, pid, uid, getPackageName(), attributionTag, targetSdkIsAtLeastT);
            case PERMISSION_READ_VIDEO:
                return checkPermissionReadVideo(
                        context, pid, uid, getPackageName(), attributionTag, targetSdkIsAtLeastT);
            case PERMISSION_READ_IMAGES:
                return checkPermissionReadImages(
                        context, pid, uid, getPackageName(), attributionTag, targetSdkIsAtLeastT);
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
            case PERMISSION_INSTALL_PACKAGES:
                return checkPermissionInstallPackages(
                        context, pid, uid, getPackageName(), attributionTag);
            case APPOP_REQUEST_INSTALL_PACKAGES_FOR_SHARED_UID:
                return checkAppOpRequestInstallPackagesForSharedUid(
                        context, uid, getSharedPackageNamesArray(), attributionTag);
            case PERMISSION_ACCESS_MTP:
                return checkPermissionAccessMtp(
                        context, pid, uid, getPackageName(), attributionTag);
            case PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED:
                return checkPermissionReadVisualUserSelected(context, pid, uid, getPackageName(),
                        attributionTag, targetSdkIsAtLeastT);
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

        return checkIsLegacyStorageGranted(context, uid, getPackageName(), attributionTag);
    }

    private volatile boolean shouldBypass;
    private volatile boolean shouldBypassResolved;

    /**
     * Allow apps holding {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     * permission to request raw external storage access.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    static final long ENABLE_RAW_MANAGE_EXTERNAL_STORAGE_ACCESS = 178209446L;

    /**
     * Allow apps holding {@link android.app.role}#SYSTEM_GALLERY role to request raw external
     * storage access.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.R)
    static final long ENABLE_RAW_SYSTEM_GALLERY_ACCESS = 183372781L;

    /**
     * Checks if app chooses to bypass database operations.
     *
     * <p>
     * Note that this method doesn't check if app qualifies to bypass database operations.
     *
     * @return {@code true} if AndroidManifest.xml of this app has
     * android:requestRawExternalStorageAccess=true
     * {@code false} otherwise.
     */
    public boolean shouldBypassDatabase(boolean isSystemGallery) {
        if (!shouldBypassResolved) {
            shouldBypass = shouldBypassDatabaseInternal(isSystemGallery);
            shouldBypassResolved = true;
        }
        return shouldBypass;
    }

    private boolean shouldBypassDatabaseInternal(boolean isSystemGallery) {
        if (!SdkLevel.isAtLeastS()) {
            // We need to parse the manifest flag ourselves here.
            // TODO(b/178209446): Parse app manifest to get new flag values
            return true;
        }

        final ApplicationInfo ai;
        try {
            ai = context.getPackageManager()
                    .getApplicationInfo(getPackageName(), 0);
            if (ai != null) {
                final int requestRawExternalStorageValue
                        = ai.getRequestRawExternalStorageAccess();
                if (requestRawExternalStorageValue
                        != ApplicationInfo.RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT) {
                    return requestRawExternalStorageValue
                            == ApplicationInfo.RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED;
                }
                // Manifest flag is not set, hence return default value based on the category of the
                // app and targetSDK.
                if (isSystemGallery) {
                    if (CompatChanges.isChangeEnabled(
                            ENABLE_RAW_SYSTEM_GALLERY_ACCESS, uid)) {
                        // If systemGallery, then the flag will default to false when they are
                        // targeting targetSDK>=30.
                        return false;
                    }
                } else if (CompatChanges.isChangeEnabled(
                        ENABLE_RAW_MANAGE_EXTERNAL_STORAGE_ACCESS, uid)) {
                    // If app has MANAGE_EXTERNAL_STORAGE, the flag will default to false when they
                    // are targeting targetSDK>=31.
                    return false;
                }
            }
        } catch (NameNotFoundException e) {
        }
        return true;
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
    private boolean isRedactionNeededInternal(boolean isTargetSdkAtLeastT) {
        if (hasPermission(PERMISSION_IS_SELF) || hasPermission(PERMISSION_IS_SHELL)) {
            return false;
        }

        return !checkPermissionAccessMediaLocation(context, pid, uid, getPackageName(),
                attributionTag, isTargetSdkAtLeastT);
    }

    @GuardedBy("lock")
    private final LongArray ownedIds = new LongArray();

    public boolean isOwned(long id) {
        synchronized (lock) {
            return ownedIds.indexOf(id) != -1;
        }
    }

    public void setOwned(long id, boolean owned) {
        synchronized (lock) {
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

    @GuardedBy("lock")
    private final ArrayMap<String, Long> rowIdOfDeletedPaths = new ArrayMap<>();

    public void addDeletedRowId(@NonNull String path, long id) {
        synchronized (lock) {
            rowIdOfDeletedPaths.put(path.toLowerCase(Locale.ROOT), id);
        }
    }

    public boolean removeDeletedRowId(long id) {
        synchronized (lock) {
            int index = rowIdOfDeletedPaths.indexOfValue(id);
            final boolean isDeleted = index > -1;
            while (index > -1) {
                rowIdOfDeletedPaths.removeAt(index);
                index = rowIdOfDeletedPaths.indexOfValue(id);
            }
            return isDeleted;
        }
    }

    public long getDeletedRowId(@NonNull String path) {
        synchronized (lock) {
            return rowIdOfDeletedPaths.getOrDefault(path.toLowerCase(Locale.ROOT), UNKNOWN_ROW_ID);
        }
    }

    protected void incrementDeletedFileCountBypassingDatabase() {
        synchronized (lock) {
            mDeletedFileCountBypassingDatabase++;
        }
    }

    protected int getDeletedFileCountBypassingDatabase() {
        synchronized (lock) {
            return mDeletedFileCountBypassingDatabase;
        }
    }

    private volatile int applicationMediaCapabilitiesSupportedFlags = -1;
    private volatile int applicationMediaCapabilitiesUnsupportedFlags = -1;

    public int getApplicationMediaCapabilitiesSupportedFlags() {
        return applicationMediaCapabilitiesSupportedFlags;
    }

    public int getApplicationMediaCapabilitiesUnsupportedFlags() {
        return applicationMediaCapabilitiesUnsupportedFlags;
    }

    public void setApplicationMediaCapabilitiesFlags(int supportedFlags, int unsupportedFlags) {
        applicationMediaCapabilitiesSupportedFlags = supportedFlags;
        applicationMediaCapabilitiesUnsupportedFlags = unsupportedFlags;
    }

    /**
     * Returns {@code true} if this package has Audio read/write permissions.
     */
    public boolean checkCallingPermissionAudio(boolean forWrite) {
        if (forWrite) {
            return hasPermission(PERMISSION_WRITE_AUDIO);
        } else {
            // write permission should be enough for reading as well
            return hasPermission(PERMISSION_READ_AUDIO)
                    || hasPermission(PERMISSION_WRITE_AUDIO);
        }
    }

    /**
     * Returns {@code true} if this package has Video read/write permissions.
     */
    public boolean checkCallingPermissionVideo(boolean forWrite) {
        if (forWrite) {
            return hasPermission(PERMISSION_WRITE_VIDEO);
        } else {
            // write permission should be enough for reading as well
            return hasPermission(PERMISSION_READ_VIDEO) || hasPermission(PERMISSION_WRITE_VIDEO);
        }
    }

    /**
     * Returns {@code true} if this package has Image read/write permissions.
     */
    public boolean checkCallingPermissionImages(boolean forWrite) {
        if (forWrite) {
            return hasPermission(PERMISSION_WRITE_IMAGES);
        } else {
            // write permission should be enough for reading as well
            return hasPermission(PERMISSION_READ_IMAGES) || hasPermission(PERMISSION_WRITE_IMAGES);
        }
    }

    /**
     * Returns {@code true} if this package is a legacy app and has read permission
     */
    public boolean isCallingPackageLegacyRead() {
        return hasPermission(PERMISSION_IS_LEGACY_READ);
    }

    /**
     * Returns {@code true} if this package is a legacy app and has write permission
     */
    public boolean isCallingPackageLegacyWrite() {
        return hasPermission(PERMISSION_IS_LEGACY_WRITE);
    }

    /**
     * Return {@code true} if this package has user selected access on images/videos.
     */
    public boolean checkCallingPermissionUserSelected() {
        // For user select mode READ_MEDIA_VISUAL_USER_SELECTED == true &&
        // READ_MEDIA_IMAGES == false && READ_MEDIA_VIDEO == false
        return hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
                && !hasPermission(PERMISSION_READ_IMAGES) && !hasPermission(PERMISSION_READ_VIDEO);
    }

    protected void dump(PrintWriter writer) {
        if (getDeletedFileCountBypassingDatabase() <= 0) {
            return;
        }

        writer.println(getDeletedFileCountLogMessage(uid, getPackageName(),
                getDeletedFileCountBypassingDatabase()));
    }

    protected void dump(String reason) {
        Log.i(TAG, "Invalidating LocalCallingIdentity cache for package " + packageName
                + ". Reason: " + reason);
        if (this.getDeletedFileCountBypassingDatabase() > 0) {
            Logging.logPersistent(getDeletedFileCountLogMessage(uid, getPackageName(),
                    getDeletedFileCountBypassingDatabase()));
        }
    }

    private static String getDeletedFileCountLogMessage(int uid, String packageName,
            int deletedFilesCountBypassingDatabase) {
        return "uid=" + uid + " packageName=" + packageName + " deletedFilesCountBypassingDatabase="
                + deletedFilesCountBypassingDatabase;
    }
}
