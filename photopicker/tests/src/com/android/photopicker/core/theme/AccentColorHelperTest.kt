/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.theme

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AccentColorHelperTest {
    @Test
    fun testAccentColorHelper_differentIntentActions_accentColorsNotSet() {
        // helper class reads colors from input intent and validates it.
        // create input intent:
        var pickerIntent = Intent()
        val validAccentColor: Long = 0xFF0000

        // Verify that the helper does not work with [MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP]
        // intent action.
        pickerIntent.setAction(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, validAccentColor)
        assertExceptionThrownDueToInvalidInput(pickerIntent)

        // Verify that the helper does not work with [Intent.ACTION_GET_CONTENT] intent action.
        pickerIntent.setAction(Intent.ACTION_GET_CONTENT)
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, validAccentColor)
        assertExceptionThrownDueToInvalidInput(pickerIntent)

        // Verify that the helper does not work with [Intent.ACTION_GET_CONTENT] intent action.
        pickerIntent.setAction(MediaStore.ACTION_PICK_IMAGES)
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, validAccentColor)
        val accentColorHelperPickImagesMode = AccentColorHelper(pickerIntent)
        assertThat(accentColorHelperPickImagesMode.getAccentColor()).isNotEqualTo(Color.Unspecified)
        assertThat(accentColorHelperPickImagesMode.getAccentColor()).isEqualTo(
            createColorFromLongFormat(validAccentColor),
        )
    }

    @Test
    fun testAccentColorHelper_invalidInputColors_accentColorsNotSet() {
        // helper class reads colors from input intent and validates it.
        // create input intent:
        var pickerIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        val invalidInputColor: Long = 0
        val colorWithLowLuminance: Long = 0xFFFFF0 // a color close to white
        val colorWithHighLuminance: Long = 0x000001 // a color close to black

        // Verify that the helper does not work with invalid colors

        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, invalidInputColor)
        assertExceptionThrownDueToInvalidInput(pickerIntent)

        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, colorWithLowLuminance)
        assertExceptionThrownDueToInvalidInput(pickerIntent)

        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, colorWithHighLuminance)
        assertExceptionThrownDueToInvalidInput(pickerIntent)
    }

    @Test
    fun testAccentColorHelper_validInputColor_accentColorsSet() {
        // helper class reads colors from input intent and validates it.
        // create input intent:
        var pickerIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        val validAccentColor: Long = 0xFF0000

        // Verify that the helper works with valid color
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR, validAccentColor)
        val accentColorHelperInvalidInputColor = AccentColorHelper(pickerIntent)
        assertThat(accentColorHelperInvalidInputColor.getAccentColor())
            .isNotEqualTo(Color.Unspecified)
        assertThat(accentColorHelperInvalidInputColor.getAccentColor()).isEqualTo(
            createColorFromLongFormat(validAccentColor),
        )
    }

    @Test
    fun testAccentColorHelper_textColorForDifferentLuminance_changesAccordingly() {
        // helper class reads colors from input intent and validates it.
        // create input intent:
        var pickerIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        val validAccentColorWithHighLuminance: Long = 0x144F28 // dark green
        val validAccentColorWithLowLuminance: Long = 0xd6E0D7 // light pink

        // Verify that the helper works with validAccentColorWithHighLuminance. In this case the
        // text color set should be white since the accent color is dark.
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR,
            validAccentColorWithHighLuminance)
        val accentColorHelperHighLuminance = AccentColorHelper(pickerIntent)
        assertThat(accentColorHelperHighLuminance.getAccentColor())
            .isNotEqualTo(Color.Unspecified)
        assertThat(accentColorHelperHighLuminance.getAccentColor()).isEqualTo(
            createColorFromLongFormat(validAccentColorWithHighLuminance),
        )
        assertThat(accentColorHelperHighLuminance.getTextColorForAccentComponents()).isEqualTo(
            Color.White,
        )

        // Verify that the helper works with validAccentColorWithLowLuminance. In this case the
        // text color set should be black since the accent color is light.
        pickerIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_ACCENT_COLOR,
            validAccentColorWithLowLuminance)
        val accentColorHelperLowLuminance = AccentColorHelper(pickerIntent)
        assertThat(accentColorHelperLowLuminance.getAccentColor())
            .isNotEqualTo(Color.Unspecified)
        assertThat(accentColorHelperLowLuminance.getAccentColor()).isEqualTo(
            createColorFromLongFormat(validAccentColorWithLowLuminance),
        )
        assertThat(accentColorHelperLowLuminance.getTextColorForAccentComponents()).isEqualTo(
            Color.Black,
        )
    }

    private fun createColorFromLongFormat(color: Long): Color {
        // Gives us the formatted color string where the mask gives us the color in the RRGGBB
        // format and the %06X gives zero-padded hex (6 characters long)
        val hexColor = String.format("#%06X", (0xFFFFFF.toLong() and color))
        val inputColor = android.graphics.Color.parseColor(hexColor)
        return Color(inputColor)
    }

    private fun assertExceptionThrownDueToInvalidInput(pickerIntent: Intent) {
        try {
            AccentColorHelper(pickerIntent)
            Assert.fail("Should have failed since the input was invalid")
        } catch (exception: IllegalArgumentException) {
            // expected result, yippee!!
        }
    }
}
