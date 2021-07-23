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
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.providers.media.R;

/**
 * ViewHolder of a message within a RecyclerView.
 */
public class MessageHolder extends BaseViewHolder {
    private TextView mMessage;
    private int mMaxCount;
    public MessageHolder(@NonNull Context context, @NonNull ViewGroup parent, int maxCount) {
        super(context, parent, R.layout.item_message);
        mMaxCount = maxCount;
        mMessage = (TextView) itemView.findViewById(R.id.item_message);
    }

    @Override
    public void bind() {
        final Resources res = itemView.getContext().getResources();
        final CharSequence quantityText = res.getQuantityText(R.plurals.select_up_to, mMaxCount);
        final CharSequence message = TextUtils.expandTemplate(quantityText,
                String.valueOf(mMaxCount));
        mMessage.setText(message);
    }
}
