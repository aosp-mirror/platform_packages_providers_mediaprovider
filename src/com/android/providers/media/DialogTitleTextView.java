/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

public class DialogTitleTextView extends androidx.appcompat.widget.AppCompatTextView {
    private static final int MAX_LINES = 3; // Maximum lines allowed

    public DialogTitleTextView(Context context) {
        super(context);
        init();
    }

    public DialogTitleTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DialogTitleTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setMaxLines(MAX_LINES);

        setEllipsize(TextUtils.TruncateAt.END); // Add ellipsis if text is too long
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Check if the number of lines exceeds the limit
        if (getLineCount() > MAX_LINES) {
            // If exceeding, measure again with unlimited height to wrap the text
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }
}
