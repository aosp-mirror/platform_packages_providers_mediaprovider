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

package com.android.providers.media;

import static android.os.Process.THREAD_PRIORITY_FOREGROUND;

import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAllAvailableCloudProviders;
import static com.android.providers.media.photopicker.util.CloudProviderUtils.getAvailableCloudProviders;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.modules.utils.BasicShellCommandHandler;
import com.android.modules.utils.HandlerExecutor;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.CloudProviderInfo;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

class MediaProviderShellCommand extends BasicShellCommandHandler {
    private final @NonNull Context mAppContext;
    private final @NonNull ConfigStore mConfigStore;
    private final @NonNull PickerSyncController mPickerSyncController;
    private final @NonNull OutputStream mOut;

    MediaProviderShellCommand(
            @NonNull Context context,
            @NonNull ConfigStore configStore,
            @NonNull PickerSyncController pickerSyncController,
            @NonNull ParcelFileDescriptor out) {
        mAppContext = context.getApplicationContext();
        mPickerSyncController = pickerSyncController;
        mConfigStore = configStore;
        mOut = new ParcelFileDescriptor.AutoCloseOutputStream(out);
    }

    @Override
    public int onCommand(String cmd) {
        try (PrintWriter pw = getOutPrintWriter()) {
            if (cmd == null || cmd.isBlank()) {
                cmd = "help";
            }
            switch (cmd) {
                case "version":
                    return runVersion(pw);
                case "cloud-provider":
                    return runCloudProvider(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        }
    }

    private int runVersion(@NonNull PrintWriter pw) {
        pw.print('\'' + DatabaseHelper.INTERNAL_DATABASE_NAME + "' version: ");
        pw.println(DatabaseHelper.VERSION_LATEST);

        pw.print('\'' + DatabaseHelper.EXTERNAL_DATABASE_NAME + "' version: ");
        pw.println(DatabaseHelper.VERSION_LATEST);

        pw.print('\'' + PickerDatabaseHelper.PICKER_DATABASE_NAME + "' version: ");
        pw.println(PickerDatabaseHelper.VERSION_LATEST);

        return 0;
    }

    private int runCloudProvider(@NonNull PrintWriter pw) {
        final String subcommand = getNextArgRequired();
        switch (subcommand) {
            case "list":
                return runCloudProviderList(pw);
            case "info":
                return runCloudProviderInfo(pw);
            case "set":
                return runCloudProviderSet(pw);
            case "unset":
                return runCloudProviderUnset(pw);
            case "sync-library":
                return runCloudProviderSyncLibrary(pw);
            case "reset-library":
                return runCloudProviderResetLibrary(pw);
            default:
                pw.println("Error: unknown cloud-provider command '" + subcommand + "'");
                return 1;
        }
    }

    private int runCloudProviderList(@NonNull PrintWriter pw) {
        final String option = getNextOption();
        if ("--allowlist".equals(option)) {
            final List<String> allowlist = mConfigStore.getAllowedCloudProviderPackages();
            if (allowlist.isEmpty()) {
                pw.println("Allowlist is empty.");
            } else {
                for (var providerAuthority : allowlist) {
                    pw.println(providerAuthority);
                }
            }
        } else {
            final List<CloudProviderInfo> cloudProviders;

            if ("--all".equals(option)) {
                cloudProviders = getAllAvailableCloudProviders(mAppContext, mConfigStore);
            } else if (option == null) {
                cloudProviders = getAvailableCloudProviders(mAppContext, mConfigStore);
            } else {
                pw.println("Error: unknown cloud-provider list option '" + option + "'");
                return 1;
            }

            if (cloudProviders.isEmpty()) {
                pw.println("No available CloudMediaProviders.");
            } else {
                for (var providerInfo : cloudProviders) {
                    pw.println(providerInfo.toShortString());
                }
            }
        }
        return 0;
    }

    private int runCloudProviderInfo(@NonNull PrintWriter pw) {
        pw.println("Current CloudMediaProvider:");
        pw.println(mPickerSyncController.getCurrentCloudProviderInfo().toShortString());
        return 0;
    }

    private int runCloudProviderSet(@NonNull PrintWriter pw) {
        final String authority = getNextArg();
        if (authority == null) {
            pw.println("Error: authority not provided");
            pw.println("(usage: `media_provider cloud-provider set <authority>`)");
            return 1;
        }

        pw.println("Setting current CloudMediaProvider authority to '" + authority + "'...");
        final boolean success = mPickerSyncController.forceSetCloudProvider(authority);

        pw.println(success ?  "Succeed." : "Failed.");
        return success ? 0 : 1;
    }

    private int runCloudProviderUnset(@NonNull PrintWriter pw) {
        pw.println("Unsetting current CloudMediaProvider (disabling CMP integration)...");
        final boolean success = mPickerSyncController.forceSetCloudProvider(null);

        pw.println(success ?  "Succeed." : "Failed.");
        return success ? 0 : 1;
    }

    private int runCloudProviderSyncLibrary(@NonNull PrintWriter pw) {
        pw.println("Syncing PhotoPicker's library (CMP and local)...");

        // TODO(b/242550131): add PickerSyncController's API to make it possible to sync from only
        //  one provider at a time (i.e. either CMP or local)
        mPickerSyncController.syncAllMedia();

        pw.println("Done.");
        return 0;
    }

    private int runCloudProviderResetLibrary(@NonNull PrintWriter pw) {
        pw.println("Resetting PhotoPicker's library (CMP and local)...");

        // TODO(b/242550131): add PickerSyncController's API to make it possible to reset just one
        //  provider's library at a time (i.e. either CMP or local).
        mPickerSyncController.resetAllMedia();

        pw.println("Done.");
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("MediaProvider (media_provider) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println();
        pw.println("  version");
        pw.println("      Print databases (internal/external/picker) versions.");
        pw.println();
        pw.println("  cloud-provider [list | info | set | unset] [...]");
        pw.println("      Configure and audit CloudMediaProvider-s (CMPs).");
        pw.println();
        pw.println("      list  [--all | --allowlist]");
        pw.println("          List installed and allowlisted CMPs.");
        pw.println("          --all: ignore allowlist, list all installed CMPs.");
        pw.println("          --allowlisted: print allowlist of CMP authorities.");
        pw.println();
        pw.println("      info");
        pw.println("          Print current CloudMediaProvider.");
        pw.println();
        pw.println("      set <AUTHORITY>");
        pw.println("          Set current CloudMediaProvider.");
        pw.println();
        pw.println("      unset");
        pw.println("          Unset CloudMediaProvider (disables CMP integration).");
        pw.println();
        pw.println("      sync-library");
        pw.println("          Sync media from the current CloudMediaProvider and local provider.");
        pw.println();
        pw.println("      reset-library");
        pw.println("          Reset media previously synced from the CloudMediaProvider and");
        pw.println("          the local provider.");
        pw.println();
    }

    public void exec(@Nullable String[] args) {
        getExecutor().execute(() -> exec(
                /* Binder target */ null,
                /* FileDescriptor in */ null,
                /* FileDescriptor out */ null,
                /* FileDescriptor err */ null,
                args));
    }


    @Override
    public OutputStream getRawOutputStream() {
        return mOut;
    }

    @Override
    public OutputStream getRawErrorStream() {
        return mOut;
    }

    @Nullable
    private static Executor sExecutor;

    @NonNull
    private static synchronized Executor getExecutor() {
        if (sExecutor == null) {
            final HandlerThread thread = new HandlerThread("cli", THREAD_PRIORITY_FOREGROUND);
            thread.start();
            final Handler handler = new Handler(thread.getLooper());
            sExecutor = new HandlerExecutor(handler);
        }
        return sExecutor;
    }
}
