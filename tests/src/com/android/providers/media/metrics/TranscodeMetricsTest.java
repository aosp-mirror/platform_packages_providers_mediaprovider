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

import static com.google.common.truth.Truth.assertThat;

import android.util.StatsEvent;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TranscodeMetricsTest {
    private static final int UNKNOWN_ATOM_TAG = -1;

    private static final TranscodeMetrics.TranscodingStatsData EMPTY_STATS_DATA =
            new TranscodeMetrics.TranscodingStatsData(
                    "something" /* mRequestorPackage */,
                    0 /* mAccessType */,
                    0 /* mFileSizeBytes */,
                    0 /* mTranscodeResult */,
                    0 /* mTranscodeDurationMillis */,
                    0 /* mFileDurationMillis */,
                    0 /* mFrameRate */,
                    (short) 0 /* mAccessReason */);

    @After
    public void tearDown() {
        // this is to reset the saved data in TranscodeMetrics.
        TranscodeMetrics.pullStatsEvents();
    }

    @Test
    public void testSaveStatsData_doesNotGoBeyondHardLimit() {
        for (int i = 0; i < TranscodeMetrics.getStatsDataCountHardLimit() + 5; ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }
        assertThat(TranscodeMetrics.getSavedStatsDataCount()).isEqualTo(
                TranscodeMetrics.getStatsDataCountHardLimit());
    }

    @Test
    public void testSaveStatsData_totalStatsDataCountEqualsPassedData() {
        int totalRequestsToPass = TranscodeMetrics.getStatsDataCountHardLimit() + 5;
        for (int i = 0; i < totalRequestsToPass; ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }
        assertThat(TranscodeMetrics.getTotalStatsDataCount()).isEqualTo(totalRequestsToPass);
    }

    @Test
    public void testSaveStatsData_savedStatsDataCountEqualsPassedData_withinHardLimit() {
        int totalRequestsToPass = TranscodeMetrics.getStatsDataCountHardLimit() - 5;
        for (int i = 0; i < totalRequestsToPass; ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }
        assertThat(TranscodeMetrics.getSavedStatsDataCount()).isEqualTo(totalRequestsToPass);
    }

    @Test
    public void testHandleStatsEventDataRequest_resetsData() {
        for (int i = 0; i < TranscodeMetrics.getStatsDataCountHardLimit(); ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }

        List<StatsEvent> statsEvents = TranscodeMetrics.pullStatsEvents();

        assertThat(TranscodeMetrics.getSavedStatsDataCount()).isEqualTo(0);
        assertThat(TranscodeMetrics.getTotalStatsDataCount()).isEqualTo(0);
    }

    @Test
    public void testHandleStatsEventDataRequest_fillsExactlySampleLimit_excessData() {
        for (int i = 0; i < TranscodeMetrics.getStatsDataCountHardLimit(); ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }

        List<StatsEvent> statsEvents = TranscodeMetrics.pullStatsEvents();

        assertThat(statsEvents.size()).isEqualTo(TranscodeMetrics.getStatsDataSampleLimit());
    }

    @Test
    public void testHandleStatsEventDataRequest_fillsExactlyAsSaved_dataWithinSampleLimit() {
        int totalRequestsToPass = TranscodeMetrics.getStatsDataSampleLimit() - 5;
        for (int i = 0; i < totalRequestsToPass; ++i) {
            TranscodeMetrics.saveStatsData(EMPTY_STATS_DATA);
        }

        List<StatsEvent> statsEvents = TranscodeMetrics.pullStatsEvents();

        assertThat(statsEvents.size()).isEqualTo(totalRequestsToPass);
    }
}
