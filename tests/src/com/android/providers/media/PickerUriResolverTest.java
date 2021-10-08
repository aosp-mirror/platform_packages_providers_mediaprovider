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

package com.android.providers.media;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.net.Uri;
import androidx.test.runner.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerUriResolverTest {
    @Test
    public void wrapProviderUriValid() throws Exception {
        final String providerSuffix = "authority/media/media_id";

        final Uri providerUriUserImplicit = Uri.parse("content://" + providerSuffix);

        final Uri providerUriUser0 = Uri.parse("content://0@" + providerSuffix);
        final Uri mediaUriUser0 = Uri.parse("content://media/picker/0/" + providerSuffix);

        final Uri providerUriUser10 = Uri.parse("content://10@" + providerSuffix);
        final Uri mediaUriUser10 = Uri.parse("content://media/picker/10/" + providerSuffix);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit, 0))
                .isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.wrapProviderUri(providerUriUser0, 0)).isEqualTo(mediaUriUser0);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser0)).isEqualTo(providerUriUser0);

        assertThat(PickerUriResolver.wrapProviderUri(providerUriUserImplicit, 10))
                .isEqualTo(mediaUriUser10);
        assertThat(PickerUriResolver.wrapProviderUri(providerUriUser10, 10))
                .isEqualTo(mediaUriUser10);
        assertThat(PickerUriResolver.unwrapProviderUri(mediaUriUser10))
                .isEqualTo(providerUriUser10);
    }

    @Test
    public void wrapProviderUriInvalid() throws Exception {
        final String providerSuffixLong = "authority/media/media_id/another_media_id";
        final String providerSuffixShort = "authority/media";

        final Uri providerUriUserLong = Uri.parse("content://0@" + providerSuffixLong);
        final Uri mediaUriUserLong = Uri.parse("content://media/picker/0/" + providerSuffixLong);

        final Uri providerUriUserShort = Uri.parse("content://0@" + providerSuffixShort);
        final Uri mediaUriUserShort = Uri.parse("content://media/picker/0/" + providerSuffixShort);

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserLong, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserLong));

        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.unwrapProviderUri(mediaUriUserShort));
        assertThrows(IllegalArgumentException.class,
                () -> PickerUriResolver.wrapProviderUri(providerUriUserShort, 0));
    }

    private static <T extends Exception> void assertThrows(Class<T> clazz, Runnable r) {
        try {
            r.run();
            fail("Expected " + clazz + " to be thrown");
        } catch (Exception e) {
            if (!clazz.isAssignableFrom(e.getClass())) {
                throw e;
            }
        }
    }
}
