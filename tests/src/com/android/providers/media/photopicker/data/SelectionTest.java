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

package com.android.providers.media.photopicker.data;

import static com.android.providers.media.photopicker.data.model.ModelTestUtils.generateJpegItem;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.content.Intent;
import android.provider.MediaStore;
import android.text.format.DateUtils;

import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.viewmodel.InstantTaskExecutorRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class SelectionTest {
    private Selection mSelection;

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Before
    public void setUp() {
        mSelection = new Selection();
    }

    @Test
    public void testAddSelectedItem() {
        final String id = "1";
        final Item item = generateFakeImageItem(id);

        mSelection.addSelectedItem(item);

        final Item selectedItem = mSelection.getSelectedItems().get(0);

        assertThat(selectedItem.getId()).isEqualTo(item.getId());
        assertThat(selectedItem.getDateTaken()).isEqualTo(item.getDateTaken());
        assertThat(selectedItem.getMimeType()).isEqualTo(item.getMimeType());
        assertThat(selectedItem.getDuration()).isEqualTo(item.getDuration());
    }

    @Test
    public void testDeleteSelectedItem() {
        final String id = "1";
        final Item item = generateFakeImageItem(id);

        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(0);

        mSelection.addSelectedItem(item);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(1);

        mSelection.removeSelectedItem(item);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(0);
    }

    @Test
    public void testClearSelectedItem() {
        final String id = "1";
        final Item item = generateFakeImageItem(id);
        final String id2 = "2";
        final Item item2 = generateFakeImageItem(id2);

        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(0);

        mSelection.addSelectedItem(item);
        mSelection.addSelectedItem(item2);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(2);

        mSelection.clearSelectedItems();
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(0);
    }

    @Test
    public void testSetSelectedItem() {
        final String id1 = "1";
        final Item item1 = generateFakeImageItem(id1);
        final String id2 = "2";
        final Item item2 = generateFakeImageItem(id2);
        final String id3 = "3";
        final Item item3 = generateFakeImageItem(id3);

        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(0);

        mSelection.addSelectedItem(item1);
        mSelection.addSelectedItem(item2);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(2);

        mSelection.setSelectedItem(item3);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(1);
        assertThat(mSelection.getSelectedItems().get(0).getContentUri())
                .isEqualTo(item3.getContentUri());
    }

    @Test
    public void testGetSelectedItemsForPreview_multiSelect() {
        final String id1 = "1";
        final Item item1 = generateFakeImageItem(id1);
        final String id2 = "2";
        final Item item2 = generateFakeImageItem(id2);
        final String id3 = "3";
        final Item item3 = generateFakeImageItem(id3);
        final String id4 = "4";
        final Item item4SameDateTakenAsItem3 =
                generateJpegItem(id4, item3.getDateTaken(), /* generationModified */ 1L);

        mSelection.addSelectedItem(item1);
        mSelection.addSelectedItem(item2);
        mSelection.addSelectedItem(item3);
        mSelection.addSelectedItem(item4SameDateTakenAsItem3);
        assertThat(mSelection.getSelectedItemCount().getValue()).isEqualTo(4);

        List<Item> itemsForPreview = mSelection.getSelectedItemsForPreview();
        assertThat(itemsForPreview.size()).isEqualTo(0);

        mSelection.prepareSelectedItemsForPreviewAll();

        itemsForPreview = mSelection.getSelectedItemsForPreview();
        assertThat(itemsForPreview.size()).isEqualTo(4);

        // Verify that the item list is sorted based on dateTaken
        assertThat(itemsForPreview.get(0).getId()).isEqualTo(id4);
        assertThat(itemsForPreview.get(1).getId()).isEqualTo(id3);
        assertThat(itemsForPreview.get(2).getId()).isEqualTo(id2);
        assertThat(itemsForPreview.get(3).getId()).isEqualTo(id1);
    }

    @Test
    public void testGetSelectedItemsForPreview_singleSelect() {
        final String id1 = "1";
        final Item item1 = generateFakeImageItem(id1);

        List<Item> itemsForPreview = mSelection.getSelectedItemsForPreview();
        assertThat(itemsForPreview.size()).isEqualTo(0);

        mSelection.prepareItemForPreviewOnLongPress(item1);

        itemsForPreview = mSelection.getSelectedItemsForPreview();
        assertThat(itemsForPreview.size()).isEqualTo(1);

        // Verify that the item list has expected element.
        assertThat(itemsForPreview.get(0).getId()).isEqualTo(id1);
    }

    @Test
    public void testParseValuesFromIntent_allowMultipleNotSupported() {
        final Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        mSelection.parseSelectionValuesFromIntent(intent);

        assertThat(mSelection.canSelectMultiple()).isFalse();
    }

    @Test
    public void testParseValuesFromIntent_setDefaultFalseForAllowMultiple() {
        final Intent intent = new Intent();

        mSelection.parseSelectionValuesFromIntent(intent);

        assertThat(mSelection.canSelectMultiple()).isFalse();
    }

    @Test
    public void testParseValuesFromIntent_validMaxSelectionLimit() {
        final int maxLimit = MediaStore.getPickImagesMaxLimit() - 1;
        final Intent intent = new Intent();
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxLimit);

        mSelection.parseSelectionValuesFromIntent(intent);

        assertThat(mSelection.canSelectMultiple()).isTrue();
        assertThat(mSelection.getMaxSelectionLimit()).isEqualTo(maxLimit);
    }

    @Test
    public void testParseValuesFromIntent_negativeMaxSelectionLimit_throwsException() {
        final Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1);

        try {
            mSelection.parseSelectionValuesFromIntent(intent);
            fail("The maximum selection limit is not allowed to be negative");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testParseValuesFromIntent_tooLargeMaxSelectionLimit_throwsException() {
        final Intent intent = new Intent();
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit() + 1);

        try {
            mSelection.parseSelectionValuesFromIntent(intent);
            fail("The maximum selection limit should not be greater than "
                    + "MediaStore.getPickImagesMaxLimit()");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testParseValuesFromIntent_actionGetContent() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        mSelection.parseSelectionValuesFromIntent(intent);

        assertThat(mSelection.canSelectMultiple()).isTrue();
        assertThat(mSelection.getMaxSelectionLimit())
                .isEqualTo(MediaStore.getPickImagesMaxLimit());
    }

    @Test
    public void testParseValuesFromIntent_actionGetContent_doesNotRespectExtraPickImagesMax() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 5);

        try {
            mSelection.parseSelectionValuesFromIntent(intent);
            fail("EXTRA_PICK_IMAGES_MAX is not supported for ACTION_GET_CONTENT");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testIsSelectionAllowed_exceedsMaxSelectionLimit_selectionNotAllowed() {
        final int maxLimit = 2;
        final Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxLimit);
        mSelection.parseSelectionValuesFromIntent(intent);

        assertThat(mSelection.isSelectionAllowed()).isTrue();

        final String id1 = "1";
        final Item item1 = generateFakeImageItem(id1);
        mSelection.addSelectedItem(item1);

        assertThat(mSelection.isSelectionAllowed()).isTrue();

        final String id2 = "2";
        final Item item2 = generateFakeImageItem(id2);
        mSelection.addSelectedItem(item2);

        assertThat(mSelection.isSelectionAllowed()).isFalse();
    }

    private static Item generateFakeImageItem(String id) {
        final long dateTakenMs = System.currentTimeMillis() + Long.parseLong(id)
                * DateUtils.DAY_IN_MILLIS;

        return generateJpegItem(id, dateTakenMs, /* generationModified */ 1L);
    }
}