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

package com.android.providers.media.photopicker.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of a photo item within a RecyclerView.
 */
public class PhotoGridHolder extends BaseItemHolder {

    private final Context mContext;
    private final IconHelper mIconHelper;
    private final ImageView mIconThumb;

    public PhotoGridHolder(Context context, ViewGroup parent, IconHelper iconHelper) {
        super(context, parent, R.layout.item_photo_grid);

        mIconThumb = itemView.findViewById(R.id.icon_thumbnail);
        mContext = context;
        mIconHelper = iconHelper;
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        mIconHelper.load(item, mIconThumb);
    }
}
