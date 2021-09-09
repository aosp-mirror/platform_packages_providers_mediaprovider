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

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ProfileDialogFragment extends DialogFragment {

    private static final String TAG = "ProfileDialog";

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final PickerViewModel pickerViewModel = new ViewModelProvider(requireActivity()).get(
                PickerViewModel.class);
        final UserIdManager userIdManager = pickerViewModel.getUserIdManager();

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getActivity());
        if (userIdManager.isBlockedByAdmin()) {
            builder.setIcon(R.drawable.ic_lock);
            builder.setTitle(getString(R.string.picker_profile_admin_title));
            final String message = userIdManager.isManagedUserSelected() ?
                    getString(R.string.picker_profile_admin_msg_from_work) :
                    getString(R.string.picker_profile_admin_msg_from_personal);
            builder.setMessage(message);
            builder.setPositiveButton(android.R.string.ok, null);
        } else if (userIdManager.isWorkProfileOff()) {
            builder.setIcon(R.drawable.ic_work_outline);
            builder.setTitle(getString(R.string.picker_profile_work_title));
            builder.setMessage(getString(R.string.picker_profile_work_msg));
            // TODO(b/197199728): Add listener to turn on apps. This maybe a bit tricky because
            // after turning on Work profile, work profile MediaProvider may not be available
            // immediately.
            builder.setNegativeButton(android.R.string.cancel, null);
        } else {
            Log.e(TAG, "Unknown error for profile dialog");
            return null;
        }
        return builder.create();
    }

    public static void show(FragmentManager fm) {
        FragmentTransaction ft = fm.beginTransaction();
        Fragment f = new ProfileDialogFragment();
        ft.add(f, TAG);
        ft.commitAllowingStateLoss();
    }
}
