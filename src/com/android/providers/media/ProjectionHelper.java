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

import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Utility class to handle projection columns across releases, database agnostic.
 */
public class ProjectionHelper {

    private static final String TAG = "ProjectionHelper";
    @Nullable
    private final Class<? extends Annotation> mColumnAnnotation;
    @Nullable
    private final Class<? extends Annotation> mExportedSinceAnnotation;

    public ProjectionHelper(@Nullable Class<? extends Annotation> columnAnnotation,
            @Nullable Class<? extends Annotation> exportedSinceAnnotation) {
        mColumnAnnotation = columnAnnotation;
        mExportedSinceAnnotation = exportedSinceAnnotation;
    }

    @GuardedBy("mProjectionMapCache")
    private final ArrayMap<Class<?>, ArrayMap<String, String>>
            mProjectionMapCache = new ArrayMap<>();

    /**
     * Return a projection map that represents the valid columns that can be
     * queried the given contract class. The mapping is built automatically
     * using the {@link android.provider.Column} annotation, and is designed to
     * ensure that we always support public API commitments.
     */
    public ArrayMap<String, String> getProjectionMap(Class<?>... clazzes) {
        ArrayMap<String, String> result = new ArrayMap<>();
        synchronized (mProjectionMapCache) {
            for (Class<?> clazz : clazzes) {
                ArrayMap<String, String> map = mProjectionMapCache.get(clazz);
                if (map == null) {
                    map = new ArrayMap<>();
                    try {
                        for (Field field : clazz.getFields()) {
                            if (Objects.equals(field.getName(), "_ID") || (mColumnAnnotation != null
                                    && field.isAnnotationPresent(mColumnAnnotation))) {
                                boolean shouldIgnoreByOsVersion = shouldBeIgnoredByOsVersion(field);
                                if (!shouldIgnoreByOsVersion) {
                                    final String column = (String) field.get(null);
                                    map.put(column, column);
                                }
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                    mProjectionMapCache.put(clazz, map);
                }
                result.putAll(map);
            }
            return result;
        }
    }

    private boolean shouldBeIgnoredByOsVersion(@NonNull Field field) {
        if (mExportedSinceAnnotation == null) {
            return false;
        }

        if (!field.isAnnotationPresent(mExportedSinceAnnotation)) {
            return false;
        }

        try {
            final Annotation annotation = field.getAnnotation(mExportedSinceAnnotation);
            final int exportedSinceOSVersion = (int) annotation.annotationType().getMethod(
                    "osVersion").invoke(annotation);
            final boolean shouldIgnore = exportedSinceOSVersion > Build.VERSION.SDK_INT;
            if (shouldIgnore) {
                Log.d(TAG, "Ignoring column " + field.get(null) + " with version "
                        + exportedSinceOSVersion + " in OS version " + Build.VERSION.SDK_INT);
            }
            return shouldIgnore;
        } catch (Exception e) {
            Log.e(TAG, "Can't parse the OS version in ExportedSince annotation", e);
            return false;
        }
    }

    /**
     * @return whether a column annotation has been defined for the helper.
     */
    public boolean hasColumnAnnotation() {
        return mColumnAnnotation != null;
    }
}
