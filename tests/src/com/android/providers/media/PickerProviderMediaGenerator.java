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

import static android.provider.CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MS;
import static android.provider.CloudMediaProviderContract.MediaColumns.DURATION_MS;
import static android.provider.CloudMediaProviderContract.MediaColumns.ID;
import static android.provider.CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI;
import static android.provider.CloudMediaProviderContract.MediaColumns.MIME_TYPE;
import static android.provider.CloudMediaProviderContract.MediaColumns.SIZE_BYTES;
import static android.provider.CloudMediaProviderContract.MediaColumns;
import static android.provider.CloudMediaProviderContract.MediaInfo;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.CloudMediaProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates {@link TestMedia} items that can be accessed via test {@link CloudMediaProvider}
 * instances.
 */
public class PickerProviderMediaGenerator {
    private static final Map<String, MediaGenerator> sMediaGeneratorMap = new HashMap<>();
    private static final String[] MEDIA_PROJECTION = new String[] {
        ID,
        MEDIA_STORE_URI,
        MIME_TYPE,
        DATE_TAKEN_MS,
        SIZE_BYTES,
        DURATION_MS,
    };

    private static final String[] DELETED_MEDIA_PROJECTION = new String[] { ID };

    public static class MediaGenerator {
        private final Set<TestMedia> mMedia = new HashSet<>();
        private final Set<TestMedia> mDeletedMedia = new HashSet<>();
        private String mVersion;
        private long mGeneration;

        public Cursor getMedia(long generation) {
            return getCursor(mMedia, generation, /* isDeleted */ false);
        }

        public Cursor getDeletedMedia(long generation) {
            return getCursor(mDeletedMedia, generation, /* isDeleted */ true);
        }

        public void addMedia(String localId, String cloudId) {
            mDeletedMedia.remove(createPlaceholderMedia(localId, cloudId));
            mMedia.add(createTestMedia(localId, cloudId));
        }

        public void deleteMedia(String localId, String cloudId) {
            if (mMedia.remove(createPlaceholderMedia(localId, cloudId))) {
                mDeletedMedia.add(createTestMedia(localId, cloudId));
            }
        }

        public void resetAll() {
            mMedia.clear();
            mDeletedMedia.clear();
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

        private TestMedia createTestMedia(String localId, String cloudId) {
            // Increase generation
            return new TestMedia(localId, cloudId, ++mGeneration);
        }

        private static TestMedia createPlaceholderMedia(String localId, String cloudId) {
            // Don't increase generation. Used to create a throw-away element used for removal from
            // |mMedia| or |mDeletedMedia|
            return new TestMedia(localId, cloudId, 0);
        }

        private static Cursor getCursor(Set<TestMedia> mediaSet, long generation,
                boolean isDeleted) {
            final MatrixCursor matrix;
            if (isDeleted) {
                matrix = new MatrixCursor(DELETED_MEDIA_PROJECTION);
            } else {
                matrix = new MatrixCursor(MEDIA_PROJECTION);
            }

            Set<TestMedia> result = new HashSet<>();
            for (TestMedia media : mediaSet) {
                if (media.generation > generation) {
                    matrix.addRow(media.toArray(isDeleted));
                }
            }
            return matrix;
        }
    }

    private static class TestMedia {
        public final String localId;
        public final String cloudId;
        public final long dateTakenMs;
        public final long generation;

        public TestMedia(String localId, String cloudId, long generation) {
            this.localId = localId;
            this.cloudId = cloudId;
            this.dateTakenMs = System.currentTimeMillis();
            this.generation = generation;
        }

        public String[] toArray(boolean isDeleted) {
            if (isDeleted) {
                return new String[] {getId()};
            }

            return new String[] {
                getId(),
                localId == null ? null : "content://media/external/files/" + localId,
                "image/jpeg",
                String.valueOf(dateTakenMs),
                /* size_bytes */ String.valueOf(4096),
                /* duration_ms */ String.valueOf(0)
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

    public static MediaGenerator getMediaGenerator(String authority) {
        MediaGenerator generator = sMediaGeneratorMap.get(authority);
        if (generator == null) {
            generator = new MediaGenerator();
            sMediaGeneratorMap.put(authority, generator);
        }
        return generator;
    }
}
