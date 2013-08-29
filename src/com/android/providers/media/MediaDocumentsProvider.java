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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.DocumentRoot;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsProvider;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.provider.MediaStore.Audio.Albums;
import android.provider.MediaStore.Audio.ArtistColumns;
import android.provider.MediaStore.Audio.Artists;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.text.format.DateUtils;

import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Presents a {@link DocumentsContract} view of {@link MediaProvider} contents.
 */
public class MediaDocumentsProvider extends DocumentsProvider {

    private static final String[] SUPPORTED_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_BUCKET = "bucket";

    private static final String TYPE_AUDIO = "audio";
    private static final String TYPE_ARTIST = "artist";
    private static final String TYPE_ALBUM = "album";

    private DocumentRoot mImagesRoot;
    private DocumentRoot mAudioRoot;

    private List<DocumentRoot> mRoots;

    @Override
    public boolean onCreate() {
        mRoots = Lists.newArrayList();

        mImagesRoot = new DocumentRoot();
        mImagesRoot.docId = TYPE_IMAGE;
        mImagesRoot.rootType = DocumentRoot.ROOT_TYPE_SHORTCUT;
        mImagesRoot.title = getContext().getString(R.string.root_images);
        mImagesRoot.icon = R.mipmap.ic_launcher_gallery;
        mImagesRoot.flags = DocumentRoot.FLAG_LOCAL_ONLY;
        mRoots.add(mImagesRoot);

        mAudioRoot = new DocumentRoot();
        mAudioRoot.docId = TYPE_AUDIO;
        mAudioRoot.rootType = DocumentRoot.ROOT_TYPE_SHORTCUT;
        mAudioRoot.title = getContext().getString(R.string.root_audio);
        mAudioRoot.icon = R.drawable.ic_search_category_music_song;
        mAudioRoot.flags = DocumentRoot.FLAG_LOCAL_ONLY;
        mRoots.add(mAudioRoot);

        return true;
    }

    private static class Ident {
        public String type;
        public long id;
    }

    private static Ident getIdentForDocId(String docId) {
        final Ident ident = new Ident();
        final int split = docId.indexOf(':');
        if (split == -1) {
            ident.type = docId;
            ident.id = -1;
        } else {
            ident.type = docId.substring(0, split);
            ident.id = Long.parseLong(docId.substring(split + 1));
        }
        return ident;
    }

    private static String getDocIdForIdent(String type, long id) {
        return type + ":" + id;
    }

    @Override
    public List<DocumentRoot> getDocumentRoots() {
        return mRoots;
    }

