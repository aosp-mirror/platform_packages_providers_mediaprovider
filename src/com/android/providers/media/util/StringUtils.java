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

package src.com.android.providers.media.util;

import android.icu.text.MessageFormat;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import android.content.res.Resources;

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
}
