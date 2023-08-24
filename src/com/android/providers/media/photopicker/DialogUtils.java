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

package com.android.providers.media.photopicker;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.providers.media.R;

/**
 * Dialog box to display custom alert or error messages
 */
public class DialogUtils extends AppCompatActivity {
    /**
     * Custom dialog box with single button to display title and single error message
     */
    public static void showDialog(Context context, String title, String message) {
        View customView =
                LayoutInflater.from(context).inflate(R.layout.error_dialog, null);

        TextView dialogTitle = customView.findViewById(R.id.title);
        TextView dialogMessage = customView.findViewById(R.id.message);
        Button gotItButton = customView.findViewById(R.id.okButton);
        dialogTitle.setText(title);
        dialogMessage.setText(message);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(customView);
        builder.setCancelable(false); // Prevent dismiss when clicking outside
        final AlertDialog dialog = builder.create();

        gotItButton.setOnClickListener(v -> {
            dialog.dismiss(); // Close the dialog
        });
        dialog.show();
    }
}
