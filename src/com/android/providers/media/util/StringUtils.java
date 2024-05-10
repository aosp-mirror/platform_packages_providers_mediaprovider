/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import static com.android.providers.media.util.Logging.TAG;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.icu.text.MessageFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class StringUtils {

  /**
   * Returns the formatted ICU format string corresponding to the provided resource ID and count
   * number of entities in the plural string.
   */
  public static String getICUFormatString(Resources resources, int count, int resourceID) {
    MessageFormat msgFormat = new MessageFormat(
        resources.getString(resourceID),
        Locale.getDefault());
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("count", count);
    return msgFormat.format(arguments);
  }

  public static String getStringConfig(Context context, int resId) {
      final Resources res = context.getResources();
      try {
          return res.getString(resId);
      } catch (NotFoundException e) {
          return null;
      }
  }

  /**
   * Variant of {@link String#startsWith(String)} but which tests with
   * case-insensitivity.
   */
  public static boolean startsWithIgnoreCase(@Nullable String target, @Nullable String other) {
    if (target == null || other == null) return false;
    if (other.length() > target.length()) return false;
    return target.regionMatches(true, 0, other, 0, other.length());
  }

  /**
   * Variant of {@link Objects#equal(Object, Object)} but which tests with
   * case-insensitivity.
   */
  public static boolean equalIgnoreCase(@Nullable String a, @Nullable String b) {
      return (a != null) && a.equalsIgnoreCase(b);
  }

  /**
   * Returns a string array config as a {@code List<String>}.
   */
  public static List<String> getStringArrayConfig(Context context, int resId) {
      final Resources res = context.getResources();
      try {
          final String[] configValue = res.getStringArray(resId);
          return Arrays.asList(configValue);
      } catch (NotFoundException e) {
          return new ArrayList<String>();
      }
  }

  /**
   * Returns the list of uncached relative paths after removing invalid ones.
   */
  public static List<String> verifySupportedUncachedRelativePaths(List<String> unverifiedPaths) {
      final List<String> verifiedPaths = new ArrayList<>();
      for (final String path : unverifiedPaths) {
          if (path == null) {
              continue;
          }
          if (path.startsWith("/")) {
              Log.w(TAG, "Relative path config must not start with '/'. Ignoring: " + path);
              continue;
          }
          if (!path.endsWith("/")) {
              Log.w(TAG, "Relative path config must end with '/'. Ignoring: " + path);
              continue;
          }

          verifiedPaths.add(path);
      }

      return verifiedPaths;
  }

    /**
     * Returns string description of {@link PackageManager}-s component state.
     */
    @NonNull
    public static String componentStateToString(int componentState) {
        final String componentStateAsString;
        switch (componentState) {
            case COMPONENT_ENABLED_STATE_DISABLED:
                componentStateAsString = "STATE_DISABLED";
                break;
            case COMPONENT_ENABLED_STATE_ENABLED:
                componentStateAsString = "STATE_ENABLED";
                break;
            default:
                componentStateAsString = "Unknown";
                break;
        }
        return String.format("%s ( %d )", componentStateAsString, componentState);
    }

    /**
     * Returns true if dateExpires is empty string or has null value.
     */
    public static boolean isNullOrEmpty(String dateExpires) {
        return dateExpires == null || dateExpires.trim().isEmpty()
                || dateExpires.trim().equalsIgnoreCase("null");
    }
}
