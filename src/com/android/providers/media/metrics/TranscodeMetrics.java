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

package com.android.providers.media.metrics;

import static com.android.providers.media.MediaProviderStatsLog.TRANSCODING_DATA;

import android.app.StatsManager;
import android.util.StatsEvent;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Stores metrics for transcode sessions to be shared with statsd.
 */
public final class TranscodeMetrics {
    private static final List<TranscodingStatsData> TRANSCODING_STATS_DATA = new ArrayList<>();
    private static final int STATS_DATA_SAMPLE_LIMIT = 100;
    private static final int STATS_DATA_COUNT_HARD_LIMIT = 400;  // for safety

    // Total data save requests we've received for one statsd pull cycle.
    // This can be greater than TRANSCODING_STATS_DATA.size() since we might not add all the
    // incoming data because of the hard limit on the size.
    private static int sTotalStatsDataCount = 0;

    static int handleStatsEventDataRequest(int atomTag, List<StatsEvent> statsEvents) {
        if (TRANSCODING_DATA != atomTag) {
            return StatsManager.PULL_SKIP;
        }

        synchronized (TRANSCODING_STATS_DATA) {
            if (TRANSCODING_STATS_DATA.size() > STATS_DATA_SAMPLE_LIMIT) {
                doRandomSampling();
            }

            fillStatsEventDataList(atomTag, statsEvents);
            resetStatsData();
            return StatsManager.PULL_SUCCESS;
        }
    }

    private static void fillStatsEventDataList(int atomTag, List<StatsEvent> statsEvents) {
        synchronized (TRANSCODING_STATS_DATA) {
            StatsEvent event;
            int dataCountToFill = Math.min(TRANSCODING_STATS_DATA.size(), STATS_DATA_SAMPLE_LIMIT);
            for (int i = 0; i < dataCountToFill; ++i) {
                TranscodingStatsData statsData = TRANSCODING_STATS_DATA.get(i);
                event = StatsEvent.newBuilder().setAtomId(atomTag)
                        .writeString(statsData.mRequestorPackage)
                        .writeInt(statsData.mAccessType)
                        .writeLong(statsData.mFileSizeBytes)
                        .writeInt(statsData.mTranscodeResult)
                        .writeLong(statsData.mTranscodeDurationMillis)
                        .writeLong(statsData.mFileDurationMillis)
                        .writeLong(statsData.mFrameRate)
                        .writeInt(statsData.mAccessReason).build();

                statsEvents.add(event);
            }
        }
    }

    /**
     * The random samples would get collected in the first {@code STATS_DATA_SAMPLE_LIMIT} positions
     * inside {@code TRANSCODING_STATS_DATA}
     */
    private static void doRandomSampling() {
        Random random = new Random(System.currentTimeMillis());

        synchronized (TRANSCODING_STATS_DATA) {
            for (int i = 0; i < STATS_DATA_SAMPLE_LIMIT; ++i) {
                int randomIndex = random.nextInt(TRANSCODING_STATS_DATA.size() - i /* bound */)
                        + i;
                Collections.swap(TRANSCODING_STATS_DATA, i, randomIndex);
            }
        }
    }

    private static void resetStatsData() {
        synchronized (TRANSCODING_STATS_DATA) {
            TRANSCODING_STATS_DATA.clear();
            sTotalStatsDataCount = 0;
        }
    }

    /**
     * Saves the statsd data that'd eventually be shared in the pull callback.
     * The data is saved only if StatsdPuller is initialized.
     * Everyone should always use this method for saving the data.
     */
    public static void saveStatsData(TranscodingStatsData transcodingStatsData) {
        if (!StatsdPuller.isInitialized()) {
            // no need to accumulate data if statsd is not going to ask for it.
            return;
        }

        forceSaveStatsData(transcodingStatsData);
    }

    /**
     * {@link StatsManager} does not register callback for Android unit test.  So, we have this
     * method exposed for unit-tests so that they can save data.
     */
    @VisibleForTesting
    static void forceSaveStatsData(TranscodingStatsData transcodingStatsData) {
        checkAndLimitStatsDataSizeAfterAddition(transcodingStatsData);
    }

    private static void checkAndLimitStatsDataSizeAfterAddition(
            TranscodingStatsData transcodingStatsData) {
        synchronized (TRANSCODING_STATS_DATA) {
            ++sTotalStatsDataCount;

            if (TRANSCODING_STATS_DATA.size() < STATS_DATA_COUNT_HARD_LIMIT) {
                TRANSCODING_STATS_DATA.add(transcodingStatsData);
                return;
            }

            // Depending on how much transcoding we are doing, we might end up accumulating a lot of
            // data by the time statsd comes back with the pull callback.
            // We don't want to just keep growing our memory usage.
            // So we simply randomly choose an element to remove with equal likeliness.
            Random random = new Random(System.currentTimeMillis());
            int replaceIndex = random.nextInt(sTotalStatsDataCount /* bound */);

            if (replaceIndex < STATS_DATA_COUNT_HARD_LIMIT) {
                TRANSCODING_STATS_DATA.set(replaceIndex, transcodingStatsData);
            }
        }
    }

    @VisibleForTesting
    static int getSavedStatsDataCount() {
        return TRANSCODING_STATS_DATA.size();
    }

    @VisibleForTesting
    static int getTotalStatsDataCount() {
        return sTotalStatsDataCount;
    }

    @VisibleForTesting
    static int getStatsDataCountHardLimit() {
        return STATS_DATA_COUNT_HARD_LIMIT;
    }

    @VisibleForTesting
    static int getStatsDataSampleLimit() {
        return STATS_DATA_SAMPLE_LIMIT;
    }

    /** This is the data to populate the proto shared to westworld. */
    public static final class TranscodingStatsData {
        private final String mRequestorPackage;
        private final short mAccessType;
        private final long mFileSizeBytes;
        private final short mTranscodeResult;
        private final long mTranscodeDurationMillis;
        private final long mFileDurationMillis;
        private final long mFrameRate;
        private final short mAccessReason;

        TranscodingStatsData(String requestorPackage, int accessType, long fileSizeBytes,
                int transcodeResult, long transcodeDurationMillis,
                long videoDurationMillis, long frameRate, short transcodeReason) {
            mRequestorPackage = requestorPackage;
            mAccessType = (short) accessType;
            mFileSizeBytes = fileSizeBytes;
            mTranscodeResult = (short) transcodeResult;
            mTranscodeDurationMillis = transcodeDurationMillis;
            mFileDurationMillis = videoDurationMillis;
            mFrameRate = frameRate;
            mAccessReason = transcodeReason;
        }
    }
}
