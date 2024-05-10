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

package com.android.providers.media.photopicker.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Data class that holds identity of a {@link android.provider.CloudMediaProvider}
 */
public final class CloudProviderInfo {
    public static final CloudProviderInfo EMPTY = new CloudProviderInfo();

    public final String authority;
    public final String packageName;
    public final int uid;

    private CloudProviderInfo() {
        this.authority = null;
        this.packageName = null;
        this.uid = -1;
    }

    public CloudProviderInfo(@NonNull String authority, @NonNull String packageName, int uid) {
        Objects.requireNonNull(authority);
        Objects.requireNonNull(packageName);

        this.authority = authority;
        this.packageName = packageName;
        this.uid = uid;
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }

    /**
     * Check if the {@link android.provider.CloudMediaProvider} belongs to the given package.
     * Note that this method will <b>always<b/> return {@code false} when {@param packageName} is
     * {@code null} and/or when {@code this} instance is {@link #EMPTY}.
     *
     * @return {@code true} if {@param packageName} is not {@code null} and matches the
     *         {@link #packageName}, otherwise {@code false}.
     */
    public boolean matches(@Nullable String packageName) {
        return packageName != null && packageName.equals(this.packageName);
    }

    /**
     * Check if the {@link android.provider.CloudMediaProvider} belongs to the given package and
     * declares the given authority.
     * Note that this method will <b>always<b/> return {@code false} if either {@code authority} or
     * {@code packageName} is {@code null} and/or when {@code this} instance is {@link #EMPTY}.
     *
     * @return {@code true} if both {@code authority} and {@code packageName} are not {@code null}
     *         and match the {@link #authority} and {@link #packageName} respectively, otherwise
     *         {@code false}.
     */
    public boolean matches(@Nullable String authority, @Nullable String packageName) {
        return authority != null && authority.equals(this.authority) && matches(packageName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        CloudProviderInfo that = (CloudProviderInfo) obj;

        return Objects.equals(authority, that.authority)
                && Objects.equals(packageName, that.packageName)
                && uid == that.uid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(authority, packageName, uid);
    }

    @Override
    public String toString() {
        return "CloudProviderInfo{"
                + "authority='" + authority + '\''
                + ", pkg='" + packageName + '\''
                + ", uid=" + uid
                + '}';
    }

    /** Returns a short string representation of the object. */
    public String toShortString() {
        if (isEmpty()) {
            return "-";
        }
        return "pkg: " + packageName + " / auth: " + authority;
    }
}
