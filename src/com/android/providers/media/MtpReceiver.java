/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.mtp.MtpServer;

public class MtpReceiver extends BroadcastReceiver {
    private static final String TAG = MtpReceiver.class.getSimpleName();
    private static final boolean DEBUG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // If we somehow fail to configure after boot, it becomes difficult to
            // recover usb state. Thus we always configure once on boot, but it
            // has no effect if Mtp is disabled or already configured.
            MtpServer.configure(false);
            final Intent usbState = context.registerReceiver(
                    null, new IntentFilter(UsbManager.ACTION_USB_STATE));
            if (usbState != null) {
                handleUsbState(context, usbState);
            }
        } else if (UsbManager.ACTION_USB_STATE.equals(action)) {
            handleUsbState(context, intent);
        }
    }

    private void handleUsbState(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        boolean configured = extras.getBoolean(UsbManager.USB_CONFIGURED);
        boolean connected = extras.getBoolean(UsbManager.USB_CONNECTED);
        boolean mtpEnabled = extras.getBoolean(UsbManager.USB_FUNCTION_MTP);
        boolean ptpEnabled = extras.getBoolean(UsbManager.USB_FUNCTION_PTP);
        boolean unlocked = extras.getBoolean(UsbManager.USB_DATA_UNLOCKED);
        boolean configChanged = extras.getBoolean(UsbManager.USB_CONFIG_CHANGED);

        if ((configChanged || (connected && !configured)) && (mtpEnabled || ptpEnabled)) {
            MtpServer.configure(ptpEnabled);
            // tell MediaProvider MTP is configured so it can bind to the service
            context.getContentResolver().insert(Uri.parse(
                    "content://media/none/mtp_connected"), null);
        } else if (configured && (mtpEnabled || ptpEnabled)) {
            intent = new Intent(context, MtpService.class);
            intent.putExtra(UsbManager.USB_DATA_UNLOCKED, unlocked);
            if (ptpEnabled) {
                intent.putExtra(UsbManager.USB_FUNCTION_PTP, true);
            }
            if (DEBUG) { Log.d(TAG, "handleUsbState startService"); }
            context.startService(intent);
        } else if (!connected || !(mtpEnabled || ptpEnabled)) {
            // Only unbind if disconnected or disabled.
            boolean status = context.stopService(new Intent(context, MtpService.class));
            if (DEBUG) { Log.d(TAG, "handleUsbState stopService status=" + status); }
            // tell MediaProvider MTP is disconnected so it can unbind from the service
            context.getContentResolver().delete(Uri.parse(
                    "content://media/none/mtp_connected"), null, null);
        }
    }
}
