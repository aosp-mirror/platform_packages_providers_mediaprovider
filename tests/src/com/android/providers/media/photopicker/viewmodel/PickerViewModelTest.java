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

package com.android.providers.media.photopicker.viewmodel;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.ItemTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class PickerViewModelTest {

    private static final String FAKE_IMAGE_MIME_TYPE = "image/jpg";
    private static final String FAKE_DISPLAY_NAME = "testDisplayName";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application mApplication;

    private PickerViewModel mPickerViewModel;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getTargetContext();
        when(mApplication.getApplicationContext()).thenReturn(context);
        mPickerViewModel = new PickerViewModel(mApplication);
    }

    @Test
    public void testAddSelectedItem() throws Exception {
        final long id = 1;
        final Item item = generateFakeImageItem(id);

        mPickerViewModel.addSelectedItem(item);

        final Item selectedItem = mPickerViewModel.getSelectedItems().getValue().get(
                item.getContentUri());

        assertThat(selectedItem.getId()).isEqualTo(item.getId());
        assertThat(selectedItem.getDateTaken()).isEqualTo(item.getDateTaken());
        assertThat(selectedItem.getDisplayName()).isEqualTo(item.getDisplayName());
        assertThat(selectedItem.getMimeType()).isEqualTo(item.getMimeType());
        assertThat(selectedItem.getVolumeName()).isEqualTo(item.getVolumeName());
        assertThat(selectedItem.getDuration()).isEqualTo(item.getDuration());
    }

    @Test
    public void testDeleteSelectedItem() throws Exception {
        final long id = 1;
        final Item item = generateFakeImageItem(id);
        Map<Uri, Item> selectedItems = mPickerViewModel.getSelectedItems().getValue();

        assertThat(selectedItems.size()).isEqualTo(0);

        mPickerViewModel.addSelectedItem(item);

        selectedItems = mPickerViewModel.getSelectedItems().getValue();
        assertThat(selectedItems.size()).isEqualTo(1);

        mPickerViewModel.deleteSelectedItem(item);

        selectedItems = mPickerViewModel.getSelectedItems().getValue();
        assertThat(selectedItems.size()).isEqualTo(0);
    }

    @Test
    public void testClearSelectedItem() throws Exception {
        final long id1 = 1;
        final Item item1 = generateFakeImageItem(id1);
        final long id2 = 2;
        final Item item2 = generateFakeImageItem(id2);
        Map<Uri, Item> selectedItems = mPickerViewModel.getSelectedItems().getValue();

        assertThat(selectedItems.size()).isEqualTo(0);

        mPickerViewModel.addSelectedItem(item1);
        mPickerViewModel.addSelectedItem(item2);

        selectedItems = mPickerViewModel.getSelectedItems().getValue();
        assertThat(selectedItems.size()).isEqualTo(2);

        mPickerViewModel.clearSelectedItems();

        selectedItems = mPickerViewModel.getSelectedItems().getValue();
        assertThat(selectedItems.size()).isEqualTo(0);
    }

    private static Item generateFakeImageItem(long id) {
        return ItemTest.generateItem(id, FAKE_IMAGE_MIME_TYPE, FAKE_DISPLAY_NAME + id,
                MediaStore.VOLUME_EXTERNAL, 12345678l, 1000l);
    }
}