    @Override
    public Cursor queryDocument(String docId) throws FileNotFoundException {
        final ContentResolver resolver = getContext().getContentResolver();
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);
        final Ident ident = getIdentForDocId(docId);

        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (TYPE_IMAGE.equals(ident.type) && ident.id == -1) {
                // single root
                includeImagesRoot(result);
            } else if (TYPE_BUCKET.equals(ident.type)) {
                // single bucket
                cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                        BucketQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + ident.id,
                        null, null);
                if (cursor.moveToFirst()) {
                    includeBucket(result, cursor);
                }
            } else if (TYPE_IMAGE.equals(ident.type)) {
                // single image
                cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                        ImageQuery.PROJECTION, BaseColumns._ID + "=" + ident.id, null,
                        null);
                if (cursor.moveToFirst()) {
                    includeImage(result, cursor);
                }
            } else if (TYPE_AUDIO.equals(ident.type) && ident.id == -1) {
                // single root
                includeAudioRoot(result);
            } else if (TYPE_ARTIST.equals(ident.type)) {
                // single artist
                cursor = resolver.query(Artists.EXTERNAL_CONTENT_URI,
                        ArtistQuery.PROJECTION, BaseColumns._ID + "=" + ident.id, null,
                        null);
                if (cursor.moveToFirst()) {
                    includeArtist(result, cursor);
                }
            } else if (TYPE_ALBUM.equals(ident.type)) {
                // single album
                cursor = resolver.query(Albums.EXTERNAL_CONTENT_URI,
                        AlbumQuery.PROJECTION, BaseColumns._ID + "=" + ident.id, null,
                        null);
                if (cursor.moveToFirst()) {
                    includeAlbum(result, cursor);
                }
            } else if (TYPE_AUDIO.equals(ident.type)) {
                // single song
                cursor = resolver.query(Audio.Media.EXTERNAL_CONTENT_URI,
                        SongQuery.PROJECTION, BaseColumns._ID + "=" + ident.id, null,
                        null);
                if (cursor.moveToFirst()) {
                    includeAudio(result, cursor);
                }
            } else {
                throw new UnsupportedOperationException("Unsupported document " + docId);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }

    @Override
    public Cursor queryDocumentChildren(String docId) throws FileNotFoundException {
        final ContentResolver resolver = getContext().getContentResolver();
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);
        final Ident ident = getIdentForDocId(docId);

        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            if (TYPE_IMAGE.equals(ident.type) && ident.id == -1) {
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
            } else if (TYPE_BUCKET.equals(ident.type)) {
                // include images under bucket
                cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                        ImageQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + ident.id,
                        null, null);
                while (cursor.moveToNext()) {
                    includeImage(result, cursor);
                }
            } else if (TYPE_AUDIO.equals(ident.type) && ident.id == -1) {
                // include all artists
                cursor = resolver.query(Audio.Artists.EXTERNAL_CONTENT_URI,
                        ArtistQuery.PROJECTION, null, null, null);
                while (cursor.moveToNext()) {
                    includeArtist(result, cursor);
                }
            } else if (TYPE_ARTIST.equals(ident.type)) {
                // include all albums under artist
                cursor = resolver.query(Artists.Albums.getContentUri("external", ident.id),
                        AlbumQuery.PROJECTION, null, null, null);
                while (cursor.moveToNext()) {
                    includeAlbum(result, cursor);
                }
            } else if (TYPE_ALBUM.equals(ident.type)) {
                // include all songs under album
                cursor = resolver.query(Audio.Media.EXTERNAL_CONTENT_URI,
                        SongQuery.PROJECTION, AudioColumns.ALBUM_ID + "=" + ident.id,
                        null, null);
                while (cursor.moveToNext()) {
                    includeAudio(result, cursor);
                }
            } else {
                throw new UnsupportedOperationException("Unsupported document " + docId);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final Ident ident = getIdentForDocId(docId);

        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Media is read-only");
        }

        final Uri target;
        if (TYPE_IMAGE.equals(ident.type) && ident.id != -1) {
            target = ContentUris.withAppendedId(
                    Images.Media.EXTERNAL_CONTENT_URI, ident.id);
        } else if (TYPE_AUDIO.equals(ident.type) && ident.id != -1) {
            target = ContentUris.withAppendedId(
                    Audio.Media.EXTERNAL_CONTENT_URI, ident.id);
        } else {
            throw new UnsupportedOperationException("Unsupported document " + docId);
        }

        // Delegate to real provider
        final long token = Binder.clearCallingIdentity();
        try {
            return getContext().getContentResolver().openFileDescriptor(target, mode);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final Ident ident = getIdentForDocId(docId);

        final long token = Binder.clearCallingIdentity();
        try {
            if (TYPE_BUCKET.equals(ident.type)) {
                return new AssetFileDescriptor(
                        openBucketThumbnail(ident.id), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            } else if (TYPE_IMAGE.equals(ident.type) && ident.id != -1) {
                // TODO: load optimized thumbnail
                return new AssetFileDescriptor(
                        openDocument(docId, "r", signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
            } else {
                throw new UnsupportedOperationException("Unsupported document " + docId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void includeImagesRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, TYPE_IMAGE);
        row.offer(DocumentColumns.DISPLAY_NAME, mImagesRoot.title);
        row.offer(DocumentColumns.FLAGS, Documents.FLAG_PREFERS_GRID);
        row.offer(DocumentColumns.MIME_TYPE, Documents.MIME_TYPE_DIR);
    }

    private void includeAudioRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, TYPE_AUDIO);
        row.offer(DocumentColumns.DISPLAY_NAME, mAudioRoot.title);
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
        final String docId = getDocIdForIdent(TYPE_BUCKET, id);

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
        final String docId = getDocIdForIdent(TYPE_IMAGE, id);

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
        final String docId = getDocIdForIdent(TYPE_ARTIST, id);

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
        final String docId = getDocIdForIdent(TYPE_ALBUM, id);

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

    private void includeAudio(MatrixCursor result, Cursor cursor) {
        final long id = cursor.getLong(SongQuery._ID);
        final String docId = getDocIdForIdent(TYPE_AUDIO, id);

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, cursor.getString(SongQuery.TITLE));
        row.offer(DocumentColumns.SIZE, cursor.getLong(SongQuery.SIZE));
        row.offer(DocumentColumns.MIME_TYPE, cursor.getString(SongQuery.MIME_TYPE));
        row.offer(DocumentColumns.LAST_MODIFIED,
                cursor.getLong(SongQuery.DATE_MODIFIED) * DateUtils.SECOND_IN_MILLIS);
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

    private ParcelFileDescriptor openBucketThumbnail(long bucketId) throws FileNotFoundException {
        final ContentResolver resolver = getContext().getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(Images.Media.EXTERNAL_CONTENT_URI,
                    BucketThumbnailQuery.PROJECTION, ImageColumns.BUCKET_ID + "=" + bucketId, null,
                    ImageColumns.DATE_MODIFIED + " DESC");
            if (cursor.moveToFirst()) {
                final long id = cursor.getLong(BucketThumbnailQuery._ID);
                final Uri uri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, id);
                return resolver.openFileDescriptor(uri, "r");
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return null;
    }
}
