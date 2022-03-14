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

import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Drawables.Style.OUTLINE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Drawables.WORK_PROFILE_ICON;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.BLOCKED_BY_ADMIN_TITLE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.BLOCKED_FROM_PERSONAL_MESSAGE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.BLOCKED_FROM_WORK_MESSAGE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.WORK_PROFILE_PAUSED_MESSAGE;
import static com.android.providers.media.photopicker.ui.DevicePolicyResources.Strings.WORK_PROFILE_PAUSED_TITLE;

import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.modules.utils.build.SdkLevel;
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
            setBlockedByAdminParams(userIdManager.isManagedUserSelected(), builder);
        } else if (userIdManager.isWorkProfileOff()) {
            setWorkProfileOffParams(builder);
        } else {
            Log.e(TAG, "Unknown error for profile dialog");
            return null;
        }
        return builder.create();
    }

    private void setBlockedByAdminParams(
            boolean isManagedUserSelected, MaterialAlertDialogBuilder builder) {
        String title;
        String message;
        if (SdkLevel.isAtLeastT()) {
            title = getUpdatedEnterpriseString(
                    BLOCKED_BY_ADMIN_TITLE, R.string.picker_profile_admin_title);
            message = isManagedUserSelected
                    ? getUpdatedEnterpriseString(
                            BLOCKED_FROM_WORK_MESSAGE, R.string.picker_profile_admin_msg_from_work)
                    : getUpdatedEnterpriseString(
                            BLOCKED_FROM_PERSONAL_MESSAGE,
                            R.string.picker_profile_admin_msg_from_personal);
        } else {
            title = getString(R.string.picker_profile_admin_title);
            message = isManagedUserSelected
                    ? getString(R.string.picker_profile_admin_msg_from_work)
                    : getString(R.string.picker_profile_admin_msg_from_personal);
        }
        builder.setIcon(R.drawable.ic_lock);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, null);
    }

    private void setWorkProfileOffParams(MaterialAlertDialogBuilder builder) {
        Drawable icon;
        String title;
        String message;
        if (SdkLevel.isAtLeastT()) {
            icon = getUpdatedWorkProfileIcon();
            title = getUpdatedEnterpriseString(
                    WORK_PROFILE_PAUSED_TITLE, R.string.picker_profile_work_paused_title);
            message = getUpdatedEnterpriseString(
                    WORK_PROFILE_PAUSED_MESSAGE, R.string.picker_profile_work_paused_msg);
        } else {
            icon = getContext().getDrawable(R.drawable.ic_work_outline);
            title = getContext().getString(R.string.picker_profile_work_paused_title);
            message = getContext().getString(R.string.picker_profile_work_paused_msg);
        }
        builder.setIcon(icon);
        builder.setTitle(title);
        builder.setMessage(message);
        // TODO(b/197199728): Add listener to turn on apps. This maybe a bit tricky because
        // after turning on Work profile, work profile MediaProvider may not be available
        // immediately.
        builder.setPositiveButton(android.R.string.ok, null);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private String getUpdatedEnterpriseString(String updatableStringId, int defaultStringId) {
        final DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(updatableStringId, () -> getString(defaultStringId));
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private Drawable getUpdatedWorkProfileIcon() {
        final DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getDrawable(WORK_PROFILE_ICON, OUTLINE, () ->
                getContext().getDrawable(R.drawable.ic_work_outline));
    }

    public static void show(FragmentManager fm) {
        FragmentTransaction ft = fm.beginTransaction();
        Fragment f = new ProfileDialogFragment();
        ft.add(f, TAG);
        ft.commitAllowingStateLoss();
    }
}
