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

package com.android.providers.media.photopicker.util;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;

import com.android.providers.media.MediaProvider;
import com.android.providers.media.photopicker.data.model.UserId;

/**
 * A utility class for cross-profile usage.
 */
public class CrossProfileUtils {
    private static final String TAG = "CrossProfileUtils";

    /**
     * Whether {@link MediaStore#ACTION_PICK_IMAGES} intent is allowed to show cross profile content
     * for the current user profile. This can be regulated by device admin policies.
     *
     * @return {@code true} if the current user profile can access cross profile content via
     *         {@link MediaStore#ACTION_PICK_IMAGES}.
     *
     * Note: For simplicity this function assumes that the caller is only checking for
     * {@link MediaStore#ACTION_PICK_IMAGES} intent, please modify the logic if we want to check
     * for multiple intents.
     */
    public static boolean isIntentAllowedCrossProfileAccess(Intent intent,
            PackageManager packageManager) {
        intent.setComponent(null);
        intent.setPackage(null);
        for (ResolveInfo info : packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info != null && info.isCrossProfileIntentForwarderActivity()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@link MediaProvider} for user profile of {@code userId} is available or not
     *
     * return {@code false} if managed profile is turned off
     */
    public static boolean isMediaProviderAvailable(UserId userId, Context context) {
        try (ContentProviderClient client = userId.getContentResolver(context)
                .acquireUnstableContentProviderClient(MediaStore.AUTHORITY)) {
            if (client != null) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get content resolver for the given userId: " + userId, e);
        }
        return false;
    }

    /**
     * Whether the given profile is in quiet mode or not.
     * Notes: Quiet mode is only supported for managed profiles.
     *
     * @param userId The user id of the profile to be queried.
     * @return true if the profile is in quiet mode, false otherwise.
     */
    public static boolean isQuietModeEnabled(UserId userId, Context context) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        return userManager.isQuietModeEnabled(userId.getUserHandle());
    }
}
