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

import android.annotation.Nullable;
import android.content.Context;
import android.text.Html;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.util.SafetyProtectionUtils;

/**
 * A custom view class for Safety Protection widget.
 */
public class SafetyProtectionSectionView extends LinearLayout {
    public SafetyProtectionSectionView(Context context) {
        super(context);
        init(context);
    }

    public SafetyProtectionSectionView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SafetyProtectionSectionView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public SafetyProtectionSectionView(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        setGravity(Gravity.CENTER);
        setOrientation(HORIZONTAL);
        int visibility = SafetyProtectionUtils.shouldShowSafetyProtectionResources(context)
                ? View.VISIBLE : View.GONE;
        setVisibility(visibility);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Context context = getContext();
        if (SafetyProtectionUtils.shouldShowSafetyProtectionResources(context)) {
            LayoutInflater.from(context).inflate(R.layout.safety_protection_section, this);
            TextView safetyProtectionDisplayTextView =
                    requireViewById(R.id.safety_protection_display_text);
            safetyProtectionDisplayTextView.setText(Html.fromHtml(
                    context.getString(android.R.string.safety_protection_display_text), 0));
        }
    }
}
