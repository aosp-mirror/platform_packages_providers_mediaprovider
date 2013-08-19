/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsContract.RootColumns;
import android.provider.DocumentsContract.Roots;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.provider.MediaStore.Audio.Albums;
import android.provider.MediaStore.Audio.ArtistColumns;
import android.provider.MediaStore.Audio.Artists;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.text.format.DateUtils;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;

/**
 * Presents a {@link DocumentsContract} view of {@link MediaProvider} contents.
 */
public class MediaDocumentsProvider extends ContentProvider {
    private static final String AUTHORITY = "com.android.providers.media.documents";

    // TODO: enable once MediaStore can generate PFD thumbnails
    private static final boolean ENABLE_THUMBNAILS = false;

    private static final String ROOT_IMAGES = "images";
    private static final String ROOT_MUSIC = "music";

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_ROOTS = 1;
    private static final int URI_ROOTS_ID = 2;
    private static final int URI_DOCS_ID = 3;
    private static final int URI_DOCS_ID_CONTENTS = 4;

    static {
        sMatcher.addURI(AUTHORITY, "roots", URI_ROOTS);
        sMatcher.addURI(AUTHORITY, "roots/*", URI_ROOTS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*", URI_DOCS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*/contents", URI_DOCS_ID_CONTENTS);
    }

    private static final String[] ALL_ROOTS_COLUMNS = new String[] {
            RootColumns.ROOT_ID, RootColumns.ROOT_TYPE, RootColumns.ICON, RootColumns.TITLE,
            RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES
    };

