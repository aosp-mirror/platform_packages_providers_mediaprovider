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

package com.android.providers.media.backupandrestore;

import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.BACKUP_COLUMNS;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
import com.android.providers.media.leveldb.LevelDBResult;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(com.android.providers.media.flags.Flags.FLAG_ENABLE_BACKUP_AND_RESTORE)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public final class BackupExecutorTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Map used to store key id for given column and vice versa.
     */
    private static Map<String, String> sColumnIdToKeyMap;

    private Set<File> mStagedFiles = new HashSet<>();

    private Context mIsolatedContext;

    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    private File mDownloadsDir;

    @BeforeClass
    public static void setupBeforeClass() {
        createColumnToKeyMap();
    }

    private String mLevelDbPath;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.DUMP,
                        Manifest.permission.READ_DEVICE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);

        mIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = mIsolatedContext.getContentResolver();
        mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        mLevelDbPath =
                mIsolatedContext.getFilesDir().getAbsolutePath() + "/backup/external_primary/";
        FileUtils.deleteContents(mDownloadsDir);
    }

    @After
    public void tearDown() {
        // Delete leveldb directory after test
        File levelDbDir = new File(mLevelDbPath);
        for (File f : levelDbDir.listFiles()) {
            f.delete();
        }
        levelDbDir.delete();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testBackup() throws Exception {
        try {
            // Add all files in Downloads directory
            File file = new File(mDownloadsDir, "a_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
            stageNewFile(R.raw.test_image, file);
            file = new File(mDownloadsDir, "b_" + SystemClock.elapsedRealtimeNanos() + ".gif");
            stageNewFile(R.raw.test_gif, file);
            file = new File(mDownloadsDir, "c_" + SystemClock.elapsedRealtimeNanos() + ".mp3");
            stageNewFile(R.raw.test_audio, file);
            file = new File(mDownloadsDir, "d_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
            stageNewFile(R.raw.test_motion_photo, file);
            file = new File(mDownloadsDir, "e_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
            stageNewFile(R.raw.test_video, file);
            file = new File(mDownloadsDir, "f_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
            stageNewFile(R.raw.test_video_gps, file);
            file = new File(mDownloadsDir, "g_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
            stageNewFile(R.raw.test_video_xmp, file);
            file = new File(mDownloadsDir, "h_" + SystemClock.elapsedRealtimeNanos() + ".mp3");
            stageNewFile(R.raw.test_audio, file);
            file = new File(mDownloadsDir, "i_" + SystemClock.elapsedRealtimeNanos() + ".mp3");
            stageNewFile(R.raw.test_audio_empty_title, file);
            file = new File(mDownloadsDir, "j_" + SystemClock.elapsedRealtimeNanos() + ".xspf");
            stageNewFile(R.raw.test_xspf, file);
            file = new File(mDownloadsDir, "k_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
            stageNewFile(R.raw.large_xmp, file);

            mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);
            // Run idle maintenance to backup data
            MediaStore.runIdleMaintenance(mIsolatedResolver);

            // Stage another file to test incremental backup
            file = new File(mDownloadsDir, "l_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
            stageNewFile(R.raw.large_xmp, file);
            // Run idle maintenance again for incremental backup
            MediaStore.runIdleMaintenance(mIsolatedResolver);

            Bundle bundle = new Bundle();
            bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "_data LIKE ? AND is_pending=0 AND _modifier=3 AND volume_name=? AND "
                            + "mime_type IS NOT NULL");
            bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[]{mDownloadsDir.getAbsolutePath() + "/%",
                            MediaStore.VOLUME_EXTERNAL_PRIMARY});
            List<String> columns = new ArrayList<>(Arrays.asList(BACKUP_COLUMNS));
            columns.add(MediaStore.Files.FileColumns.DATA);
            String[] projection = columns.toArray(new String[0]);
            Set<File> scannedFiles = new HashSet<>();
            Map<String, Map<String, String>> pathToAttributesMap = new HashMap<>();
            try (Cursor c = mIsolatedResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection,
                    bundle, null)) {
                assertThat(c).isNotNull();
                while (c.moveToNext()) {
                    Map<String, String> attributesMap = new HashMap<>();
                    for (String col : BACKUP_COLUMNS) {
                        assertWithMessage("Column is missing: " + col).that(
                                c.getColumnIndex(col)).isNotEqualTo(-1);
                        Optional<String> value = BackupExecutor.extractValue(c, col);
                        value.ifPresent(s -> attributesMap.put(col, s));
                    }
                    String path = c.getString(
                            c.getColumnIndex(MediaStore.Files.FileColumns.DATA));
                    scannedFiles.add(new File(path));
                    pathToAttributesMap.put(path, attributesMap);
                }
            }

            assertThat(scannedFiles).containsAtLeastElementsIn(mStagedFiles);
            assertWithMessage("Database does not have entries for staged files").that(
                    pathToAttributesMap).isNotEmpty();
            LevelDBInstance levelDBInstance = LevelDBManager.getInstance(mLevelDbPath);
            for (String path : pathToAttributesMap.keySet()) {
                LevelDBResult levelDBResult = levelDBInstance.query(path);
                // Assert leveldb has entry for file path
                assertThat(levelDBResult.isSuccess()).isTrue();
                Map<String, String> actualResultMap = deSerialiseValueString(
                        levelDBResult.getValue());
                assertThat(actualResultMap.keySet()).isNotEmpty();
                assertThat(actualResultMap).isEqualTo(pathToAttributesMap.get(path));
            }
        } finally {
            FileUtils.deleteContents(mDownloadsDir);
            mStagedFiles.clear();
        }
    }

    static Map<String, String> deSerialiseValueString(String valueString) {
        String[] values = valueString.split(":::");
        Map<String, String> map = new HashMap<>();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }

            String[] keyValue = value.split("=", 2);
            map.put(sColumnIdToKeyMap.get(keyValue[0]), keyValue[1]);
        }

        return map;
    }

    private void stageNewFile(int resId, File file) throws IOException {
        file.createNewFile();
        mStagedFiles.add(file);
        stage(resId, file);
    }

    static void createColumnToKeyMap() {
        sColumnIdToKeyMap = new HashMap<>();
        sColumnIdToKeyMap.put("0", MediaStore.Files.FileColumns.IS_FAVORITE);
        sColumnIdToKeyMap.put("1", MediaStore.Files.FileColumns.MEDIA_TYPE);
        sColumnIdToKeyMap.put("2", MediaStore.Files.FileColumns.MIME_TYPE);
        sColumnIdToKeyMap.put("3", MediaStore.Files.FileColumns._USER_ID);
        sColumnIdToKeyMap.put("4", MediaStore.Files.FileColumns.SIZE);
        sColumnIdToKeyMap.put("5", MediaStore.MediaColumns.DATE_TAKEN);
        sColumnIdToKeyMap.put("6", MediaStore.MediaColumns.CD_TRACK_NUMBER);
        sColumnIdToKeyMap.put("7", MediaStore.MediaColumns.ALBUM);
        sColumnIdToKeyMap.put("8", MediaStore.MediaColumns.ARTIST);
        sColumnIdToKeyMap.put("9", MediaStore.MediaColumns.AUTHOR);
        sColumnIdToKeyMap.put("10", MediaStore.MediaColumns.COMPOSER);
        sColumnIdToKeyMap.put("11", MediaStore.MediaColumns.GENRE);
        sColumnIdToKeyMap.put("12", MediaStore.MediaColumns.TITLE);
        sColumnIdToKeyMap.put("13", MediaStore.MediaColumns.YEAR);
        sColumnIdToKeyMap.put("14", MediaStore.MediaColumns.DURATION);
        sColumnIdToKeyMap.put("15", MediaStore.MediaColumns.NUM_TRACKS);
        sColumnIdToKeyMap.put("16", MediaStore.MediaColumns.WRITER);
        sColumnIdToKeyMap.put("17", MediaStore.MediaColumns.ALBUM_ARTIST);
        sColumnIdToKeyMap.put("18", MediaStore.MediaColumns.DISC_NUMBER);
        sColumnIdToKeyMap.put("19", MediaStore.MediaColumns.COMPILATION);
        sColumnIdToKeyMap.put("20", MediaStore.MediaColumns.BITRATE);
        sColumnIdToKeyMap.put("21", MediaStore.MediaColumns.CAPTURE_FRAMERATE);
        sColumnIdToKeyMap.put("22", MediaStore.Audio.AudioColumns.TRACK);
        sColumnIdToKeyMap.put("23", MediaStore.MediaColumns.DOCUMENT_ID);
        sColumnIdToKeyMap.put("24", MediaStore.MediaColumns.INSTANCE_ID);
        sColumnIdToKeyMap.put("25", MediaStore.MediaColumns.ORIGINAL_DOCUMENT_ID);
        sColumnIdToKeyMap.put("26", MediaStore.MediaColumns.RESOLUTION);
        sColumnIdToKeyMap.put("27", MediaStore.MediaColumns.ORIENTATION);
        sColumnIdToKeyMap.put("28", MediaStore.Video.VideoColumns.COLOR_STANDARD);
        sColumnIdToKeyMap.put("29", MediaStore.Video.VideoColumns.COLOR_TRANSFER);
        sColumnIdToKeyMap.put("30", MediaStore.Video.VideoColumns.COLOR_RANGE);
        sColumnIdToKeyMap.put("31", MediaStore.Files.FileColumns._VIDEO_CODEC_TYPE);
        sColumnIdToKeyMap.put("32", MediaStore.MediaColumns.WIDTH);
        sColumnIdToKeyMap.put("33", MediaStore.MediaColumns.HEIGHT);
        sColumnIdToKeyMap.put("34", MediaStore.Images.ImageColumns.DESCRIPTION);
        sColumnIdToKeyMap.put("35", MediaStore.Images.ImageColumns.EXPOSURE_TIME);
        sColumnIdToKeyMap.put("36", MediaStore.Images.ImageColumns.F_NUMBER);
        sColumnIdToKeyMap.put("37", MediaStore.Images.ImageColumns.ISO);
        sColumnIdToKeyMap.put("38", MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE);
        sColumnIdToKeyMap.put("39", MediaStore.Files.FileColumns._SPECIAL_FORMAT);
        sColumnIdToKeyMap.put("40", MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME);
        // Adding number gap to allow addition of new values
        sColumnIdToKeyMap.put("80", MediaStore.MediaColumns.XMP);
    }
}
