/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.provider.CloudMediaProviderContract.AccountInfo;
import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.CloudMediaProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates {@link TestMedia} items that can be accessed via test {@link CloudMediaProvider}
 * instances.
 */
public class PickerProviderMediaGenerator {
    private static final Map<String, MediaGenerator> sMediaGeneratorMap = new HashMap<>();
    private static final String[] MEDIA_PROJECTION = new String[] {
        MediaColumns.ID,
        MediaColumns.MEDIA_STORE_URI,
        MediaColumns.MIME_TYPE,
        MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
        MediaColumns.DATE_TAKEN_MS,
        MediaColumns.GENERATION_MODIFIED,
        MediaColumns.SIZE_BYTES,
        MediaColumns.DURATION_MS,
        MediaColumns.IS_FAVORITE,
    };

    private static final String[] ALBUM_PROJECTION = new String[] {
        AlbumColumns.ID,
        AlbumColumns.DISPLAY_NAME,
        AlbumColumns.DATE_TAKEN_MS,
        AlbumColumns.MEDIA_COVER_ID,
        AlbumColumns.MEDIA_COUNT,
        AlbumColumns.TYPE,
    };

    private static final String[] DELETED_MEDIA_PROJECTION = new String[] { MediaColumns.ID };

    // TODO(b/195009148): Investigate how to expose as TestApi and avoid hard-coding
    // Copied from CloudMediaProviderContract#AlbumColumns
    public static final String ALBUM_COLUMN_TYPE_LOCAL = "LOCAL";
    public static final String ALBUM_COLUMN_TYPE_CLOUD = null;
    public static final String ALBUM_COLUMN_TYPE_FAVORITES = "FAVORITES";
    public static final String ALBUM_COLUMN_TYPE_UNRELIABLE_VOLUME = "UNRELIABLE_VOLUME";

    public static class MediaGenerator {
        private final List<TestMedia> mMedia = new ArrayList<>();
        private final List<TestMedia> mDeletedMedia = new ArrayList<>();
        private final List<TestAlbum> mAlbums = new ArrayList<>();
        private String mVersion;
        private long mGeneration;
        private String mAccountName;
        private Intent mAccountConfigurationIntent;

        public Cursor getMedia(long generation, String albumdId, String mimeType, long sizeBytes) {
            return getCursor(mMedia, generation, albumdId, mimeType, sizeBytes,
                    /* isDeleted */ false);
        }

        public Cursor getAlbums(String mimeType, long sizeBytes, boolean isLocal) {
            return getCursor(mAlbums, mimeType, sizeBytes, isLocal);
        }

        public Cursor getDeletedMedia(long generation) {
            return getCursor(mDeletedMedia, generation, /* albumId */ STRING_DEFAULT,
                    /* mimeType */ STRING_DEFAULT, /* sizeBytes */ LONG_DEFAULT,
                    /* isDeleted */ true);
        }

        public Bundle getAccountInfo() {
            Bundle bundle = new Bundle();
            bundle.putString(AccountInfo.ACTIVE_ACCOUNT_NAME, mAccountName);
            bundle.putParcelable(AccountInfo.ACCOUNT_CONFIGURATION_INTENT,
                    mAccountConfigurationIntent);

            return bundle;
        }

        public void setAccountInfo(String accountName, Intent configIntent) {
            mAccountName = accountName;
            mAccountConfigurationIntent = configIntent;
        }

        public void addMedia(String localId, String cloudId) {
            mDeletedMedia.remove(createPlaceholderMedia(localId, cloudId));
            mMedia.add(0, createTestMedia(localId, cloudId));
        }

        public void addMedia(String localId, String cloudId, String albumId, String mimeType,
                int standardMimeTypeExtension, long sizeBytes, boolean isFavorite) {
            mDeletedMedia.remove(createPlaceholderMedia(localId, cloudId));
            mMedia.add(0,
                    createTestMedia(localId, cloudId, albumId, mimeType, standardMimeTypeExtension,
                            sizeBytes, isFavorite));
        }

        public void deleteMedia(String localId, String cloudId) {
            if (mMedia.remove(createPlaceholderMedia(localId, cloudId))) {
                mDeletedMedia.add(createTestMedia(localId, cloudId));
            }
        }

        public void createAlbum(String id) {
            mAlbums.add(createTestAlbum(id));
        }

        public void resetAll() {
            mMedia.clear();
            mDeletedMedia.clear();
            mAlbums.clear();
        }

        public void setVersion(String version) {
            mVersion = version;
        }

        public String getVersion() {
            return mVersion;
        }

        public long getGeneration() {
            return mGeneration;
        }

        public long getCount() {
            return mMedia.size();
        }

        private TestAlbum createTestAlbum(String id) {
            return new TestAlbum(id, mMedia);
        }

        private TestMedia createTestMedia(String localId, String cloudId) {
            // Increase generation
            return new TestMedia(localId, cloudId, ++mGeneration);
        }

        private TestMedia createTestMedia(String localId, String cloudId, String albumId,
                String mimeType, int standardMimeTypeExtension, long sizeBytes,
                boolean isFavorite) {
            // Increase generation
            return new TestMedia(localId, cloudId, albumId, mimeType, standardMimeTypeExtension,
                    sizeBytes, /* durationMs */ 0, ++mGeneration, isFavorite);
        }

