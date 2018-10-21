/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.database.CharArrayBuffer;
import android.database.CrossProcessCursorWrapper;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.util.SparseArray;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Cursor that offers to translate values of requested columns.
 *
 * @hide
 */
public class TranslatingCursor extends CrossProcessCursorWrapper {
    private final SparseArray<UnaryOperator<String>> mTranslations;

    private TranslatingCursor(@NonNull Cursor cursor,
            SparseArray<UnaryOperator<String>> translations) {
        super(cursor);
        mTranslations = translations;
    }

    /**
     * Create a wrapped instance of the given {@link Cursor} which translates
     * the requested columns so they always return specific values when
     * accessed.
     * <p>
     * If a translated column appears multiple times in the underlying cursor,
     * all instances will be translated. If none of the translated columns
     * appear in the given cursor, the given cursor will be returned untouched
     * to improve performance.
     */
    public static Cursor create(@NonNull Cursor cursor,
            @NonNull Map<String, UnaryOperator<String>> translations) {
        final SparseArray<UnaryOperator<String>> internalTranslations = new SparseArray<>();

        final String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            if (translations.containsKey(columns[i])) {
                internalTranslations.put(i, translations.get(columns[i]));
            }
        }

        if (internalTranslations.size() == 0) {
            return cursor;
        } else {
            return new TranslatingCursor(cursor, internalTranslations);
        }
    }

    @Override
    public void fillWindow(int position, CursorWindow window) {
        // Fill window directly to ensure data is rewritten
        DatabaseUtils.cursorFillWindow(this, position, window);
    }

    @Override
    public CursorWindow getWindow() {
        // Returning underlying window risks leaking data
        return null;
    }

    @Override
    public Cursor getWrappedCursor() {
        throw new UnsupportedOperationException(
                "Returning underlying cursor risks leaking data");
    }

    @Override
    public double getDouble(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getDouble(columnIndex);
        }
    }

    @Override
    public float getFloat(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getFloat(columnIndex);
        }
    }

    @Override
    public int getInt(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getInt(columnIndex);
        }
    }

    @Override
    public long getLong(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getLong(columnIndex);
        }
    }

    @Override
    public short getShort(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getShort(columnIndex);
        }
    }

    @Override
    public String getString(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            return mTranslations.valueAt(i).apply(super.getString(columnIndex));
        } else {
            return super.getString(columnIndex);
        }
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            super.copyStringToBuffer(columnIndex, buffer);
        }
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            throw new IllegalStateException();
        } else {
            return super.getBlob(columnIndex);
        }
    }

    @Override
    public int getType(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            return Cursor.FIELD_TYPE_STRING;
        } else {
            return super.getType(columnIndex);
        }
    }

    @Override
    public boolean isNull(int columnIndex) {
        final int i = mTranslations.indexOfKey(columnIndex);
        if (i >= 0) {
            return getString(columnIndex) == null;
        } else {
            return super.isNull(columnIndex);
        }
    }
}
