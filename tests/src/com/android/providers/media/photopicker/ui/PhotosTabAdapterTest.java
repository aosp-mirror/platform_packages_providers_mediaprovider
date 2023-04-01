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

package com.android.providers.media.photopicker.ui;

import static com.android.providers.media.photopicker.data.model.ModelTestUtils.generateJpegItem;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.text.format.DateUtils;
import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.ui.PhotosTabAdapter.DateHeader;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Tests {@link PhotosTabAdapter}. */
@RunWith(AndroidJUnit4.class)
public class PhotosTabAdapterTest {
    @Test
    public void test_hasRecentItem() {
        final PhotosTabAdapter adapter = createAdapter(/* shouldShowRecentSection */ true);

        final List<Item> mediaItems = generateFakeImageItemList(1);
        final Item mediaItem = mediaItems.get(0);

        adapter.setMediaItems(mediaItems);

        // One media item + "Recent" section header
        assertThat(adapter.getItemCount()).isEqualTo(2);

        // Check the first adapter item is the "Recent" section header
        final Object firstAdapterItem = adapter.getAdapterItem(0);
        assertThat(firstAdapterItem).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) firstAdapterItem).timestamp)
                .isEqualTo(DateHeader.RECENT);

        // Check the second adapter item is our only media item
        final Object secondAdapterItem = adapter.getAdapterItem(1);
        assertThat(secondAdapterItem).isInstanceOf(Item.class);
        assertThat(((Item) secondAdapterItem).getId())
                .isEqualTo(mediaItem.getId());
    }

    @Test
    public void test_exceedMinCount_notSameDay_hasRecentItemAndOneDateItem() {
        final PhotosTabAdapter adapter = createAdapter(/* shouldShowRecentSection */ true);

        final int mediaItemCount = 13;
        final List<Item> mediaItems = generateFakeImageItemList(mediaItemCount);

        adapter.setMediaItems(mediaItems);

        // Media items count + "Recent" section header + one other date section header
        assertThat(adapter.getItemCount()).isEqualTo(mediaItemCount + 2);

        Object adapterItem = adapter.getAdapterItem(0);
        assertThat(adapterItem).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) adapterItem).timestamp)
                .isEqualTo(DateHeader.RECENT);

        // The index 13 is the next date header because the minimum item count in the "Recent"
        // section is 12
        adapterItem = adapter.getAdapterItem(13);
        assertThat(adapterItem).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) adapterItem).timestamp)
                .isNotEqualTo(DateHeader.RECENT);
    }

    /**
     * Test that The total number in `Recent` may exceed the minimum count. If the photo items are
     * taken on same day, they should not be split apart.
     */
    @Test
    public void test_exceedMinCount_sameDay_hasRecentItemNoDateItem() {
        final PhotosTabAdapter adapter = createAdapter(/* shouldShowRecentSection */ true);

        final List<Item> mediaItems = generateFakeImageItemList(12);

        // "Manually" generate one more media item
        final long dateTakenMs = mediaItems.get(mediaItems.size() - 1).getDateTaken();
        final String lastItemId = "13";
        final Item lastItem = generateJpegItem(lastItemId, dateTakenMs, /* generationModified */ 1);
        mediaItems.add(lastItem);

        final int mediaItemsCount = mediaItems.size();
        adapter.setMediaItems(mediaItems);

        // Media items count + "Recent" section header
        assertThat(adapter.getItemCount()).isEqualTo(mediaItemsCount + 1);

        // Check the first item is the "Recent" section header
        final Object firstAdapterItem = adapter.getAdapterItem(0);
        assertThat(firstAdapterItem).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) firstAdapterItem).timestamp)
                .isEqualTo(DateHeader.RECENT);
    }

    @Test
    public void test_noRecentSection() throws Exception {
        final PhotosTabAdapter adapter = createAdapter(/* shouldShowRecentSection */ false);

        final int mediaItemCount = 3;
        final List<Item> mediaItems = generateFakeImageItemList(mediaItemCount);

        adapter.setMediaItems(mediaItems);

        // Media items count + date section headers (1 item per section)
        assertThat(adapter.getItemCount()).isEqualTo(2 * mediaItemCount);

        // Test the 1st item is a date header
        final Object firstDateHeader = adapter.getAdapterItem(0);
        assertThat(firstDateHeader).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) firstDateHeader).timestamp)
                .isNotEqualTo(DateHeader.RECENT);

        // Test the 3rd item is a date header, and the timestamp is greater than the previous one.
        final Object secondDateHeader = adapter.getAdapterItem(2);
        assertThat(secondDateHeader).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) secondDateHeader).timestamp)
                .isGreaterThan(((DateHeader) firstDateHeader).timestamp);

        // Test the 5th item is a date header, and the timestamp is greater than the previous one.
        final Object thirdDateHeader = adapter.getAdapterItem(4);
        assertThat(thirdDateHeader).isInstanceOf(DateHeader.class);
        assertThat(((DateHeader) thirdDateHeader).timestamp)
                .isGreaterThan(((DateHeader) secondDateHeader).timestamp);
    }

    @Test
    public void testGetCategoryItems_dataIsUpdated() {
        final PhotosTabAdapter adapter = createAdapter(/* shouldShowRecentSection */ false);

        final int mediaItemCount = 3;
        final List<Item> mediaItems = generateFakeImageItemList(mediaItemCount);

        adapter.setMediaItems(mediaItems);

        // Media items count + date section headers (1 item per section)
        assertThat(adapter.getItemCount()).isEqualTo(2 * mediaItemCount);

        // "Update" media collection
        final int newMediaItemCount = 5;
        final List<Item> newMediaItems = generateFakeImageItemList(newMediaItemCount);

        adapter.setMediaItems(newMediaItems);

        // Media items count + date section headers (1 item per section)
        assertThat(adapter.getItemCount()).isEqualTo(2 * newMediaItemCount);
    }

    private static PhotosTabAdapter createAdapter(boolean shouldShowRecentSection) {
        return new PhotosTabAdapter(/* showRecentSection */ shouldShowRecentSection,
                mock(Selection.class), mock(ImageLoader.class), mock(View.OnClickListener.class),
                mock(View.OnLongClickListener.class), mock(LifecycleOwner.class),
                /* cloudMediaProviderAppTitle */ mock(LiveData.class),
                /* cloudMediaAccountName */ mock(LiveData.class),
                /* shouldShowChooseAppBanner */ mock(LiveData.class),
                /* shouldShowCloudMediaAvailableBanner */ mock(LiveData.class),
                /* shouldShowAccountUpdatedBanner */ mock(LiveData.class),
                /* shouldShowChooseAccountBanner */ mock(LiveData.class),
                /* onChooseAppBannerEventListener */ mock(TabAdapter.OnBannerEventListener.class),
                /* onCloudMediaAvailableBannerEventListener */
                mock(TabAdapter.OnBannerEventListener.class),
                /* onAccountUpdatedBannerEventListener */
                mock(TabAdapter.OnBannerEventListener.class),
                /* onChooseAccountBannerEventListener */
                mock(TabAdapter.OnBannerEventListener.class));
    }

    private static Item generateFakeImageItem(String id) {
        final long dateTakenMs = System.currentTimeMillis()
                + Long.parseLong(id) * DateUtils.DAY_IN_MILLIS;
        return generateJpegItem(id, dateTakenMs, /* generationModified */ 1L);
    }

    private static List<Item> generateFakeImageItemList(int n) {
        final List<Item> itemList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            itemList.add(generateFakeImageItem(String.valueOf(i)));
        }
        return itemList;
    }
}
