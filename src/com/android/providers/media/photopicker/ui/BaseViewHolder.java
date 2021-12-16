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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ViewHolder of a item within a {@link RecyclerView.Adapter}.
 */
public abstract class BaseViewHolder extends RecyclerView.ViewHolder {

    public BaseViewHolder(@NonNull Context context, @NonNull ViewGroup parent, int layout) {
        this(context, inflateLayout(context, parent, layout));
    }

    public BaseViewHolder(@NonNull Context context, @NonNull View view) {
        super(view);
    }

    private static <V extends View> V inflateLayout(@NonNull Context context,
            @NonNull ViewGroup parent, int layout) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        return (V) inflater.inflate(layout, parent, false);
    }

    public abstract void bind();
}
