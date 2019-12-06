/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.drm.DrmConvertedStatus;
import android.drm.DrmManagerClient;
import android.drm.DrmSupportInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.tests.R;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Vector;
import java.util.function.Consumer;

/**
 * Verify that we scan various DRM files correctly. This is accomplished by
 * generating DRM files locally and confirming the scan results.
 */
@RunWith(AndroidJUnit4.class)
public class DrmTest {
    private static final String TAG = "DrmTest";

    private Context mContext;
    private DrmManagerClient mClient;

    private static final String MIME_FORWARD_LOCKED = "application/vnd.oma.drm.message";
    private static final String MIME_UNSUPPORTED = "unsupported/drm.mimetype";

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mClient = new DrmManagerClient(mContext);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.closeQuietly(mClient);
    }

    @Test
    public void testForwardLock_Audio() throws Exception {
        doForwardLock("audio/mpeg", R.raw.test_audio, (values) -> {
            assertEquals(1_045L, (long) values.getAsLong(MediaColumns.DURATION));
        });
    }

    @Test
    public void testForwardLock_Video() throws Exception {
        doForwardLock("video/mp4", R.raw.test_video, (values) -> {
            assertEquals(40_000L, (long) values.getAsLong(MediaColumns.DURATION));
        });
    }

    @Test
    public void testForwardLock_Image() throws Exception {
        doForwardLock("image/jpeg", R.raw.test_image, (values) -> {
            // ExifInterface currently doesn't know how to scan DRM images, so
            // the best we can do is verify the base test metadata
        });
    }

    @Test
    public void testForwardLock_Binary() throws Exception {
        doForwardLock("application/octet-stream", R.raw.test_image, null);
    }

    private void doForwardLock(String mimeType, int resId,
            @Nullable Consumer<ContentValues> verifier) throws Exception {
        Assume.assumeTrue(isForwardLockSupported());

        Vector<InputStream> sequence = new Vector<InputStream>();

        String dmHeader = "--mime_content_boundary\r\n" +
                "Content-Type: " + mimeType + "\r\n" +
                "Content-Transfer-Encoding: binary\r\n\r\n";
        sequence.add(new ByteArrayInputStream(dmHeader.getBytes(StandardCharsets.UTF_8)));

        AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(resId);
        FileInputStream body = afd.createInputStream();
        sequence.add(body);

        String dmFooter = "\r\n--mime_content_boundary--";
        sequence.add(new ByteArrayInputStream(dmFooter.getBytes(StandardCharsets.UTF_8)));

        SequenceInputStream dmStream = new SequenceInputStream(sequence.elements());

        File flPath = new File(mContext.getExternalMediaDirs()[0],
                "temp" + System.nanoTime() + ".fl");
        RandomAccessFile flFile = new RandomAccessFile(flPath, "rw");
        assertTrue("couldn't convert to fl file",
                convertDmToFl(mContext, dmStream,  flFile));
        dmStream.close(); // this closes the underlying streams and AFD as well
        flFile.close();

        // Scan the DRM file and confirm that it looks sane
        final Uri flUri = MediaStore.scanFile(mContext, flPath);
        try (Cursor c = mContext.getContentResolver().query(flUri, null, null, null)) {
            assertTrue(c.moveToFirst());

            final ContentValues values = new ContentValues();
            DatabaseUtils.copyFromCursorToContentValues(MediaColumns.DISPLAY_NAME, c, values);
            DatabaseUtils.copyFromCursorToContentValues(MediaColumns.MIME_TYPE, c, values);
            DatabaseUtils.copyFromCursorToContentValues(MediaColumns.IS_DRM, c, values);
            DatabaseUtils.copyFromCursorToContentValues(MediaColumns.DURATION, c, values);
            Log.v(TAG, values.toString());

            // Filename should match what we found on disk
            assertEquals(flPath.getName(), values.get(MediaColumns.DISPLAY_NAME));
            // Should always be marked as a DRM file
            assertEquals("1", values.get(MediaColumns.IS_DRM));

            final String actualMimeType = values.getAsString(MediaColumns.MIME_TYPE);
            if (Objects.equals(mimeType, actualMimeType)) {
                // We scanned the item successfully, so we can also check our
                // custom verifier, if any
                if (verifier != null) {
                    verifier.accept(values);
                }
            } else if (Objects.equals(MIME_UNSUPPORTED, actualMimeType)) {
                // We don't scan unsupported items, so we can't check our custom
                // verifier, but we're still willing to consider this as passing
            } else {
                fail("Unexpected MIME type " + actualMimeType);
            }
        }
    }

    /**
     * Shamelessly copied from
     * cts/common/device-side/util-axt/src/com/android/compatibility/common/util/MediaUtils.java
     */
    public static boolean convertDmToFl(
            Context context,
            InputStream dmStream,
            RandomAccessFile flFile) {
        final String MIMETYPE_DRM_MESSAGE = "application/vnd.oma.drm.message";
        byte[] dmData = new byte[10000];
        int totalRead = 0;
        int numRead;
        while (true) {
            try {
                numRead = dmStream.read(dmData, totalRead, dmData.length - totalRead);
            } catch (IOException e) {
                Log.w(TAG, "Failed to read from input file");
                return false;
            }
            if (numRead == -1) {
                break;
            }
            totalRead += numRead;
            if (totalRead == dmData.length) {
                // grow array
                dmData = Arrays.copyOf(dmData, dmData.length + 10000);
            }
        }
        byte[] fileData = Arrays.copyOf(dmData, totalRead);

        DrmManagerClient drmClient = null;
        try {
            drmClient = new DrmManagerClient(context);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "DrmManagerClient instance could not be created, context is Illegal.");
            return false;
        } catch (IllegalStateException e) {
            Log.w(TAG, "DrmManagerClient didn't initialize properly.");
            return false;
        }

        try {
            int convertSessionId = -1;
            try {
                convertSessionId = drmClient.openConvertSession(MIMETYPE_DRM_MESSAGE);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Conversion of Mimetype: " + MIMETYPE_DRM_MESSAGE
                        + " is not supported.", e);
                return false;
            } catch (IllegalStateException e) {
                Log.w(TAG, "Could not access Open DrmFramework.", e);
                return false;
            }

            if (convertSessionId < 0) {
                Log.w(TAG, "Failed to open session.");
                return false;
            }

            DrmConvertedStatus convertedStatus = null;
            try {
                convertedStatus = drmClient.convertData(convertSessionId, fileData);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Buffer with data to convert is illegal. Convertsession: "
                        + convertSessionId, e);
                return false;
            } catch (IllegalStateException e) {
                Log.w(TAG, "Could not convert data. Convertsession: " + convertSessionId, e);
                return false;
            }

            if (convertedStatus == null ||
                    convertedStatus.statusCode != DrmConvertedStatus.STATUS_OK ||
                    convertedStatus.convertedData == null) {
                Log.w(TAG, "Error in converting data. Convertsession: " + convertSessionId);
                try {
                    DrmConvertedStatus result = drmClient.closeConvertSession(convertSessionId);
                    if (result.statusCode != DrmConvertedStatus.STATUS_OK) {
                        Log.w(TAG, "Conversion failed with status: " + result.statusCode);
                        return false;
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Could not close session. Convertsession: " +
                           convertSessionId, e);
                }
                return false;
            }

            try {
                flFile.write(convertedStatus.convertedData, 0, convertedStatus.convertedData.length);
            } catch (IOException e) {
                Log.w(TAG, "Failed to write to output file: " + e);
                return false;
            }

            try {
                convertedStatus = drmClient.closeConvertSession(convertSessionId);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Could not close convertsession. Convertsession: " +
                        convertSessionId, e);
                return false;
            }

            if (convertedStatus == null ||
                    convertedStatus.statusCode != DrmConvertedStatus.STATUS_OK ||
                    convertedStatus.convertedData == null) {
                Log.w(TAG, "Error in closing session. Convertsession: " + convertSessionId);
                return false;
            }

            try {
                flFile.seek(convertedStatus.offset);
                flFile.write(convertedStatus.convertedData);
            } catch (IOException e) {
                Log.w(TAG, "Could not update file.", e);
                return false;
            }

            return true;
        } finally {
            drmClient.close();
        }
    }

    private boolean isForwardLockSupported() {
        for (DrmSupportInfo info : mClient.getAvailableDrmSupportInfo()) {
            Iterator<String> it = info.getMimeTypeIterator();
            while (it.hasNext()) {
                if (Objects.equals(MIME_FORWARD_LOCKED, it.next())) {
                    return true;
                }
            }
        }
        return false;
    }
}
