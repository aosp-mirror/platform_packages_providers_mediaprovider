/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.SQLException;
import android.media.MediaScanner;
import android.mtp.PtpClient;
import android.mtp.PtpCursor;
import android.net.Uri;
import android.os.FileUtils;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Ptp;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Provides access to content on PTP devices via USB.
 * At the top level we have a list of PTP devices,
 * and then a list of storage units for each device.
 * Finally a list of objects (typically files and folders)
 * and their properties can be for each storage unit.
 */
public class PtpProvider extends ContentProvider implements PtpClient.Listener {

    private static final String TAG = "PtpProvider";

    private PtpClient mClient;
    private ContentResolver mResolver;
    private String mMediaStoragePath;
    private MediaScanner mMediaScanner;

    private static final UriMatcher sUriMatcher;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");

        Context context = getContext();
        // fail if we have no USB host support
        if (!context.getResources().getBoolean(
                com.android.internal.R.bool.config_hasUsbHostSupport)) {
            Log.d(TAG, "no USB host support");
            return false;
        }

        mMediaStoragePath = SystemProperties.get("ro.media.storage");
        mMediaScanner = new MediaScanner(context);
        mResolver = context.getContentResolver();
        mClient = new PtpClient(this);
        Log.d(TAG, "mClient: " + mClient);
        mClient.start();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        // keep this locked up until we are ready to support it as a public API
        getContext().enforceCallingPermission(android.Manifest.permission.ACCESS_USB, null);

        Log.d(TAG, "query projection: " + projection);

        if (mClient == null) {
            return null;
        }

        if (projection == null) {
            throw new UnsupportedOperationException("PtpProvider queries require a projection");
        }
        if (selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException("PtpProvider queries do not support selection");
        }
        if (sortOrder != null) {
            throw new UnsupportedOperationException("PtpProvider queries do not support sortOrder");
        }

        int deviceID = 0;
        long storageID = 0;
        long objectID = 0;
        int queryType = sUriMatcher.match(uri);
        try {
            switch (queryType) {
                case PtpCursor.DEVICE:
                    break;

                case PtpCursor.DEVICE_ID:
                case PtpCursor.STORAGE:
                case PtpCursor.OBJECT:
                    deviceID = Integer.parseInt(uri.getPathSegments().get(1));
                    break;

                case PtpCursor.STORAGE_ID:
                case PtpCursor.STORAGE_CHILDREN:
                    deviceID = Integer.parseInt(uri.getPathSegments().get(1));
                    storageID = Long.parseLong(uri.getPathSegments().get(3));
                    break;

                case PtpCursor.OBJECT_CHILDREN:
                case PtpCursor.OBJECT_ID:
                    deviceID = Integer.parseInt(uri.getPathSegments().get(1));
                    objectID = Long.parseLong(uri.getPathSegments().get(3));
                    storageID = -1;
                    break;

                default:
                    throw new SQLException("Unknown URL: " + uri.toString());
            }
        } catch (Exception e) {
            throw new SQLException("Unknown URL: " + uri.toString());
        }

        PtpCursor cursor = new PtpCursor(mClient, queryType, deviceID,
                storageID, objectID, projection);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        Log.d(TAG, "getType");
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // keep this locked up until we are ready to support it as a public API
        getContext().enforceCallingPermission(android.Manifest.permission.ACCESS_USB, null);

        if (sUriMatcher.match(uri) == PtpCursor.OBJECT_IMPORT) {
            Log.d(TAG, "import uri " + uri);
            int deviceID = Integer.parseInt(uri.getPathSegments().get(1));
            int objectID = Integer.parseInt(uri.getPathSegments().get(3));
            String destPath = uri.getQuery();
            // use internal media path to avoid fuse overhead
            destPath = MediaProvider.externalToMediaPath(destPath);

            if (!destPath.startsWith(mMediaStoragePath)) {
                throw new IllegalArgumentException(
                        "Destination path not in media storage directory");
            }
            if (mClient == null) {
                throw new IllegalStateException("PTP host support not initialized");
            }

            // make sure the containing directories exist and have correct permissions
            File file = new File(destPath);
            File parent = file.getParentFile();
            parent.mkdirs();
            while (parent != null && !mMediaStoragePath.equals(parent.getAbsolutePath())) {
                Log.d(TAG, "parent: " + parent);
                FileUtils.setPermissions(parent.getAbsolutePath(), 0775, Process.myUid(),
                        Process.MEDIA_RW_GID);
                parent = parent.getParentFile();
            }
            
            if (mClient.importFile(deviceID, objectID, destPath)) {
                Log.d(TAG, "import succeeded");
                return mMediaScanner.scanSingleFile(destPath, MediaProvider.EXTERNAL_VOLUME, null);
            }
            
            return null;
        }

