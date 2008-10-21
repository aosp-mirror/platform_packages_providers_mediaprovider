/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.database.AbstractCursor;
import android.database.CursorWindow;
import android.provider.MediaStore;


/**
 * Cursor for querying media scanner status.
 */
class MediaScannerCursor extends AbstractCursor {

    private static final String[] kColumnNames = {
        MediaStore.MEDIA_SCANNER_VOLUME
    };

    private String mVolumeName;

    MediaScannerCursor(String volumeName) {
        mVolumeName = volumeName;
    }

    @Override
    public int getCount() {
        return 1;
    }

    @Override
    public String[] getColumnNames() {
        return kColumnNames;
    }

    @Override
    public String getString(int column) {
        if (column == 0) {
            return mVolumeName;
        } else {
            return null;
        }
    }

    @Override
    public short getShort(int column) {
        return -1;
    }

    @Override
    public int getInt(int column) {
        return -1;
    }

    @Override
    public long getLong(int column) {
        return -1;
    }

    @Override
    public float getFloat(int column) {
        return -1;
    }
    
    @Override
    public double getDouble(int column) {
        return -1;
    }

    @Override
    public boolean isNull(int column) {
        return (column != 0);
    }

}
