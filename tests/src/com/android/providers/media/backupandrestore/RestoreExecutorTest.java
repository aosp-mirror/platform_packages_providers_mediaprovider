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

import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.FIELD_SEPARATOR;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.KEY_VALUE_SEPARATOR;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.RESTORE_COMPLETED;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
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
import com.android.providers.media.leveldb.LevelDBEntry;
import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(com.android.providers.media.flags.Flags.FLAG_ENABLE_BACKUP_AND_RESTORE)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public final class RestoreExecutorTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Map used to store key id for given column and vice versa.
     */
    private static Map<String, String> sColumnNameToIdMap;

    private Context mIsolatedContext;

    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    private File mDownloadsDir;

    @BeforeClass
    public static void setupBeforeClass() {
        createColumnToKeyMap();
    }

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

        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        FileUtils.deleteContents(mDownloadsDir);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testMetadataRestoreForImageFile() throws Exception {
        String levelDbPath =
                mIsolatedContext.getFilesDir().getAbsolutePath() + "/restore/external_primary/";
        if (!new File(levelDbPath).exists()) {
            new File(levelDbPath).mkdirs();
        }
        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(levelDbPath);
        // Stage image file
        File testImageFile = new File(mDownloadsDir,
                "a_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
        stageNewFile(R.raw.test_image, testImageFile);
        seedImageDataIntoLevelDb(testImageFile, levelDBInstance);
        // Update shared preference to allow restore
        SharedPreferences sharedPreferences = mIsolatedContext.getSharedPreferences(
                BackupAndRestoreUtils.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putBoolean(RESTORE_COMPLETED, true).apply();

        try {
            mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
            mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);
            assertRestoreForImageFile(testImageFile);
        } finally {
            LevelDBManager.delete(levelDbPath);
        }
    }

    @Test
    public void testMetadataRestoreForVideoFile() throws Exception {
        String levelDbPath =
                mIsolatedContext.getFilesDir().getAbsolutePath() + "/restore/external_primary/";
        if (!new File(levelDbPath).exists()) {
            new File(levelDbPath).mkdirs();
        }
        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(levelDbPath);
        // Stage video file
        File testVideoFile = new File(mDownloadsDir,
                "b_" + SystemClock.elapsedRealtimeNanos() + ".mp4");
        stageNewFile(R.raw.test_video_gps, testVideoFile);
        seedVideoDataIntoLevelDb(testVideoFile, levelDBInstance);
        // Update shared preference to allow restore
        SharedPreferences sharedPreferences = mIsolatedContext.getSharedPreferences(
                BackupAndRestoreUtils.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putBoolean(RESTORE_COMPLETED, true).apply();

        try {
            mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
            mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);
            assertRestoreForVideoFile(testVideoFile);
        } finally {
            LevelDBManager.delete(levelDbPath);
        }
    }

    @Test
    public void testMetadataRestoreForAudioFile() throws Exception {
        String levelDbPath =
                mIsolatedContext.getFilesDir().getAbsolutePath() + "/restore/external_primary/";
        if (!new File(levelDbPath).exists()) {
            new File(levelDbPath).mkdirs();
        }
        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(levelDbPath);
        // Stage audio file
        File testAudioFile = new File(mDownloadsDir,
                "c_" + SystemClock.elapsedRealtimeNanos() + ".mp3");
        stageNewFile(R.raw.test_audio, testAudioFile);
        seedAudioDataIntoLevelDb(testAudioFile, levelDBInstance);
        // Update shared preference to allow restore
        SharedPreferences sharedPreferences = mIsolatedContext.getSharedPreferences(
                BackupAndRestoreUtils.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putBoolean(RESTORE_COMPLETED, true).apply();

        try {
            mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
            mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);
            assertRestoreForAudioFile(testAudioFile);
        } finally {
            LevelDBManager.delete(levelDbPath);
        }
    }

    private void assertRestoreForAudioFile(File testAudioFile) {
        Bundle bundle = new Bundle();
        bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "_data LIKE ?");
        bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{testAudioFile.getPath()});
        try (Cursor c = mIsolatedResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.TITLE,
                        MediaStore.Audio.AudioColumns.TRACK,
                        MediaStore.Files.FileColumns.DURATION,
                        MediaStore.Files.FileColumns.ALBUM,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME},
                bundle, null)) {
            assertThat(c).isNotNull();
            assertThat(c.getCount()).isEqualTo(1);
            c.moveToNext();
            assertThat(c.getString(0)).isEqualTo(testAudioFile.getPath());
            assertThat(c.getString(1)).isEqualTo("MyAudio");
            assertThat(c.getString(2)).isEqualTo("Forever");
            assertThat(c.getInt(3)).isEqualTo(120);
            assertThat(c.getString(4)).isEqualTo("ColdPlay");
            assertThat(c.getInt(5)).isEqualTo(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO);
            assertThat(c.getString(6)).isEqualTo("com.hello.audio");
        }
    }

    private void assertRestoreForVideoFile(File testVideoFile) {
        Bundle bundle = new Bundle();
        bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "_data LIKE ?");
        bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{testVideoFile.getPath()});
        try (Cursor c = mIsolatedResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.TITLE,
                        MediaStore.Video.VideoColumns.COLOR_STANDARD,
                        MediaStore.Video.VideoColumns.COLOR_RANGE,
                        MediaStore.Video.VideoColumns.COLOR_TRANSFER,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME},
                bundle, null)) {
            assertThat(c).isNotNull();
            assertThat(c.getCount()).isEqualTo(1);
            c.moveToNext();
            assertThat(c.getString(0)).isEqualTo(testVideoFile.getPath());
            assertThat(c.getString(1)).isEqualTo("MyVideo");
            assertThat(c.getInt(2)).isEqualTo(1);
            assertThat(c.getInt(3)).isEqualTo(5);
            assertThat(c.getInt(4)).isEqualTo(10);
            assertThat(c.getInt(5)).isEqualTo(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
            assertThat(c.getString(6)).isEqualTo("com.hello.video");
        }
    }

    private void assertRestoreForImageFile(File testImageFile) {
        Bundle bundle = new Bundle();
        bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "_data LIKE ?");
        bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{testImageFile.getPath()});
        try (Cursor c = mIsolatedResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[]{MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.TITLE,
                        MediaStore.Files.FileColumns.HEIGHT,
                        MediaStore.Files.FileColumns.WIDTH,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Images.ImageColumns.DESCRIPTION,
                        MediaStore.Images.ImageColumns.EXPOSURE_TIME,
                        MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE,
                        MediaStore.Files.FileColumns.IS_FAVORITE,
                        MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME},
                bundle, null)) {
            assertThat(c).isNotNull();
            assertThat(c.getCount()).isEqualTo(1);
            c.moveToNext();
            assertThat(c.getString(0)).isEqualTo(testImageFile.getPath());
            assertThat(c.getString(1)).isEqualTo("MyImage");
            assertThat(c.getInt(2)).isEqualTo(1600);
            assertThat(c.getInt(3)).isEqualTo(3200);
            assertThat(c.getInt(4)).isEqualTo(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
            assertThat(c.getString(5)).isEqualTo("My camera image");
            assertThat(c.getString(6)).isEqualTo("20");
            assertThat(c.getInt(7)).isEqualTo(2);
            assertThat(c.getInt(8)).isEqualTo(1);
            assertThat(c.getString(9)).isEqualTo("com.hello.image");
        }
    }

    private void seedAudioDataIntoLevelDb(File testAudioFile, LevelDBInstance levelDBInstance)
            throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME, "com.hello.audio");
        values.put(MediaStore.Files.FileColumns.SIZE,
                String.valueOf(Files.size(Path.of(testAudioFile.getAbsolutePath()))));
        values.put(MediaStore.Files.FileColumns.TITLE, "MyAudio");
        values.put(MediaStore.Audio.AudioColumns.TRACK, "Forever");
        values.put(MediaStore.Files.FileColumns.DURATION, "120");
        values.put(MediaStore.Files.FileColumns.ALBUM, "ColdPlay");
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO));
        assertThat(levelDBInstance.insert(
                new LevelDBEntry(testAudioFile.getAbsolutePath(),
                        createSerialisedValue(values))).isSuccess()).isTrue();
    }

    private void seedVideoDataIntoLevelDb(File testVideoFile, LevelDBInstance levelDBInstance)
            throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME, "com.hello.video");
        values.put(MediaStore.Files.FileColumns.SIZE,
                String.valueOf(Files.size(Path.of(testVideoFile.getAbsolutePath()))));
        values.put(MediaStore.Files.FileColumns.TITLE, "MyVideo");
        values.put(MediaStore.Video.VideoColumns.COLOR_STANDARD, "1");
        values.put(MediaStore.Video.VideoColumns.COLOR_RANGE, "5");
        values.put(MediaStore.Video.VideoColumns.COLOR_TRANSFER, "10");
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO));
        assertThat(levelDBInstance.insert(
                new LevelDBEntry(testVideoFile.getAbsolutePath(),
                        createSerialisedValue(values))).isSuccess()).isTrue();
    }

    private void seedImageDataIntoLevelDb(File testFile, LevelDBInstance levelDBInstance)
            throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME, "com.hello.image");
        values.put(MediaStore.Files.FileColumns.SIZE,
                String.valueOf(Files.size(Path.of(testFile.getAbsolutePath()))));
        values.put(MediaStore.Files.FileColumns.TITLE, "MyImage");
        values.put(MediaStore.Files.FileColumns.HEIGHT, "1600");
        values.put(MediaStore.Files.FileColumns.WIDTH, "3200");
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
        values.put(MediaStore.Images.ImageColumns.DESCRIPTION, "My camera image");
        values.put(MediaStore.Images.ImageColumns.EXPOSURE_TIME, "20");
        values.put(MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE, "2");
        values.put(MediaStore.Files.FileColumns.IS_FAVORITE, "1");
        assertThat(levelDBInstance.insert(
                new LevelDBEntry(testFile.getAbsolutePath(),
                        createSerialisedValue(values))).isSuccess()).isTrue();
    }

    private void stageNewFile(int resId, File file) throws IOException {
        file.createNewFile();
        stage(resId, file);
    }

    private String createSerialisedValue(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        for (String backupColumn : sColumnNameToIdMap.keySet()) {
            if (entries.containsKey(backupColumn)) {
                sb.append(sColumnNameToIdMap.get(backupColumn)).append(KEY_VALUE_SEPARATOR).append(
                        entries.get(backupColumn));
                sb.append(FIELD_SEPARATOR);
            }
        }
        return sb.toString();
    }

    private static void createColumnToKeyMap() {
        sColumnNameToIdMap = new HashMap<>();
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns.IS_FAVORITE, "0");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns.MEDIA_TYPE, "1");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns.MIME_TYPE, "2");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns._USER_ID, "3");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns.SIZE, "4");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.DATE_TAKEN, "5");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.CD_TRACK_NUMBER, "6");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.ALBUM, "7");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.ARTIST, "8");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.AUTHOR, "9");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.COMPOSER, "10");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.GENRE, "11");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.TITLE, "12");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.YEAR, "13");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.DURATION, "14");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.NUM_TRACKS, "15");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.WRITER, "16");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.ALBUM_ARTIST, "17");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.DISC_NUMBER, "18");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.COMPILATION, "19");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.BITRATE, "20");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.CAPTURE_FRAMERATE, "21");
        sColumnNameToIdMap.put(MediaStore.Audio.AudioColumns.TRACK, "22");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.DOCUMENT_ID, "23");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.INSTANCE_ID, "24");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.ORIGINAL_DOCUMENT_ID, "25");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.RESOLUTION, "26");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.ORIENTATION, "27");
        sColumnNameToIdMap.put(MediaStore.Video.VideoColumns.COLOR_STANDARD, "28");
        sColumnNameToIdMap.put(MediaStore.Video.VideoColumns.COLOR_TRANSFER, "29");
        sColumnNameToIdMap.put(MediaStore.Video.VideoColumns.COLOR_RANGE, "30");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns._VIDEO_CODEC_TYPE, "31");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.WIDTH, "32");
        sColumnNameToIdMap.put(MediaStore.MediaColumns.HEIGHT, "33");
        sColumnNameToIdMap.put(MediaStore.Images.ImageColumns.DESCRIPTION, "34");
        sColumnNameToIdMap.put(MediaStore.Images.ImageColumns.EXPOSURE_TIME, "35");
        sColumnNameToIdMap.put(MediaStore.Images.ImageColumns.F_NUMBER, "36");
        sColumnNameToIdMap.put(MediaStore.Images.ImageColumns.ISO, "37");
        sColumnNameToIdMap.put(MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE, "38");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns._SPECIAL_FORMAT, "39");
        sColumnNameToIdMap.put(MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME, "40");
        // Adding number gap to allow addition of new values
        sColumnNameToIdMap.put(MediaStore.MediaColumns.XMP, "80");
    }
}
