/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.oemmetadataservices;

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.IOemMetadataService;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.OemMetadataService;
import android.provider.OemMetadataServiceWrapper;
import android.provider.media.internal.flags.Flags;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.ModernMediaScanner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(com.android.providers.media.flags.Flags.FLAG_ENABLE_OEM_METADATA)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
public class OemMetadataServiceTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long POLLING_SLEEP_MILLIS = 100;

    private CountDownLatch mServiceLatch = new CountDownLatch(1);
    private OemMetadataServiceWrapper mOemMetadataServiceWrapper;

    private ServiceConnection mServiceConnection;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);
    }

    @After
    public void tearDown() throws Exception {
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
        }
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testGetSupportedMimeTypes() throws Exception {
        bindService();
        assertNotNull(mOemMetadataServiceWrapper);

        Set<String> actualValue = mOemMetadataServiceWrapper.getSupportedMimeTypes();

        assertThat(actualValue).containsExactlyElementsIn(
                Arrays.asList("audio/mpeg", "audio/3gpp", "audio/flac"));
    }

    @Test
    public void testGetOemCustomData() throws Exception {
        bindService();
        assertNotNull(mOemMetadataServiceWrapper);
        final File dir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, "a.jpg");
        file.createNewFile();

        try {
            Map<String, String> actualValue = mOemMetadataServiceWrapper.getOemCustomData(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY));

            assertThat(actualValue.keySet()).containsExactlyElementsIn(
                    Arrays.asList("a", "b", "c", "d", "e"));
            assertThat(actualValue.get("a")).isEqualTo("1");
            assertThat(actualValue.get("b")).isEqualTo("2");
            assertThat(actualValue.get("c")).isEqualTo("3");
            assertThat(actualValue.get("d")).isEqualTo("4");
            assertThat(actualValue.get("e")).isEqualTo("5");
        } finally {
            file.delete();
        }
    }

    @Test
    public void testScanOfOemMetadataAndFilterOnReadWithoutPermission() throws Exception {
        IsolatedContext isolatedContext = new IsolatedContext(mContext, "modern",
                /* asFuseThread */ false);
        ModernMediaScanner modernMediaScanner = new ModernMediaScanner(isolatedContext,
                new TestConfigStore());
        final File downloads = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        final File audioFile = new File(downloads, "audio.mp3");
        try {
            stage(R.raw.test_audio, audioFile);


            Uri uri = modernMediaScanner.scanFile(audioFile, MediaScanner.REASON_UNKNOWN);

            DatabaseHelper databaseHelper = isolatedContext.getExternalDatabase();
            // Direct query on DB returns stored value of oem_metadata
            try (Cursor c = databaseHelper.runWithoutTransaction(db -> db.query(
                    "files", new String[]{FileColumns.OEM_METADATA}, "_id=?",
                    new String[]{String.valueOf(ContentUris.parseId(uri))}, null, null, null))) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToNext();
                byte[] oemData = c.getBlob(0);
                assertThat(oemData).isNotNull();
            }

            // With shell permission, OEM metadata should be filtered
            try (Cursor cursor = isolatedContext.getContentResolver()
                    .query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{
                            FileColumns.OEM_METADATA}, null, null, null)) {
                assertThat(cursor.getCount()).isEqualTo(1);
                cursor.moveToFirst();
                assertThat(cursor.getBlob(0)).isNull();
            }
        } finally {
            audioFile.delete();
        }
    }

    @Test
    public void testNoServiceBindingWithoutPermission() throws Exception {
        updateStateOfServiceWithPermission(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        IsolatedContext isolatedContext = new IsolatedContext(mContext, "modern",
                /* asFuseThread */ false);
        ModernMediaScanner modernMediaScanner = new ModernMediaScanner(isolatedContext,
                new TestConfigStore());
        final File downloads = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        final File audioFile = new File(downloads, "audio.mp3");
        try {
            stage(R.raw.test_audio, audioFile);

            Uri uri = modernMediaScanner.scanFile(audioFile, MediaScanner.REASON_UNKNOWN);

            DatabaseHelper databaseHelper = isolatedContext.getExternalDatabase();
            // Direct query on DB returns stored value of oem_metadata
            try (Cursor c = databaseHelper.runWithoutTransaction(db -> db.query(
                    "files", new String[]{FileColumns.OEM_METADATA}, "_id=?",
                    new String[]{String.valueOf(ContentUris.parseId(uri))}, null, null, null))) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToNext();
                // OEM custom data is null
                assertThat(c.getBlob(0)).isNull();
            }
        } finally {
            audioFile.delete();
            updateStateOfServiceWithPermission(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        }
    }

    private void updateStateOfServiceWithPermission(int state) throws Exception {
        PackageManager packageManager = mContext.getPackageManager();
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        inst.getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.INTERACT_ACROSS_USERS);
        ComponentName componentName = new ComponentName(
                mContext,
                "com.android.providers.media.oemmetadataservices.TestOemMetadataService");
        packageManager.setComponentEnabledSetting(componentName, state,
                PackageManager.DONT_KILL_APP);

        waitForComponentToBeInExpectedState(packageManager, componentName, state);
    }

    private static void waitForComponentToBeInExpectedState(PackageManager packageManager,
            ComponentName componentName, int state) throws Exception {
        pollForCondition(
                () -> isComponentEnabledSetAsExpected(packageManager, componentName, state),
                /* errorMessage= */ "Timed out while waiting for component to be disabled");
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < POLLING_TIMEOUT_MILLIS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

    private static boolean isComponentEnabledSetAsExpected(PackageManager packageManager,
            ComponentName componentName, int state) {
        return packageManager.getComponentEnabledSetting(componentName) == state;
    }

    private void bindService() throws InterruptedException {
        Intent intent = new Intent(OemMetadataService.SERVICE_INTERFACE);
        intent.setClassName("com.android.providers.media.tests",
                "com.android.providers.media.oemmetadataservices.TestOemMetadataService");
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                IOemMetadataService mOemMetadataService = IOemMetadataService.Stub.asInterface(
                        iBinder);
                mOemMetadataServiceWrapper = new OemMetadataServiceWrapper(mOemMetadataService);
                mServiceLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mOemMetadataServiceWrapper = null;
            }
        };
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        mServiceLatch.await(3, TimeUnit.SECONDS);
    }
}
