/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.net.Uri;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class NotificationContentObserverTest {
    private static final String URI_UPDATE_MEDIA = "content://media/picker_internal/update/media";
    private static final String URI_UPDATE_ALBUM_CONTENT =
            "content://media/picker_internal/update/album_content";

    private static final String KEY_MEDIA = "media";
    private static final String KEY_ALBUM_CONTENT = "album_content";
    private final NotificationContentObserver.ContentObserverCallback mObserverCallbackA =
            spy(new TestableContentObserverCallback());
    private final NotificationContentObserver.ContentObserverCallback mObserverCallbackB =
            spy(new TestableContentObserverCallback());

    private NotificationContentObserver mObserver;

    @Before
    public void setUp() {
        mObserver = new NotificationContentObserver(null);
    }

    @Test
    public void registerKeysToObserverCallback_correctKeys_registersCallback() {
        mObserver.registerKeysToObserverCallback(Arrays.asList(KEY_MEDIA), mObserverCallbackA);
        mObserver.registerKeysToObserverCallback(
                Arrays.asList(KEY_ALBUM_CONTENT), mObserverCallbackB);

        assertThat(mObserver.getUrisToCallback()).hasSize(2);
        assertThat(mObserver.getUrisToCallback())
                .containsEntry(Arrays.asList(KEY_MEDIA), mObserverCallbackA);
        assertThat(mObserver.getUrisToCallback())
                .containsEntry(Arrays.asList(KEY_ALBUM_CONTENT), mObserverCallbackB);

        mObserver.registerKeysToObserverCallback(
                Arrays.asList(KEY_MEDIA, KEY_ALBUM_CONTENT), mObserverCallbackB);

        assertThat(mObserver.getUrisToCallback()).hasSize(3);
        assertThat(mObserver.getUrisToCallback()).containsEntry(
                Arrays.asList(KEY_MEDIA, KEY_ALBUM_CONTENT), mObserverCallbackB);
    }

    @Test
    public void registerKeysToObserverCallback_incorrectKey_doesNotRegisterCallback() {
        mObserver.registerKeysToObserverCallback(Arrays.asList("invalid_key"), mObserverCallbackA);

        assertThat(mObserver.getUrisToCallback()).hasSize(0);
    }

    @Test
    public void registerKeysToObserverCallback_atLeastOneValidKey_registersCallback() {
        mObserver.registerKeysToObserverCallback(
                Arrays.asList(KEY_ALBUM_CONTENT, "invalid_key"), mObserverCallbackB);

        assertThat(mObserver.getUrisToCallback()).hasSize(1);
    }

    @Test
    public void onChange_receivesCorrectMediaUri_invokesCallback() {
        mObserver.registerKeysToObserverCallback(Arrays.asList(KEY_MEDIA), mObserverCallbackA);
        String timestamp = "1063";

        mObserver.onChange(false, Uri.parse(URI_UPDATE_MEDIA + "/" + timestamp));

        verify(mObserverCallbackA).onNotificationReceived(timestamp, null);
    }

    @Test
    public void onChange_receivesCorrectAlbumContentUri_invokesCallback() {
        mObserver.registerKeysToObserverCallback(
                Arrays.asList(KEY_ALBUM_CONTENT), mObserverCallbackB);
        String albumId = "10";
        String timestamp = "457801";

        mObserver.onChange(false, Uri.parse(URI_UPDATE_ALBUM_CONTENT
                + "/" + albumId + "/" + timestamp));

        verify(mObserverCallbackB).onNotificationReceived(timestamp, albumId);
    }

    @Test
    public void onChange_receivesIncorrectUri_doesNotInvokeCallback() {
        mObserver.registerKeysToObserverCallback(
                Arrays.asList(KEY_ALBUM_CONTENT), mObserverCallbackB);
        String timestamp = "12345";

        // Missing ablum-id
        mObserver.onChange(false, Uri.parse(URI_UPDATE_ALBUM_CONTENT + "/" + timestamp));

        verify(mObserverCallbackB, never()).onNotificationReceived(timestamp, null);
    }
}
