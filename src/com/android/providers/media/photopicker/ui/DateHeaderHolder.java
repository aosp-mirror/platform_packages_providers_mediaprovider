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
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.util.DateTimeUtils;
import com.android.providers.media.photopicker.data.model.Item;

/**
 * ViewHolder of a date header within a RecyclerView.
 */
public class DateHeaderHolder extends BaseViewHolder {
    private TextView mTitle;
    public DateHeaderHolder(@NonNull Context context, @NonNull ViewGroup parent) {
        super(context, parent, R.layout.item_date_header);
        mTitle = itemView.findViewById(R.id.date_header_title);
    }

    @Override
    public void bind() {
        final Item item = (Item) itemView.getTag();
        final long dateTaken = item.getDateTaken();
        if (dateTaken == 0) {
            mTitle.setText(R.string.recent);
        } else {
            mTitle.setText(DateTimeUtils.getDateHeaderString(dateTaken));
        }
    }
}
