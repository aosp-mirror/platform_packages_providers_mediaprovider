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

import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.provider.CloudMediaProviderContract.MediaColumns;

import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_ARRAY_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.STRING_DEFAULT;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.text.TextUtils;

import com.android.providers.media.photopicker.LocalProvider;

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
    private static final String TAG = "PickerProviderMediaGenerator";
    private static final String[] MEDIA_PROJECTION = new String[] {
        MediaColumns.ID,
        MediaColumns.MEDIA_STORE_URI,
        MediaColumns.MIME_TYPE,
        MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
        MediaColumns.DATE_TAKEN_MILLIS,
        MediaColumns.SYNC_GENERATION,
        MediaColumns.SIZE_BYTES,
        MediaColumns.DURATION_MILLIS,
        MediaColumns.IS_FAVORITE,
    };
    private static final String[] ALBUM_MEDIA_PROJECTION = new String[] {
            MediaColumns.ID,
            MediaColumns.MEDIA_STORE_URI,
            MediaColumns.MIME_TYPE,
            MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            MediaColumns.DATE_TAKEN_MILLIS,
            MediaColumns.SYNC_GENERATION,
            MediaColumns.SIZE_BYTES,
            MediaColumns.DURATION_MILLIS,
    };

    private static final String[] ALBUM_PROJECTION = new String[] {
        AlbumColumns.ID,
        AlbumColumns.DATE_TAKEN_MILLIS,
        AlbumColumns.DISPLAY_NAME,
        AlbumColumns.MEDIA_COVER_ID,
        AlbumColumns.MEDIA_COUNT,
        AlbumColumns.AUTHORITY
    };

    private static final String[] DELETED_MEDIA_PROJECTION = new String[] { MediaColumns.ID };

    public static class MediaGenerator {
        private final List<TestMedia> mMedia = new ArrayList<>();
        private final List<TestMedia> mDeletedMedia = new ArrayList<>();
        private final List<TestAlbum> mAlbums = new ArrayList<>();
        private String mCollectionId;
        private long mLastSyncGeneration;
        private String mAccountName;
        private Intent mAccountConfigurationIntent;
        private int mCursorExtraQueryCount;
        private Bundle mCursorExtra;

        // TODO(b/214592293): Add pagination support for testing purposes.
        public Cursor getMedia(long generation, String albumId, String[] mimeTypes,
                long sizeBytes) {
            final Cursor cursor = getCursor(mMedia, generation, albumId, mimeTypes, sizeBytes,
                    /* isDeleted */ false);

            if (mCursorExtra != null) {
                cursor.setExtras(mCursorExtra);
            } else {
                cursor.setExtras(buildCursorExtras(mCollectionId, generation > 0, albumId != null));
            }

            if (--mCursorExtraQueryCount == 0) {
                clearCursorExtras();
            }
            return cursor;
        }

        public Cursor getAlbums(String[] mimeTypes, long sizeBytes, boolean isLocal) {
            final Cursor cursor = getCursor(mAlbums, mimeTypes, sizeBytes, isLocal);

            if (mCursorExtra != null) {
                cursor.setExtras(mCursorExtra);
            } else {
                cursor.setExtras(buildCursorExtras(mCollectionId, false, false));
            }

            if (--mCursorExtraQueryCount == 0) {
                clearCursorExtras();
            }
            return cursor;
        }

        // TODO(b/214592293): Add pagination support for testing purposes.
        public Cursor getDeletedMedia(long generation) {
            final Cursor cursor = getCursor(mDeletedMedia, generation, /* albumId */ STRING_DEFAULT,
                    STRING_ARRAY_DEFAULT, /* sizeBytes */ LONG_DEFAULT,
                    /* isDeleted */ true);

            if (mCursorExtra != null) {
                cursor.setExtras(mCursorExtra);
            } else {
                cursor.setExtras(buildCursorExtras(mCollectionId, generation > 0, false));
            }

            if (--mCursorExtraQueryCount == 0) {
                clearCursorExtras();
            }
            return cursor;
        }

        public Bundle getMediaCollectionInfo() {
            Bundle bundle = new Bundle();
            bundle.putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, mCollectionId);
            bundle.putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, mLastSyncGeneration);
            bundle.putString(MediaCollectionInfo.ACCOUNT_NAME, mAccountName);
            bundle.putParcelable(MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT,
                    mAccountConfigurationIntent);

            return bundle;
        }

        public void setAccountInfo(String accountName, Intent configIntent) {
            mAccountName = accountName;
            mAccountConfigurationIntent = configIntent;
        }

        public void clearCursorExtras() {
            mCursorExtra = null;
        }

        public void setNextCursorExtras(int queryCount, String mediaCollectionId,
                boolean honoredSyncGeneration, boolean honoredAlbumId) {
            mCursorExtraQueryCount = queryCount;
            mCursorExtra = buildCursorExtras(mediaCollectionId, honoredSyncGeneration,
                    honoredAlbumId);
        }

        public Bundle buildCursorExtras(String mediaCollectionId, boolean honoredSyncGeneration,
                boolean honoredAlbumdId) {
            final ArrayList<String> honoredArgs = new ArrayList<>();
            if (honoredSyncGeneration) {
                honoredArgs.add(EXTRA_SYNC_GENERATION);
            }
            if (honoredAlbumdId) {
                honoredArgs.add(EXTRA_ALBUM_ID);
            }

            final Bundle bundle = new Bundle();
            bundle.putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID,
                    mediaCollectionId);
            bundle.putStringArrayList(ContentResolver.EXTRA_HONORED_ARGS, honoredArgs);

            return bundle;
        }

        public void addMedia(String localId, String cloudId) {
            mDeletedMedia.remove(createPlaceholderMedia(localId, cloudId));
            mMedia.add(0, createTestMedia(localId, cloudId));
        }

        public void addAlbumMedia(String localId, String cloudId, String albumId) {
            mMedia.add(0, createTestAlbumMedia(localId, cloudId, albumId));
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
            clearCursorExtras();
        }

        public void setMediaCollectionId(String id) {
            mCollectionId = id;
        }

        public long getCount() {
            return mMedia.size();
        }

        private TestAlbum createTestAlbum(String id) {
            return new TestAlbum(id, mMedia);
        }

        private TestMedia createTestMedia(String localId, String cloudId) {
            // Increase generation
            return new TestMedia(localId, cloudId, ++mLastSyncGeneration);
        }
        private TestMedia createTestAlbumMedia(String localId, String cloudId, String albumId) {
            // Increase generation
            return new TestMedia(localId, cloudId, albumId);
        }

        private TestMedia createTestMedia(String localId, String cloudId, String albumId,
                String mimeType, int standardMimeTypeExtension, long sizeBytes,
                boolean isFavorite) {
            // Increase generation
            return new TestMedia(localId, cloudId, albumId, mimeType, standardMimeTypeExtension,
                    sizeBytes, /* durationMs */ 0, ++mLastSyncGeneration, isFavorite);
        }

        private static TestMedia createPlaceholderMedia(String localId, String cloudId) {
            // Don't increase generation. Used to create a throw-away element used for removal from
            // |mMedia| or |mDeletedMedia|
            return new TestMedia(localId, cloudId, 0);
        }

        private static Cursor getCursor(List<TestMedia> mediaList, long generation,
                String albumId, String[] mimeTypes, long sizeBytes, boolean isDeleted) {
            final MatrixCursor matrix;
            if (isDeleted) {
                matrix = new MatrixCursor(DELETED_MEDIA_PROJECTION);
            } else if(!TextUtils.isEmpty(albumId)) {
                matrix = new MatrixCursor(ALBUM_MEDIA_PROJECTION);
            } else {
                matrix = new MatrixCursor(MEDIA_PROJECTION);
            }

            for (TestMedia media : mediaList) {
                if (!TextUtils.isEmpty(albumId) && matchesFilter(media,
                        albumId, mimeTypes, sizeBytes)) {
                    matrix.addRow(media.toAlbumMediaArray());
                } else if (media.generation > generation
                        && matchesFilter(media, albumId, mimeTypes, sizeBytes)) {
                    matrix.addRow(media.toArray(isDeleted));
                }
            }
            return matrix;
        }

        private static Cursor getCursor(List<TestAlbum> albumList, String[] mimeTypes,
                long sizeBytes, boolean isLocal) {
            final MatrixCursor matrix = new MatrixCursor(ALBUM_PROJECTION);

            for (TestAlbum album : albumList) {
                final String[] res = album.toArray(mimeTypes, sizeBytes, isLocal);
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


        public TestMedia(String localId, String cloudId, String albumId) {
            this(localId, cloudId, /* albumId */ albumId, "image/jpeg",
                    /* standardMimeTypeExtension */ MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE,
                    /* sizeBytes */ 4096, /* durationMs */ 0, 0,
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

        public String[] toAlbumMediaArray() {
            return new String[] {
                    getId(),
                    localId == null ? null : "content://media/external/files/" + localId,
                    mimeType,
                    String.valueOf(standardMimeTypeExtension),
                    String.valueOf(dateTakenMs),
                    String.valueOf(generation),
                    String.valueOf(sizeBytes),
                    String.valueOf(durationMs)
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

        public String[] toArray(String[] mimeTypes, long sizeBytes, boolean isLocal) {
            long mediaCount = 0;
            String mediaCoverId = null;
            long dateTakenMs = 0;

            for (TestMedia m : media) {
                if (matchesFilter(m, id, mimeTypes, sizeBytes)) {
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
                String.valueOf(dateTakenMs),
                /* displayName */ id,
                mediaCoverId,
                String.valueOf(mediaCount),
                isLocal ? LocalProvider.AUTHORITY : null
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

    private static boolean matchesFilter(TestMedia media, String albumId, String[] mimeTypes,
            long sizeBytes) {
        if (!Objects.equals(albumId, STRING_DEFAULT) && !Objects.equals(albumId, media.albumId)) {
            return false;
        }

        if (mimeTypes != null) {
            boolean matchesMimeType = false;
            for (String m : mimeTypes) {
                if (m != null && media.mimeType.startsWith(m)) {
                    matchesMimeType = true;
                    break;
                }
            }

            if (!matchesMimeType) {
                return false;
            }
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
