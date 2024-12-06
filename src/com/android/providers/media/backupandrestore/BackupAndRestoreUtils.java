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

import android.provider.MediaStore;

import com.google.common.collect.HashBiMap;

/**
 * Class containing common constants and methods for backup and restore.
 */
public final class BackupAndRestoreUtils {

    /**
     * String separator used for separating key, value pairs.
     */
    static final String FIELD_SEPARATOR = ":::";

    /**
     * String separator used for key and value.
     */
    static final String KEY_VALUE_SEPARATOR = "=";

    /**
     * Backup directory under file's directory.
     */
    static final String BACKUP_DIRECTORY_NAME = "backup";

    /**
     * Restore directory under file's directory.
     */
    static final String RESTORE_DIRECTORY_NAME = "restore";

    /**
     * Shared preference name for backup and restore.
     */
    static final String SHARED_PREFERENCE_NAME = "BACKUP_DATA";

    /**
     * Key name for storing status of restore.
     */
    static final String RESTORE_COMPLETED = "RESTORE_COMPLETED";

    /**
     * Array of columns backed up for restore in the future.
     */
    static final String[] BACKUP_COLUMNS = new String[]{
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns._USER_ID,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.CD_TRACK_NUMBER,
            MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.AUTHOR,
            MediaStore.MediaColumns.COMPOSER,
            MediaStore.MediaColumns.GENRE,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.YEAR,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.NUM_TRACKS,
            MediaStore.MediaColumns.WRITER,
            MediaStore.MediaColumns.ALBUM_ARTIST,
            MediaStore.MediaColumns.DISC_NUMBER,
            MediaStore.MediaColumns.COMPILATION,
            MediaStore.MediaColumns.BITRATE,
            MediaStore.MediaColumns.CAPTURE_FRAMERATE,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.MediaColumns.DOCUMENT_ID,
            MediaStore.MediaColumns.INSTANCE_ID,
            MediaStore.MediaColumns.ORIGINAL_DOCUMENT_ID,
            MediaStore.MediaColumns.RESOLUTION,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.Video.VideoColumns.COLOR_STANDARD,
            MediaStore.Video.VideoColumns.COLOR_TRANSFER,
            MediaStore.Video.VideoColumns.COLOR_RANGE,
            MediaStore.Files.FileColumns._VIDEO_CODEC_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.ImageColumns.DESCRIPTION,
            MediaStore.Images.ImageColumns.EXPOSURE_TIME,
            MediaStore.Images.ImageColumns.F_NUMBER,
            MediaStore.Images.ImageColumns.ISO,
            MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE,
            MediaStore.Files.FileColumns._SPECIAL_FORMAT,
            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
            // Keeping at the last as it is a BLOB type and can have separator used in our
            // serialisation
            MediaStore.MediaColumns.XMP,
    };

    static final HashBiMap<String, String> sIdToColumnBiMap = HashBiMap.create();

    // Creates a BiMap of id to column for serialisation and de-serialisation purpose.
    // Append new fields in order and keep
    // {@link android.provider.CloudMediaProviderContract.MediaColumns.XMP} as the last field.
    // DO NOT CHANGE ANY OTHER MAPPING HERE.
    static {
        sIdToColumnBiMap.put("0", MediaStore.Files.FileColumns.IS_FAVORITE);
        sIdToColumnBiMap.put("1", MediaStore.Files.FileColumns.MEDIA_TYPE);
        sIdToColumnBiMap.put("2", MediaStore.Files.FileColumns.MIME_TYPE);
        sIdToColumnBiMap.put("3", MediaStore.Files.FileColumns._USER_ID);
        sIdToColumnBiMap.put("4", MediaStore.Files.FileColumns.SIZE);
        sIdToColumnBiMap.put("5", MediaStore.MediaColumns.DATE_TAKEN);
        sIdToColumnBiMap.put("6", MediaStore.MediaColumns.CD_TRACK_NUMBER);
        sIdToColumnBiMap.put("7", MediaStore.MediaColumns.ALBUM);
        sIdToColumnBiMap.put("8", MediaStore.MediaColumns.ARTIST);
        sIdToColumnBiMap.put("9", MediaStore.MediaColumns.AUTHOR);
        sIdToColumnBiMap.put("10", MediaStore.MediaColumns.COMPOSER);
        sIdToColumnBiMap.put("11", MediaStore.MediaColumns.GENRE);
        sIdToColumnBiMap.put("12", MediaStore.MediaColumns.TITLE);
        sIdToColumnBiMap.put("13", MediaStore.MediaColumns.YEAR);
        sIdToColumnBiMap.put("14", MediaStore.MediaColumns.DURATION);
        sIdToColumnBiMap.put("15", MediaStore.MediaColumns.NUM_TRACKS);
        sIdToColumnBiMap.put("16", MediaStore.MediaColumns.WRITER);
        sIdToColumnBiMap.put("17", MediaStore.MediaColumns.ALBUM_ARTIST);
        sIdToColumnBiMap.put("18", MediaStore.MediaColumns.DISC_NUMBER);
        sIdToColumnBiMap.put("19", MediaStore.MediaColumns.COMPILATION);
        sIdToColumnBiMap.put("20", MediaStore.MediaColumns.BITRATE);
        sIdToColumnBiMap.put("21", MediaStore.MediaColumns.CAPTURE_FRAMERATE);
        sIdToColumnBiMap.put("22", MediaStore.Audio.AudioColumns.TRACK);
        sIdToColumnBiMap.put("23", MediaStore.MediaColumns.DOCUMENT_ID);
        sIdToColumnBiMap.put("24", MediaStore.MediaColumns.INSTANCE_ID);
        sIdToColumnBiMap.put("25", MediaStore.MediaColumns.ORIGINAL_DOCUMENT_ID);
        sIdToColumnBiMap.put("26", MediaStore.MediaColumns.RESOLUTION);
        sIdToColumnBiMap.put("27", MediaStore.MediaColumns.ORIENTATION);
        sIdToColumnBiMap.put("28", MediaStore.Video.VideoColumns.COLOR_STANDARD);
        sIdToColumnBiMap.put("29", MediaStore.Video.VideoColumns.COLOR_TRANSFER);
        sIdToColumnBiMap.put("30", MediaStore.Video.VideoColumns.COLOR_RANGE);
        sIdToColumnBiMap.put("31", MediaStore.Files.FileColumns._VIDEO_CODEC_TYPE);
        sIdToColumnBiMap.put("32", MediaStore.MediaColumns.WIDTH);
        sIdToColumnBiMap.put("33", MediaStore.MediaColumns.HEIGHT);
        sIdToColumnBiMap.put("34", MediaStore.Images.ImageColumns.DESCRIPTION);
        sIdToColumnBiMap.put("35", MediaStore.Images.ImageColumns.EXPOSURE_TIME);
        sIdToColumnBiMap.put("36", MediaStore.Images.ImageColumns.F_NUMBER);
        sIdToColumnBiMap.put("37", MediaStore.Images.ImageColumns.ISO);
        sIdToColumnBiMap.put("38", MediaStore.Images.ImageColumns.SCENE_CAPTURE_TYPE);
        sIdToColumnBiMap.put("39", MediaStore.Files.FileColumns._SPECIAL_FORMAT);
        sIdToColumnBiMap.put("40", MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME);
        // Adding number gap to allow addition of new values
        sIdToColumnBiMap.put("80", MediaStore.MediaColumns.XMP);
    }
}
