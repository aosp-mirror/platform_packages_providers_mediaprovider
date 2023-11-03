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

package com.android.providers.media.photopicker.data.glide;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Custom {@link DataFetcher} to fetch a {@link InputStream} for a thumbnail from a cloud media
 * provider.
 */
public class PickerThumbnailFetcher implements DataFetcher<InputStream> {

    private static final String TAG = "PickerThumbnailFetcher";
    private final Context mContext;
    private final GlideLoadable mModel;
    private final int mWidth;
    private final int mHeight;
    private final boolean mIsThumbRequest;
    private final CancellationSignal mCancellationSignal;
    @Nullable private AssetFileDescriptor mAssetFileDescriptor = null;
    @Nullable private InputStream mInputStream = null;

    PickerThumbnailFetcher(
            Context context, GlideLoadable model, int width, int height, boolean isThumbRequest) {
        mContext = context;
        mModel = model;
        mWidth = width;
        mHeight = height;
        mIsThumbRequest = isThumbRequest;
        mCancellationSignal = new CancellationSignal();
    }

    @Override
    public void loadData(Priority priority, DataCallback<? super InputStream> callback) {
        ContentResolver contentResolver = mContext.getContentResolver();
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, new Point(mWidth, mHeight));
        opts.putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true);

        if (mIsThumbRequest) {
            opts.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true);
        }

        try {
            // Do not close the afd or InputStream as it will close the input stream. The
            // afd needs to be closed when cleanup is called, so save a reference so it can
            // be closed when Glide is done with it.
            mAssetFileDescriptor =
                    contentResolver.openTypedAssetFileDescriptor(
                            mModel.getLoadableUri(),
                            /* mimeType= */ "image/*",
                            opts,
                            /* cancellationSignal= */ mCancellationSignal);
            if (mAssetFileDescriptor == null) {
                final String err = "Failed to load data for " + mModel;
                callback.onLoadFailed(new FileNotFoundException(err));
                return;
            }
            mInputStream = mAssetFileDescriptor.createInputStream();
            callback.onDataReady(mInputStream);
        } catch (IOException e) {
            callback.onLoadFailed(e);
        }
    }

    /**
     * Cleanup is called after Glide is done with this Fetcher instance, and it is now safe to close
     * the remembered AssetFileDescriptor.
     */
    @Override
    public void cleanup() {
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }

            if (mAssetFileDescriptor != null) {
                mAssetFileDescriptor.close();
            }
        } catch (IOException e) {
            Log.d(TAG, "Unexpected error during thumbnail request cleanup.", e);
        }
    }

    @Override
    public void cancel() {
        mCancellationSignal.cancel();
    }

    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @Override
    public DataSource getDataSource() {
        // If the authority belongs to MediaProvider, we can consider this a local load.
        if (mModel.getLoadableUri().getAuthority().equals(MediaStore.AUTHORITY)) {
            return DataSource.LOCAL;
        } else {
            // Otherwise, let's assume it's a Remote data source so that Glide will cache
            // the raw return value rather than manipulated bytes.
            return DataSource.REMOTE;
        }
    }
}