    private static final String[] ALL_DOCUMENTS_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final ContentResolver resolver = getContext().getContentResolver();
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeImagesRoot(result);
                includeMusicRoot(result);
                return result;
            }
            case URI_ROOTS_ID: {
                final String rootId = DocumentsContract.getRootId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                if (ROOT_IMAGES.equals(rootId)) {
                    includeImagesRoot(result);
                } else if (ROOT_MUSIC.equals(rootId)) {
                    includeMusicRoot(result);
                }
                return result;
            }
            case URI_DOCS_ID: {
                final DocId docId = parseDocId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);

                final long token = Binder.clearCallingIdentity();
                Cursor cursor = null;
                try {
                    if (ROOT_IMAGES.equals(docId.root)) {
                        if (docId.type == null) {
                            includeRoot(result, docId.root);
                        } else if (TYPE_BUCKET.equals(docId.type)) {
                            // single bucket
                            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                                    BucketQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + docId.id,
                                    null, null);
                            if (cursor.moveToFirst()) {
                                includeBucket(result, cursor);
                            }
                        } else if (TYPE_ID.equals(docId.type)) {
                            // single image
                            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                                    ImageQuery.PROJECTION, BaseColumns._ID + "=" + docId.id, null,
                                    null);
                            if (cursor.moveToFirst()) {
                                includeImage(result, cursor);
                            }
                        }
                    } else if (ROOT_MUSIC.equals(docId.root)) {
                        if (docId.type == null) {
                            includeRoot(result, docId.root);
                        } else if (TYPE_ARTIST.equals(docId.type)) {
                            // single artist
                            cursor = resolver.query(Artists.EXTERNAL_CONTENT_URI,
                                    ArtistQuery.PROJECTION, BaseColumns._ID + "=" + docId.id, null,
                                    null);
                            if (cursor.moveToFirst()) {
                                includeArtist(result, cursor);
                            }
                        } else if (TYPE_ALBUM.equals(docId.type)) {
                            // single album
                            cursor = resolver.query(Albums.EXTERNAL_CONTENT_URI,
                                    AlbumQuery.PROJECTION, BaseColumns._ID + "=" + docId.id, null,
                                    null);
                            if (cursor.moveToFirst()) {
                                includeAlbum(result, cursor);
                            }
                        } else if (TYPE_ID.equals(docId.type)) {
                            // single song
                            cursor = resolver.query(Audio.Media.EXTERNAL_CONTENT_URI,
                                    SongQuery.PROJECTION, BaseColumns._ID + "=" + docId.id, null,
                                    null);
                            if (cursor.moveToFirst()) {
                                includeSong(result, cursor);
                            }
                        }
                    }
                } finally {
                    IoUtils.closeQuietly(cursor);
                    Binder.restoreCallingIdentity(token);
                }

                return result;
            }
            case URI_DOCS_ID_CONTENTS: {
                final DocId docId = parseDocId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);

                final long token = Binder.clearCallingIdentity();
                Cursor cursor = null;
                try {
                    if (ROOT_IMAGES.equals(docId.root)) {
                        if (docId.type == null) {
                            // include all unique buckets
                            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                                    BucketQuery.PROJECTION, null, null, ImageColumns.BUCKET_ID);
                            long lastId = Long.MIN_VALUE;
                            while (cursor.moveToNext()) {
                                final long id = cursor.getLong(
                                        cursor.getColumnIndexOrThrow(ImageColumns.BUCKET_ID));
                                if (lastId != id) {
                                    includeBucket(result, cursor);
                                    lastId = id;
                                }
                            }
                        } else if (TYPE_BUCKET.equals(docId.type)) {
                            // include images under bucket
                            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                                    ImageQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + docId.id,
                                    null, null);
                            while (cursor.moveToNext()) {
                                includeImage(result, cursor);
                            }
                        }
                    } else if (ROOT_MUSIC.equals(docId.root)) {
                        if (docId.type == null) {
                            // include all artists
                            cursor = resolver.query(Audio.Artists.EXTERNAL_CONTENT_URI,
                                    ArtistQuery.PROJECTION, null, null, null);
                            while (cursor.moveToNext()) {
                                includeArtist(result, cursor);
                            }
                        } else if (TYPE_ARTIST.equals(docId.type)) {
                            // include all albums under artist
                            cursor = resolver.query(Artists.Albums.getContentUri("external", docId.id),
                                    AlbumQuery.PROJECTION, null, null, null);
                            while (cursor.moveToNext()) {
                                includeAlbum(result, cursor);
                            }
                        } else if (TYPE_ALBUM.equals(docId.type)) {
                            // include all songs under album
                            cursor = resolver.query(Audio.Media.EXTERNAL_CONTENT_URI,
                                    SongQuery.PROJECTION, AudioColumns.ALBUM_ID + "=" + docId.id,
                                    null, null);
                            while (cursor.moveToNext()) {
                                includeSong(result, cursor);
                            }
                        }
                    }
                } finally {
                    IoUtils.closeQuietly(cursor);
                    Binder.restoreCallingIdentity(token);
                }

                return result;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private void includeImagesRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(RootColumns.ROOT_ID, ROOT_IMAGES);
        row.offer(RootColumns.ROOT_TYPE, Roots.ROOT_TYPE_SHORTCUT);
        row.offer(RootColumns.ICON, R.mipmap.ic_launcher_gallery);
        row.offer(RootColumns.TITLE, getContext().getString(R.string.root_images));
    }

    private void includeMusicRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(RootColumns.ROOT_ID, ROOT_MUSIC);
        row.offer(RootColumns.ROOT_TYPE, Roots.ROOT_TYPE_SHORTCUT);
        row.offer(RootColumns.TITLE, getContext().getString(R.string.root_music));
    }

    private static final String TYPE_BUCKET = "bucket";
    private static final String TYPE_ARTIST = "artist";
    private static final String TYPE_ALBUM = "album";
    private static final String TYPE_ID = "id";

    private static class DocId {
        public String root;
        public String type;
        public long id;
    }

    private static DocId parseDocId(Uri uri) {
        final DocId docId = new DocId();
        docId.root = DocumentsContract.getRootId(uri);

        final String raw = DocumentsContract.getDocId(uri);
        if (!Documents.DOC_ID_ROOT.equals(raw)) {
            final int split = raw.indexOf(':');
            docId.type = raw.substring(0, split);
            docId.id = Long.parseLong(raw.substring(split + 1));
        }

        return docId;
    }

    private static String buildDocId(String type, long id) {
        return type + ":" + id;
    }

    private void includeRoot(MatrixCursor result, String rootId) {
        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, Documents.DOC_ID_ROOT);
        if (ROOT_IMAGES.equals(rootId)) {
            row.offer(DocumentColumns.DISPLAY_NAME, getContext().getString(R.string.root_images));
            row.offer(DocumentColumns.FLAGS, Documents.FLAG_PREFERS_GRID);
        } else {
            row.offer(DocumentColumns.DISPLAY_NAME, getContext().getString(R.string.root_music));
        }
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private interface BucketQuery {
        final String[] PROJECTION = new String[] {
                ImageColumns.BUCKET_ID,
                ImageColumns.BUCKET_DISPLAY_NAME,
                ImageColumns.DATE_MODIFIED };

        final int BUCKET_ID = 0;
        final int BUCKET_DISPLAY_NAME = 1;
        final int DATE_MODIFIED = 2;
    }

    private void includeBucket(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(BucketQuery.BUCKET_ID);
        final String docId = buildDocId(TYPE_BUCKET, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(BucketQuery.BUCKET_DISPLAY_NAME));
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
        row.offer(DocumentColumns.LAST_MODIFIED,
                cursor.getLong(BucketQuery.DATE_MODIFIED) * DateUtils.SECOND_IN_MILLIS);
        row.offer(DocumentColumns.FLAGS,
                Documents.FLAG_PREFERS_GRID | Documents.FLAG_SUPPORTS_THUMBNAIL);
    }

    private interface ImageQuery {
        final String[] PROJECTION = new String[] {
                ImageColumns._ID,
                ImageColumns.DISPLAY_NAME,
                ImageColumns.MIME_TYPE,
                ImageColumns.SIZE,
                ImageColumns.DATE_MODIFIED };

        final int _ID = 0;
        final int DISPLAY_NAME = 1;
        final int MIME_TYPE = 2;
        final int SIZE = 3;
        final int DATE_MODIFIED = 4;
    }

    private void includeImage(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(ImageQuery._ID);
        final String docId = buildDocId(TYPE_ID, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(ImageQuery.DISPLAY_NAME));
        row.offer(DocumentColumns.SIZE, cursor.getLong(ImageQuery.SIZE));
        row.offer(DocumentColumns.MIME_TYPE, cursor.getString(ImageQuery.MIME_TYPE));
        row.offer(DocumentColumns.LAST_MODIFIED,
                cursor.getLong(ImageQuery.DATE_MODIFIED) * DateUtils.SECOND_IN_MILLIS);
        row.offer(DocumentColumns.FLAGS, Documents.FLAG_SUPPORTS_THUMBNAIL);
    }

    private interface ArtistQuery {
        final String[] PROJECTION = new String[] {
                BaseColumns._ID,
                ArtistColumns.ARTIST };

        final int _ID = 0;
        final int ARTIST = 1;
    }

    private void includeArtist(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(ArtistQuery._ID);
        final String docId = buildDocId(TYPE_ARTIST, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(ArtistQuery.ARTIST));
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private interface AlbumQuery {
        final String[] PROJECTION = new String[] {
                BaseColumns._ID,
                AlbumColumns.ALBUM };

        final int _ID = 0;
        final int ALBUM = 1;
    }

    private void includeAlbum(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(AlbumQuery._ID);
        final String docId = buildDocId(TYPE_ALBUM, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(AlbumQuery.ALBUM));
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private interface SongQuery {
        final String[] PROJECTION = new String[] {
                AudioColumns._ID,
                AudioColumns.TITLE,
                AudioColumns.MIME_TYPE,
                AudioColumns.SIZE,
                AudioColumns.DATE_MODIFIED };

        final int _ID = 0;
        final int TITLE = 1;
        final int MIME_TYPE = 2;
        final int SIZE = 3;
        final int DATE_MODIFIED = 4;
    }

    private void includeSong(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(SongQuery._ID);
        final String docId = buildDocId(TYPE_ALBUM, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(SongQuery.TITLE));
        row.offer(DocumentColumns.SIZE, cursor.getLong(SongQuery.SIZE));
        row.offer(DocumentColumns.MIME_TYPE, cursor.getString(SongQuery.MIME_TYPE));
        row.offer(DocumentColumns.LAST_MODIFIED,
                cursor.getLong(SongQuery.DATE_MODIFIED) * DateUtils.SECOND_IN_MILLIS);
    }

    private interface TypeQuery {
        final String[] PROJECTION = {
                DocumentColumns.MIME_TYPE };

        final int MIME_TYPE = 0;
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                return Roots.MIME_TYPE_DIR;
            }
            case URI_ROOTS_ID: {
                return Roots.MIME_TYPE_ITEM;
            }
            case URI_DOCS_ID: {
                final Cursor cursor = query(uri, TypeQuery.PROJECTION, null, null, null);
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getString(TypeQuery.MIME_TYPE);
                    } else {
                        return null;
                    }
                } finally {
                    IoUtils.closeQuietly(cursor);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final DocId docId = parseDocId(uri);

                if (!"r".equals(mode)) {
                    throw new IllegalArgumentException("Media is read-only");
                }

                final Uri target;
                if (ROOT_IMAGES.equals(docId.root) && TYPE_ID.equals(docId.type)) {
                    target = ContentUris.withAppendedId(
                            Images.Media.EXTERNAL_CONTENT_URI, docId.id);
                } else if (ROOT_MUSIC.equals(docId.root)) {
                    target = ContentUris.withAppendedId(
                            Audio.Media.EXTERNAL_CONTENT_URI, docId.id);
                } else {
                    throw new UnsupportedOperationException("Unsupported Uri " + uri);
                }

                // Delegate to real provider
                final long token = Binder.clearCallingIdentity();
                try {
                    return getContext().getContentResolver().openFileDescriptor(target, mode);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        // TODO: load optimized image thumbnails
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final DocId docId = parseDocId(uri);

                if (ROOT_IMAGES.equals(docId.root) && TYPE_BUCKET.equals(docId.type)) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        return openBucketThumbnail(docId.id);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } else {
                    return super.openTypedAssetFile(uri, mimeTypeFilter, opts);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private interface BucketThumbnailQuery {
        final String[] PROJECTION = new String[] {
                ImageColumns._ID,
                ImageColumns.BUCKET_ID,
                ImageColumns.DATE_MODIFIED };

        final int _ID = 0;
        final int BUCKET_ID = 1;
        final int DATE_MODIFIED = 2;
    }

    private AssetFileDescriptor openBucketThumbnail(long bucketId) throws FileNotFoundException {
        final ContentResolver resolver = getContext().getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                    BucketThumbnailQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + bucketId, null,
                    ImageColumns.DATE_MODIFIED + " DESC");
            if (cursor.moveToFirst()) {
                final long id = cursor.getLong(BucketThumbnailQuery._ID);
                final Uri uri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, id);
                return new AssetFileDescriptor(resolver.openFileDescriptor(uri, "r"), 0,
                        AssetFileDescriptor.UNKNOWN_LENGTH);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return null;
    }


    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }
}