        throw new UnsupportedOperationException("PtpProvider does not support inserting");
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        // keep this locked up until we are ready to support it as a public API
        getContext().enforceCallingPermission(android.Manifest.permission.ACCESS_USB, null);

        int deviceID;
        long objectID;

        if (mClient == null) {
            throw new IllegalStateException("PTP host support not initialized");
        }

       if (where != null || whereArgs != null) {
            throw new UnsupportedOperationException(
                    "PtpProvider does not support \"where\" for delete");
        }

        try {
            switch (sUriMatcher.match(uri)) {
                case PtpCursor.DEVICE:
                case PtpCursor.DEVICE_ID:
                    throw new UnsupportedOperationException("can not delete devices");

                case PtpCursor.STORAGE:
                case PtpCursor.STORAGE_ID:
                    throw new UnsupportedOperationException("can not delete storage units");
                case PtpCursor.OBJECT:
                case PtpCursor.STORAGE_CHILDREN:
                case PtpCursor.OBJECT_CHILDREN:
                    deviceID = Integer.parseInt(uri.getPathSegments().get(1));
                    throw new UnsupportedOperationException("can not delete multiple objects");

                case PtpCursor.OBJECT_ID:
                    deviceID = Integer.parseInt(uri.getPathSegments().get(1));
                    objectID = Long.parseLong(uri.getPathSegments().get(3));
                    break;
                default:
                    throw new SQLException("Unknown URL: " + uri.toString());
            }
        } catch (Exception e) {
            throw new SQLException("Unknown URL: " + uri.toString());
        }

        long parentID = mClient.getParent(deviceID, objectID);
        long storageID = 0;
        if (parentID <= 0) {
            storageID = mClient.getStorageID(deviceID, objectID);
        }
        boolean success = mClient.deleteObject(deviceID, objectID);
        Log.d(TAG, "delete object " + objectID + " on device " + deviceID +
                (success ? " succeeded" : " failed"));
        if (success) {
            Log.d(TAG, "notifyChange " + uri);
            mResolver.notifyChange(uri, null);

            // notify on the parent's child URI too.
            // This is needed because the parent URI is not a subset of the child URI
            if (parentID > 0) {
                uri = Ptp.Object.getContentUriForObjectChildren(deviceID, parentID);
            } else {
                uri = Ptp.Object.getContentUriForStorageChildren(deviceID, storageID);
            }
            mResolver.notifyChange(uri, null);
        }
        return (success ? 1 : 0);
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        throw new UnsupportedOperationException("PtpProvider does not support updating");
    }

    private void notifyDeviceChanged(int deviceID) {
        Uri uri = Ptp.Device.getContentUri(deviceID);
        mResolver.notifyChange(uri, null);
    }

    // PtpClient.Listener methods
    public void deviceAdded(int deviceID) {
        Log.d(TAG, "deviceAdded " + deviceID);
        notifyDeviceChanged(deviceID);
     }

    public void deviceRemoved(int deviceID) {
        Log.d(TAG, "deviceRemoved " + deviceID);
        notifyDeviceChanged(deviceID);
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device", PtpCursor.DEVICE);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#", PtpCursor.DEVICE_ID);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/storage", PtpCursor.STORAGE);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/storage/#", PtpCursor.STORAGE_ID);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/object", PtpCursor.OBJECT);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/object/#", PtpCursor.OBJECT_ID);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/storage/#/child", PtpCursor.STORAGE_CHILDREN);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/object/#/child", PtpCursor.OBJECT_CHILDREN);
        sUriMatcher.addURI(Ptp.AUTHORITY, "device/#/import/#", PtpCursor.OBJECT_IMPORT);
    }
}
