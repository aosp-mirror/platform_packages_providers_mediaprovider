/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;

public final class MaliciousAppDetector {

    private static final String TAG = "MaliciousAppDetector";
    private static final String MALICIOUS_APP_UID_LIST = "malicious_app_uid_list";
    // default file creation threshold limit set to 1 million
    private static final int FILE_CREATION_THRESHOLD_LIMIT = 1_000_000;
    // default checking malicious behaviour on every 1000th insertion
    private static final int FREQUENCY_OF_MALICIOUS_INSERTION_CHECK = 1000;

    public static final String MALICIOUS_APP_DETECTOR_PREFS = "malicious_app_detector_prefs";

    private Set<String> mMaliciousAppUidSet = new HashSet<>();
    private final int mFileCreationThresholdLimit;
    private final int mFrequencyOfMaliciousInsertionCheck;
    private final SharedPreferences mMaliciousAppDetectorPrefs;

    public MaliciousAppDetector(Context context) {
        this(context, FILE_CREATION_THRESHOLD_LIMIT, FREQUENCY_OF_MALICIOUS_INSERTION_CHECK);
    }

    public MaliciousAppDetector(Context context, int fileCreationThresholdLimit,
            int frequencyOfMaliciousInsertionCheck) {
        mMaliciousAppDetectorPrefs = context.getSharedPreferences(MALICIOUS_APP_DETECTOR_PREFS,
                Context.MODE_PRIVATE);
        mMaliciousAppUidSet = mMaliciousAppDetectorPrefs.getStringSet(MALICIOUS_APP_UID_LIST,
                new HashSet<>());
        mFileCreationThresholdLimit = fileCreationThresholdLimit;
        mFrequencyOfMaliciousInsertionCheck = frequencyOfMaliciousInsertionCheck;
    }

    /**
     * Monitors file creation activity and flags potentially malicious applications
     * The method retrieves the application's UID and queries the media provider database to count
     * the number of files associated with the application. If the file count exceeds the limit,
     * the application's UID is added to the shared preferences
     *
     * @param context the context used to access shared preferences and package manager
     * @param helper database helper used to interact with the media provider database
     * @param packageName package name of the application to monitor
     */
    public void detectFileCreationByMaliciousApp(Context context, DatabaseHelper helper,
            String packageName) {
        try {
            int uid = context.getPackageManager().getPackageUid(packageName, 0);
            helper.runWithoutTransaction((db) -> {
                int filesCount = getFilesCountForApp(db, packageName);
                if (isInsertionLimitExceeded(filesCount)) {
                    addUidToSharedPreference(uid);
                }
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG,
                    "Error while detecting malicious file creation for " + packageName, e);
        }
    }

    /**
     * Checks if an app with the given UID is allowed to create files
     *
     * @param uid the uid of the application to check
     * @return {@code true} if the application is allowed to create files, {@code false} otherwise
     */
    public boolean isAppAllowedToCreateFiles(long uid) {
        String uidString = String.valueOf(uid);
        return !mMaliciousAppUidSet.contains(uidString);
    }

    /**
     * Retrieves the number of files owned by a specific application in the media provider database
     *
     * @param db media provider database
     * @param packageName application package name
     * @return total number of files owned by the application
     */
    private int getFilesCountForApp(SQLiteDatabase db, String packageName) {
        String selection = MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ? AND "
                + MediaStore.MediaColumns.VOLUME_NAME + " = ?";
        String[] selectionArgs = new String[]{packageName, MediaStore.VOLUME_EXTERNAL_PRIMARY};

        try (Cursor cursor = db.query("files",
                new String[]{MediaStore.Files.FileColumns._ID},
                selection,
                selectionArgs,
                null, null, null, null)) {
            return cursor.getCount();
        }
    }

    /**
     * Checks if the number of files created by an application exceeds the defined threshold
     *
     * @param filesCount number of files to check against
     * @return {@code true} if the file count exceeds the threshold limit, {@code false} otherwise
     */
    private boolean isInsertionLimitExceeded(int filesCount) {
        return mFileCreationThresholdLimit <= filesCount;
    }

    /**
     * Adds the UID of an application to the shared preferences
     *
     * @param uid the uid of the application to add to the set
     */
    private void addUidToSharedPreference(int uid) {
        SharedPreferences.Editor editor = mMaliciousAppDetectorPrefs.edit();
        Set<String> uidSet = mMaliciousAppDetectorPrefs.getStringSet(MALICIOUS_APP_UID_LIST,
                new HashSet<>());
        if (uidSet == null) {
            mMaliciousAppUidSet = new HashSet<>();
        } else {
            mMaliciousAppUidSet = new HashSet<>(uidSet);
        }
        mMaliciousAppUidSet.add(String.valueOf(uid));
        editor.putStringSet(MALICIOUS_APP_UID_LIST, mMaliciousAppUidSet);
        editor.apply();
    }

    /**
     * Clears the shared preferences containing the set of malicious app UIDs
     */
    @VisibleForTesting
    public void clearSharedPref() {
        SharedPreferences.Editor editor = mMaliciousAppDetectorPrefs.edit();
        mMaliciousAppUidSet = new HashSet<>();
        editor.putStringSet(MALICIOUS_APP_UID_LIST, new HashSet<>());
        editor.apply();
    }

    public int getFrequencyOfMaliciousInsertionCheck() {
        return mFrequencyOfMaliciousInsertionCheck;
    }

    public int getFileCreationThresholdLimit() {
        return mFileCreationThresholdLimit;
    }
}