        private static TestMedia createPlaceholderMedia(String localId, String cloudId) {
            // Don't increase generation. Used to create a throw-away element used for removal from
            // |mMedia| or |mDeletedMedia|
            return new TestMedia(localId, cloudId, 0);
        }

        private static Cursor getCursor(List<TestMedia> mediaList, long generation,
                String albumId, String mimeType, long sizeBytes, boolean isDeleted) {
            final MatrixCursor matrix;
            if (isDeleted) {
                matrix = new MatrixCursor(DELETED_MEDIA_PROJECTION);
            } else {
                matrix = new MatrixCursor(MEDIA_PROJECTION);
            }

            for (TestMedia media : mediaList) {
                if (media.generation > generation
                        && matchesFilter(media, albumId, mimeType, sizeBytes)) {
                    matrix.addRow(media.toArray(isDeleted));
                }
            }
            return matrix;
        }

        private static Cursor getCursor(List<TestAlbum> albumList, String mimeType, long sizeBytes,
                boolean isLocal) {
            final MatrixCursor matrix = new MatrixCursor(ALBUM_PROJECTION);

            for (TestAlbum album : albumList) {
                final String[] res = album.toArray(mimeType, sizeBytes, isLocal);
                if (res != null) {
                    matrix.addRow(res);
                }
            }
            return matrix;
        }
    }

    private static class TestMedia {
        public final String localId;
        public final String cloudId;
        public final String albumId;
        public final String mimeType;
        public final int standardMimeTypeExtension;
        public final long sizeBytes;
        public final long dateTakenMs;
        public final long durationMs;
        public final long generation;
        public final boolean isFavorite;

        public TestMedia(String localId, String cloudId, long generation) {
            this(localId, cloudId, /* albumId */ null, "image/jpeg",
                    /* standardMimeTypeExtension */ MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE,
                    /* sizeBytes */ 4096, /* durationMs */ 0, generation,
                    /* isFavorite */ false);
        }

        public TestMedia(String localId, String cloudId, String albumId, String mimeType,
                int standardMimeTypeExtension, long sizeBytes, long durationMs, long generation,
                boolean isFavorite) {
            this.localId = localId;
            this.cloudId = cloudId;
            this.albumId = albumId;
            this.mimeType = mimeType;
            this.standardMimeTypeExtension = standardMimeTypeExtension;
            this.sizeBytes = sizeBytes;
            this.dateTakenMs = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.generation = generation;
            this.isFavorite = isFavorite;
            SystemClock.sleep(1);
        }

        public String[] toArray(boolean isDeleted) {
            if (isDeleted) {
                return new String[] {getId()};
            }

            return new String[] {
                getId(),
                localId == null ? null : "content://media/external/files/" + localId,
                mimeType,
                String.valueOf(standardMimeTypeExtension),
                String.valueOf(dateTakenMs),
                String.valueOf(generation),
                String.valueOf(sizeBytes),
                String.valueOf(durationMs),
                String.valueOf(isFavorite ? 1 : 0)
            };
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || !(o instanceof TestMedia)) {
                return false;
            }
            TestMedia other = (TestMedia) o;
            return Objects.equals(localId, other.localId) && Objects.equals(cloudId, other.cloudId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(localId, cloudId);
        }

        private String getId() {
            return cloudId == null ? localId : cloudId;
        }
    }

    private static class TestAlbum {
        public final String id;
        private final List<TestMedia> media;

        public TestAlbum(String id, List<TestMedia> media) {
            this.id = id;
            this.media = media;
        }

        public String[] toArray(String mimeType, long sizeBytes, boolean isLocal) {
            long mediaCount = 0;
            String mediaCoverId = null;
            long dateTakenMs = 0;

            for (TestMedia m : media) {
                if (matchesFilter(m, id, mimeType, sizeBytes)) {
                    if (mediaCount++ == 0) {
                        mediaCoverId = m.getId();
                        dateTakenMs = m.dateTakenMs;
                    }
                }
            }

            if (mediaCount == 0) {
                return null;
            }

            return new String[] {
                id,
                mediaCoverId,
                /* displayName */ id,
                String.valueOf(dateTakenMs),
                String.valueOf(mediaCount),
                isLocal ? ALBUM_COLUMN_TYPE_LOCAL : ALBUM_COLUMN_TYPE_CLOUD
            };
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || !(o instanceof TestAlbum)) {
                return false;
            }

            TestAlbum other = (TestAlbum) o;
            return Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static boolean matchesFilter(TestMedia media, String albumId, String mimeType,
            long sizeBytes) {
        if (!Objects.equals(albumId, STRING_DEFAULT) && !Objects.equals(albumId, media.albumId)) {
            return false;
        }
        if (!Objects.equals(mimeType, STRING_DEFAULT) && !media.mimeType.startsWith(mimeType)) {
            return false;
        }
        if (sizeBytes != LONG_DEFAULT && media.sizeBytes > sizeBytes) {
            return false;
        }

        return true;
    }

    public static MediaGenerator getMediaGenerator(String authority) {
        MediaGenerator generator = sMediaGeneratorMap.get(authority);
        if (generator == null) {
            generator = new MediaGenerator();
            sMediaGeneratorMap.put(authority, generator);
        }
        return generator;
    }
}
