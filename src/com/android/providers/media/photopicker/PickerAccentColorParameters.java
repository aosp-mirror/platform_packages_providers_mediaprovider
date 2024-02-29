/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import android.annotation.ColorLong;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Color;
import android.provider.MediaStore;
import android.util.Log;

/**
 * This class holds the parameters and the utility methods for setting a custom picker accent
 * color.
 * Only valid input color codes with luminance greater than 0.6 will be set on the major picker
 * components. All other colors are set based on Material3 baseline theme for both the dark and
 * light theme.
 */
public class PickerAccentColorParameters {
    public static final String TAG = "PhotoPicker";
    @ColorLong
    private long mPickerAccentColor = -1;
    private boolean mIsNightModeEnabled = false;
    private float mAccentColorLuminance = 0;

    public PickerAccentColorParameters() {}

    public PickerAccentColorParameters(
            long color, Application application) {
        mPickerAccentColor = color;
        setNightModeFlag(application);
    }

    private void setNightModeFlag(Application application) {
        if (application == null) {
            return;
        }
        int nightModeFlag =
                application.getApplicationContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
        mIsNightModeEnabled = nightModeFlag == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Return dark or light color variant based on whether night mode is enabled or not.
     */
    public int getThemeBasedColor(String lightThemeVariant, String darkThemeVariant) {
        return mIsNightModeEnabled
                ? Color.parseColor(darkThemeVariant) : Color.parseColor(lightThemeVariant);
    }

    /**
     * Returns whether the accent color is set or not
     */
    public boolean isCustomPickerColorSet() {
        return mPickerAccentColor != -1;
    }

    /**
     * Checks whether the input color to be used as the picker accent color is valid or not.
     */
    public boolean checkColorValidity(long color) {
        // Gives us the formatted color string where the mask gives us the color in the RRGGBB
        // format and the %06X gives zero-padded hex (6 characters long)
        String hexColor = String.format("#%06X", (0xFFFFFF & color));
        int inputColor = Color.parseColor(hexColor);
        // Color.luminance(int) would require casting the input color to integer. Instead, we
        // can encode it to use Color.luminance(long)
        long inputColorEncoded = Color.pack(inputColor);
        if (!isColorFeasibleForBothBackgrounds(inputColorEncoded)) {
            // Fall back to the android theme
            resetPickerAccentColorParameters();
            Log.w(TAG, "Value set for " + MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR
                    + " is not within the permitted brightness range. Please refer to the "
                    + "docs for more details. Setting android theme on the picker.");
            return false;
        }
        return true;
    }

    private void resetPickerAccentColorParameters() {
        mPickerAccentColor = -1;
    }

    private boolean isColorFeasibleForBothBackgrounds(long color) {
        // Returns the luminance(can also be thought of brightness)
        // Returned value ranges from 0(black) to 1(white)
        mAccentColorLuminance = Color.luminance(color);
        // Colors within this range will work both on light and dark background. Range set by
        // testing with different colors.
        return mAccentColorLuminance >= 0.05 && mAccentColorLuminance < 0.9;
    }

    /**
     * Returns whether the accent color is bright or not based on the luminance of the color.
     * Lower luminance bound set by testing with different colors.
     */
    public boolean isAccentColorBright() {
        return mAccentColorLuminance >= 0.6;
    }

    /**
     * Returns the accent color to be set. We can ignore the alpha component when using the color
     * to be set on various picker components. Also, all of these components require integer color
     * value to set their respective colors.
     */
    public int getPickerAccentColor() {
        return (int) mPickerAccentColor;
    }
}
