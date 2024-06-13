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

package com.android.providers.media.mediacognitionservices;

import static android.provider.MediaCognitionService.ProcessingTypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.CursorWindow;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaCognitionProcessingRequest;
import android.provider.MediaCognitionProcessingVersions;
import android.provider.MediaCognitionService;
import android.provider.mediacognitionutils.ICognitionGetVersionsCallbackInternal;
import android.provider.mediacognitionutils.ICognitionProcessMediaCallbackInternal;
import android.provider.mediacognitionutils.IMediaCognitionService;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


@RequiresFlagsEnabled(Flags.FLAG_MEDIA_COGNITION_SERVICE)
@RunWith(AndroidJUnit4.class)
public class MediaCognitionServiceTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private CountDownLatch mServiceLatch = new CountDownLatch(1);
    private IMediaCognitionService mPrimaryService;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(MediaCognitionService.SERVICE_INTERFACE);
        intent.setClassName("com.android.providers.media.tests",
                "com.android.providers.media.mediacognitionservices.TestMediaCognitionService");
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        mServiceLatch.await(3, TimeUnit.SECONDS);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mPrimaryService = IMediaCognitionService.Stub.asInterface(iBinder); // Update interface
            mServiceLatch.countDown();
        }
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mPrimaryService = null;
        }
    };

    @After
    public void tearDown() throws Exception {
        mContext.unbindService(mServiceConnection);
    }

    @Test
    public void testProcessMedia() throws Exception {
        assertNotNull(mPrimaryService);
        List<MediaCognitionProcessingRequest> requests =
                new ArrayList<MediaCognitionProcessingRequest>();
        requests.add(new MediaCognitionProcessingRequest
                .Builder(Uri.parse("content://media/test_image/1"))
                .setProcessingCombination(
                        ProcessingTypes.IMAGE_OCR_LATIN | ProcessingTypes.IMAGE_LABEL)
                .build());

        final TestProcessMediaCallback callback = new TestProcessMediaCallback();
        mPrimaryService.processMedia(requests, callback);
        callback.await(3, TimeUnit.SECONDS);
        final CursorWindow[] windows = callback.mWindows;
        assertTrue(windows.length > 0);
        // first column id should be 1
        assertTrue(windows[0].getString(0, 0).equals("1"));
        assertTrue(windows[0].getString(0, 1).equals("image_ocr_latin_1"));
        assertTrue(windows[0].getString(0, 2).equals("image_label_1"));
        windows[0].close();
    }

    @Test
    public void testProcessMediaLargeData() throws Exception {
        assertNotNull(mPrimaryService);
        List<MediaCognitionProcessingRequest> requests =
                new ArrayList<MediaCognitionProcessingRequest>();
        int totalCount = 100;
        for (int count = 1; count <= totalCount; count++) {
            requests.add(
                    new MediaCognitionProcessingRequest
                            .Builder(Uri.parse("content://media/test_image_large_data/" + count))
                            .setProcessingCombination(
                                    ProcessingTypes.IMAGE_OCR_LATIN | ProcessingTypes.IMAGE_LABEL)
                            .build());
        }

        final TestProcessMediaCallback callback = new TestProcessMediaCallback();
        mPrimaryService.processMedia(requests, callback);
        callback.await(3, TimeUnit.SECONDS);
        final CursorWindow[] windows = callback.mWindows;
        int count = 0;
        assertTrue(windows.length > 0);
        for (int index = 0; index < windows.length; index++) {
            for (int row = 0; row < windows[index].getNumRows(); row++) {
                count++;
                // matching id
                assertTrue(windows[index].getString(count - 1, 0).equals(String.valueOf(count)));
                assertNotNull(windows[index].getString(count - 1, 1));
                assertNotNull(windows[index].getString(count - 1, 2));
            }
            windows[index].close();
        }
        // making sure got all results back
        assertEquals(count, totalCount);
    }

    @Test
    public void testGetVersions() throws Exception {
        assertNotNull(mPrimaryService);
        final TestGetVersionsCallback callback = new TestGetVersionsCallback();
        mPrimaryService.getProcessingVersions(callback);
        callback.await(3, TimeUnit.SECONDS);
        assertNotNull(callback.mVersions);
        assertEquals(callback.mVersions.getProcessingVersion(ProcessingTypes.IMAGE_LABEL), 1);
        assertEquals(callback.mVersions.getProcessingVersion(ProcessingTypes.IMAGE_OCR_LATIN), 1);
    }

    private static class TestProcessMediaCallback
            extends ICognitionProcessMediaCallbackInternal.Stub {

        private CountDownLatch mLatch = new CountDownLatch(1);
        private CursorWindow[] mWindows;
        private String mFailureMessage;

        @Override
        public void onProcessMediaSuccess(CursorWindow[] cursorWindows) throws RemoteException {
            mWindows = cursorWindows;
            mLatch.countDown();
        }

        @Override
        public void onProcessMediaFailure(String s) throws RemoteException {
            mFailureMessage = s;
            mLatch.countDown();
        }

        public void await(int time, TimeUnit unit) throws InterruptedException {
            mLatch.await(time, unit);
        }

    }

    private static class TestGetVersionsCallback
            extends ICognitionGetVersionsCallbackInternal.Stub {

        private CountDownLatch mLatch = new CountDownLatch(1);

        private String mFailureMessage;

        private MediaCognitionProcessingVersions mVersions;

        @Override
        public void onGetProcessingVersionsSuccess(
                MediaCognitionProcessingVersions mediaCognitionProcessingVersions)
                throws RemoteException {
            mVersions = mediaCognitionProcessingVersions;
            mLatch.countDown();
        }

        @Override
        public void onGetProcessingVersionsFailure(String s) throws RemoteException {
            mFailureMessage = s;
            mLatch.countDown();
        }

        public void await(int time, TimeUnit unit) throws InterruptedException {
            mLatch.await(time, unit);
        }

    }

}
