/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_SUBTITLE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

import static com.android.providers.media.AccessChecker.getWhereForConstrainedAccess;
import static com.android.providers.media.AccessChecker.getWhereForMediaTypeMatch;
import static com.android.providers.media.AccessChecker.getWhereForOwnerPackageMatch;
import static com.android.providers.media.AccessChecker.getWhereForUserIdMatch;
import static com.android.providers.media.AccessChecker.getWhereForUserSelectedAccess;
import static com.android.providers.media.AccessChecker.hasAccessToCollection;
import static com.android.providers.media.AccessChecker.hasUserSelectedAccess;
import static com.android.providers.media.LocalUriMatcher.AUDIO_MEDIA;
import static com.android.providers.media.LocalUriMatcher.DOWNLOADS;
import static com.android.providers.media.LocalUriMatcher.DOWNLOADS_ID;
import static com.android.providers.media.LocalUriMatcher.FILES;
import static com.android.providers.media.LocalUriMatcher.FILES_ID;
import static com.android.providers.media.LocalUriMatcher.IMAGES_MEDIA;
import static com.android.providers.media.LocalUriMatcher.IMAGES_MEDIA_ID;
import static com.android.providers.media.LocalUriMatcher.IMAGES_THUMBNAILS;
import static com.android.providers.media.LocalUriMatcher.IMAGES_THUMBNAILS_ID;
import static com.android.providers.media.LocalUriMatcher.VIDEO_MEDIA;
import static com.android.providers.media.LocalUriMatcher.VIDEO_MEDIA_ID;
import static com.android.providers.media.LocalUriMatcher.VIDEO_THUMBNAILS;
import static com.android.providers.media.LocalUriMatcher.VIDEO_THUMBNAILS_ID;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import android.system.Os;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class AccessCheckerTest {
    @Test
    public void testHasAccessToCollection_forRead_noPerms() {
        LocalCallingIdentity hasNoPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), 0);

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with no permissions only has access to owned files
            assertWithMessage("Expected no global read access to collection " + collection)
                    .that(hasAccessToCollection(hasNoPerms, collection, false))
                    .isFalse();
        }
    }

    @Test
    public void testHasAccessToCollection_forRead_hasReadMediaPerms() {
        LocalCallingIdentity hasReadMedia = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), getReadMediaPermission());

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA)) {
            // App with read media permissions only has full read access to media collection
            assertWithMessage("Expected full read access to " + collection)
                    .that(hasAccessToCollection(hasReadMedia, collection, false))
                    .isTrue();
        }

        for (int collection : Arrays.asList(
                DOWNLOADS,
                FILES)) {
            // App with read media permissions doesn't have full read access to non-media collection
            assertWithMessage("Expected no full read access to " + collection)
                    .that(hasAccessToCollection(hasReadMedia, collection, false))
                    .isFalse();
        }
    }

    @Test
    public void testHasAccessToCollection_forRead_hasLegacyRead() {
        LocalCallingIdentity hasLegacyRead = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), getLegacyReadPermission());

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // Legacy app with READ_EXTERNAL_STORAGE permission has read access to all files
            assertWithMessage("Expected global read access to collection " + collection)
                    .that(hasAccessToCollection(hasLegacyRead, collection, false))
                    .isTrue();
        }
    }

    @Test
    public void testHasAccessToCollection_forWrite_noPerms() {
        LocalCallingIdentity hasNoPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), 0);

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with no permissions only has access to owned files
            assertWithMessage("Expected no global write access to collection " + collection)
                    .that(hasAccessToCollection(hasNoPerms, collection, true))
                    .isFalse();
        }
    }

    @Test
    public void testHasAccessToCollection_forWrite_hasWriteMediaPerms() {
        LocalCallingIdentity hasWriteMedia = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), getWriteMediaPermission());

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA)) {
            // App with write media permissions only has full write access to media collection
            assertWithMessage("Expected global write access to " + collection)
                    .that(hasAccessToCollection(hasWriteMedia, collection, true))
                    .isTrue();
        }

        for (int collection : Arrays.asList(
                DOWNLOADS,
                FILES)) {
            // App with write media permissions doesn't have full write access to non-media
            // collection
            assertWithMessage("Expected no full write access to " + collection)
                    .that(hasAccessToCollection(hasWriteMedia, collection, true))
                    .isFalse();
        }
    }

    @Test
    public void testHasAccessToCollection_forWrite_hasLegacyWrite() {
        LocalCallingIdentity hasLegacyWrite = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(),
                getLegacyWritePermission());

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA)) {
            // Legacy app with WRITE_EXTERNAL_STORAGE permission has write access to media
            // files
            assertWithMessage("Expected global write access to collection " + collection)
                    .that(hasAccessToCollection(hasLegacyWrite, collection, true))
                    .isTrue();
        }

        for (int collection : Arrays.asList(
                DOWNLOADS,
                FILES)) {
            // Legacy app with WRITE_EXTERNAL_STORAGE permission has write access to only primary
            // storage.
            assertWithMessage("Expected no full write access to " + collection)
                    .that(hasAccessToCollection(hasLegacyWrite, collection, true))
                    .isFalse();
        }
    }

    @Test
    public void testHasUserSelectedAccess_noPerms() {
        LocalCallingIdentity hasNoPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), 0);
        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with no permissions doesn't have access to user selected items.
            assertWithMessage("Expected no user selected read access to collection " + collection)
                    .that(hasUserSelectedAccess(hasNoPerms, collection, false)).isFalse();
        }

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with no permissions doesn't have access to user selected items.
            assertWithMessage("Expected no user selected write access to collection " + collection)
                    .that(hasUserSelectedAccess(hasNoPerms, collection, true)).isFalse();
        }
    }

    @Test
    public void testHasUserSelectedAccess_userSelectedPerms() {
        LocalCallingIdentity userSelectPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(),
                getUserSelectedPermission());
        for (int collection : Arrays.asList(
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with user selected permission grant has read access to user selected visual media
            // files
            assertWithMessage("Expected no user selected read access to collection " + collection)
                    .that(hasUserSelectedAccess(userSelectPerms, collection, false)).isTrue();
        }

        // App with user selected permission grant doesn't have access to audio files.
        assertWithMessage("Expected no user selected read access to audio collection")
                .that(hasUserSelectedAccess(userSelectPerms, AUDIO_MEDIA, false)).isFalse();

        for (int collection : Arrays.asList(
                AUDIO_MEDIA,
                VIDEO_MEDIA,
                IMAGES_MEDIA,
                DOWNLOADS,
                FILES)) {
            // App with user selected permission grant doesn't have write access
            assertWithMessage("Expected no user selected write access to collection " + collection)
                    .that(hasUserSelectedAccess(userSelectPerms, collection, true)).isFalse();
        }
    }

    @Test
    public void testGetWhereForUserSelectedAccess() {
        LocalCallingIdentity callingIdentity = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(),
                getUserSelectedPermission());
        for (int collection : Arrays.asList(
                VIDEO_MEDIA,
                VIDEO_MEDIA_ID,
                IMAGES_MEDIA,
                IMAGES_MEDIA_ID,
                DOWNLOADS,
                DOWNLOADS_ID,
                FILES,
                FILES_ID)) {
            assertWithMessage("Expected user selected where clause for collection " + collection)
                    .that(getWhereForUserSelectedAccess(callingIdentity, collection))
                    .isEqualTo("_id IN (SELECT file_id from media_grants WHERE "
                            + getWhereForOwnerPackageMatch(callingIdentity)
                            + " AND "
                            + getWhereForUserIdMatch(callingIdentity)
                            + ")");
        }

        for (int collection : Arrays.asList(
                IMAGES_THUMBNAILS,
                IMAGES_THUMBNAILS_ID)) {
            assertWithMessage("Expected user selected where clause for collection " + collection)
                    .that(getWhereForUserSelectedAccess(callingIdentity, collection))
                    .isEqualTo("image_id IN (SELECT file_id from media_grants WHERE "
                            + getWhereForOwnerPackageMatch(callingIdentity)
                            + " AND "
                            + getWhereForUserIdMatch(callingIdentity)
                            + ")");
        }

        for (int collection : Arrays.asList(
                VIDEO_THUMBNAILS,
                VIDEO_THUMBNAILS_ID)) {
            assertWithMessage("Expected user selected where clause for collection " + collection)
                    .that(getWhereForUserSelectedAccess(callingIdentity, collection))
                    .isEqualTo("video_id IN (SELECT file_id from media_grants WHERE "
                            + getWhereForOwnerPackageMatch(callingIdentity)
                            + " AND "
                            + getWhereForUserIdMatch(callingIdentity)
                            + ")");
        }


        assertThrows(UnsupportedOperationException.class,
                () -> getWhereForUserSelectedAccess(callingIdentity, AUDIO_MEDIA));
    }


    @Test
    public void testGetWhereForConstrainedAccess_forRead_noPerms() {
        LocalCallingIdentity hasNoPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), 0);

        // App with no permissions only has access to owned files
        assertWithMessage("Expected owned access SQL for Audio collection")
                .that(getWhereForConstrainedAccess(hasNoPerms, AUDIO_MEDIA, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasNoPerms)
                        + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1");
        assertWithMessage("Expected owned access SQL for Video collection")
                .that(getWhereForConstrainedAccess(hasNoPerms, VIDEO_MEDIA, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasNoPerms));
        assertWithMessage("Expected owned access SQL for Images collection")
                .that(getWhereForConstrainedAccess(hasNoPerms, IMAGES_MEDIA, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasNoPerms));

        // App with no permissions only has access to owned files
        assertWithMessage("Expected owned access SQL for Downloads collection")
                .that(getWhereForConstrainedAccess(hasNoPerms, DOWNLOADS, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasNoPerms));
        assertWithMessage("Expected owned access SQL for FILES collection")
                .that(getWhereForConstrainedAccess(hasNoPerms, FILES, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasNoPerms));
    }

    @Test
    public void testGetWhereForConstrainedAccess_forRead_hasReadMediaPerms() {
        LocalCallingIdentity hasReadMedia = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), getReadMediaPermission());

        // App with READ_EXTERNAL_STORAGE or READ_MEDIA_* permission has access to media files. In
        // this case, we already tested AccessCheckHelper#hasAccessToCollection returns true

        // App with READ_EXTERNAL_STORAGE or READ_MEDIA_* permission has access to only owned
        // non-media files or media files.
        assertWithMessage("Expected owned access SQL for Downloads collection")
                .that(getWhereForConstrainedAccess(hasReadMedia, DOWNLOADS, false, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasReadMedia));
        assertWithMessage("Expected owned access SQL for FILES collection")
                .that(getWhereForConstrainedAccess(hasReadMedia, FILES, false, Bundle.EMPTY))
                .isEqualTo(
                        getWhereForOwnerPackageMatch(hasReadMedia) + " OR " + getFilesAccessSql());
    }

    @Test
    public void testGetWhereForConstrainedAccess_forWrite_noPerms() {
        LocalCallingIdentity noPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), 0);

        // App with no permissions only has access to owned files.
        assertWithMessage("Expected owned access SQL for Audio collection")
                .that(getWhereForConstrainedAccess(noPerms, AUDIO_MEDIA, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(noPerms)
                        + " OR is_ringtone=1 OR is_alarm=1 OR is_notification=1");
        assertWithMessage("Expected owned access SQL for Video collection")
                .that(getWhereForConstrainedAccess(noPerms, VIDEO_MEDIA, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(noPerms));
        assertWithMessage("Expected owned access SQL for Images collection")
                .that(getWhereForConstrainedAccess(noPerms, IMAGES_MEDIA, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(noPerms));

        // App with no permissions only has access to owned files
        assertWithMessage("Expected owned access SQL for Downloads collection")
                .that(getWhereForConstrainedAccess(noPerms, DOWNLOADS, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(noPerms));
        assertWithMessage("Expected owned access SQL for FILES collection")
                .that(getWhereForConstrainedAccess(noPerms, FILES, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(noPerms));
    }

    @Test
    public void testGetWhereForConstrainedAccess_forWrite_hasWriteMediaPerms() {
        LocalCallingIdentity hasReadPerms = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(), getWriteMediaPermission());

        // App with write permission to media files has access write access to media files. In
        // this case, we already tested AccessCheckHelper#hasAccessToCollection returns true

        // App with write permission to media files has access write access to media files and owned
        // files.
        assertWithMessage("Expected owned access SQL for Downloads collection")
                .that(getWhereForConstrainedAccess(hasReadPerms, DOWNLOADS, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasReadPerms));
        assertWithMessage("Expected owned access SQL for FILES collection")
                .that(getWhereForConstrainedAccess(hasReadPerms, FILES, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasReadPerms) + " OR "
                        + getFilesAccessSql());
    }

    @Test
    public void testGetWhereForConstrainedAccess_forWrite_hasLegacyWrite() {
        LocalCallingIdentity hasLegacyWrite = LocalCallingIdentity.forTest(
                InstrumentationRegistry.getTargetContext(), Os.getuid(),
                getLegacyWritePermission());

        // Legacy app with WRITE_EXTERNAL_STORAGE permission has access to media files. In
        // this case, we already tested AccessCheckHelper#hasAccessToCollection returns true

        // Legacy app with WRITE_EXTERNAL_STORAGE permission has access to non-media files as well.
        // However, they don't have global write access to secondary volume.
        assertWithMessage("Expected where clause SQL for Downloads collection to be")
                .that(getWhereForConstrainedAccess(hasLegacyWrite, DOWNLOADS, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasLegacyWrite) + " OR "
                        + AccessChecker.getWhereForExternalPrimaryMatch());
        assertWithMessage("Expected where clause SQL for FILES collection to be")
                .that(getWhereForConstrainedAccess(hasLegacyWrite, FILES, true, Bundle.EMPTY))
                .isEqualTo(getWhereForOwnerPackageMatch(hasLegacyWrite) + " OR "
                        + AccessChecker.getWhereForExternalPrimaryMatch() + " OR "
                        + getFilesAccessSql());
    }

    private String getFilesAccessSql() {
        final ArrayList<String> options = new ArrayList<>();
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_AUDIO));
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_PLAYLIST));
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_SUBTITLE));
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_VIDEO));
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_SUBTITLE));
        options.add(getWhereForMediaTypeMatch(MEDIA_TYPE_IMAGE));

        return TextUtils.join(" OR ", options);
    }

    private static int getReadMediaPermission() {
        return LocalCallingIdentity.PERMISSION_READ_AUDIO
                | LocalCallingIdentity.PERMISSION_READ_VIDEO
                | LocalCallingIdentity.PERMISSION_READ_IMAGES;
    }

    private static int getWriteMediaPermission() {
        return LocalCallingIdentity.PERMISSION_WRITE_AUDIO
                | LocalCallingIdentity.PERMISSION_WRITE_VIDEO
                | LocalCallingIdentity.PERMISSION_WRITE_IMAGES;
    }

    private static int getLegacyReadPermission() {
        return LocalCallingIdentity.PERMISSION_READ_AUDIO
                | LocalCallingIdentity.PERMISSION_READ_VIDEO
                | LocalCallingIdentity.PERMISSION_READ_IMAGES
                | LocalCallingIdentity.PERMISSION_IS_LEGACY_READ;
    }

    private static int getUserSelectedPermission() {
        return LocalCallingIdentity.PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED;
    }

    private static int getLegacyWritePermission() {
        return LocalCallingIdentity.PERMISSION_WRITE_AUDIO
                | LocalCallingIdentity.PERMISSION_WRITE_VIDEO
                | LocalCallingIdentity.PERMISSION_WRITE_IMAGES
                | LocalCallingIdentity.PERMISSION_IS_LEGACY_WRITE;
    }
}
