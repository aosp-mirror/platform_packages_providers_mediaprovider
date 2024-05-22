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

package android.graphics.pdf.logging;

import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED;
import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_FAILURE;
import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_SUCCESS;
import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_UNKNOWN;
import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED__API_TYPE__API_TYPE_SELECT_CONTENT;
import static android.graphics.pdf.PdfStatsLog.PDF_API_USAGE_REPORTED__API_TYPE__API_TYPE_UNKNOWN;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_ERROR;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_LOADED;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_UNKNOWN;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_WRONG_PASSWORD;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__TYPE__LINEARIZED_TYPE;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__TYPE__NON_LINEARIZED_TYPE;
import static android.graphics.pdf.PdfStatsLog.PDF_LOAD_REPORTED__TYPE__UNKNOWN_TYPE;
import static android.graphics.pdf.PdfStatsLog.PDF_SEARCH_REPORTED;
import static android.graphics.pdf.PdfStatsLog.PDF_SEARCH_REPORTED__API_RESPONSE_STATUS__RESPONSE_FAILURE;
import static android.graphics.pdf.PdfStatsLog.PDF_SEARCH_REPORTED__API_RESPONSE_STATUS__RESPONSE_SUCCESS;
import static android.graphics.pdf.logging.PdfEventLogger.ApiResponseTypes.FAILURE;
import static android.graphics.pdf.logging.PdfEventLogger.ApiResponseTypes.SUCCESS;
import static android.graphics.pdf.logging.PdfEventLogger.ApiTypes.SELECT_CONTENT;
import static android.graphics.pdf.logging.PdfEventLogger.LinearizationTypes.LINEARIZED;
import static android.graphics.pdf.logging.PdfEventLogger.LinearizationTypes.NON_LINEARIZED;
import static android.graphics.pdf.logging.PdfEventLogger.PdfLoadResults.ERROR;
import static android.graphics.pdf.logging.PdfEventLogger.PdfLoadResults.LOADED;
import static android.graphics.pdf.logging.PdfEventLogger.PdfLoadResults.UNKNOWN;
import static android.graphics.pdf.logging.PdfEventLogger.PdfLoadResults.WRONG_PASSWORD;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

import android.graphics.pdf.PdfStatsLog;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PdfEventLoggerTest {
    private static final long DOC_ID = 12345;
    private static final int PROCESS_UID = 1234;
    private static final long LOAD_DURATION_MILLIS = 123;
    private static final float PDF_SIZE_IN_KB = 12;
    private static final int NUM_PAGES = 1;
    private static final int QUERY_PAGE_NUMBER = 123;
    private static final int MATCH_COUNT = 1;
    private static final int QUERY_LENGTH = 1;
    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(
            this).mockStatic(PdfStatsLog.class).build();
    private PdfEventLogger mPdfEventLogger;

    @Before
    public void setUp() {
        mPdfEventLogger = new PdfEventLogger(PROCESS_UID, DOC_ID);
    }

    @Test
    public void logPdfLoadReportedEvent_pdfLoadedSuccessfully() {
        mPdfEventLogger.logPdfLoadReportedEvent(LOAD_DURATION_MILLIS, PDF_SIZE_IN_KB, LOADED,
                PdfEventLogger.LinearizationTypes.UNKNOWN, NUM_PAGES);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_LOAD_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(PDF_SIZE_IN_KB),
                eq(PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_LOADED),
                eq(PDF_LOAD_REPORTED__TYPE__UNKNOWN_TYPE), eq(NUM_PAGES), eq(DOC_ID)), times(1));
    }

    @Test
    public void logPdfLoadReportedEvent_pdfLoadFailed() {
        mPdfEventLogger.logPdfLoadReportedEvent(LOAD_DURATION_MILLIS, PDF_SIZE_IN_KB, ERROR,
                PdfEventLogger.LinearizationTypes.UNKNOWN, NUM_PAGES);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_LOAD_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(PDF_SIZE_IN_KB),
                eq(PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_ERROR),
                eq(PDF_LOAD_REPORTED__TYPE__UNKNOWN_TYPE), eq(NUM_PAGES), eq(DOC_ID)), times(1));
    }

    @Test
    public void logPdfLoadReportedEvent_pdfLoadedWithWrongPassword() {
        mPdfEventLogger.logPdfLoadReportedEvent(LOAD_DURATION_MILLIS, PDF_SIZE_IN_KB,
                WRONG_PASSWORD, PdfEventLogger.LinearizationTypes.UNKNOWN, NUM_PAGES);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_LOAD_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(PDF_SIZE_IN_KB),
                eq(PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_WRONG_PASSWORD),
                eq(PDF_LOAD_REPORTED__TYPE__UNKNOWN_TYPE), eq(NUM_PAGES), eq(DOC_ID)), times(1));
    }

    @Test
    public void logPdfLoadReportedEvent_withLinearizedPdf() {
        mPdfEventLogger.logPdfLoadReportedEvent(LOAD_DURATION_MILLIS, PDF_SIZE_IN_KB, UNKNOWN,
                LINEARIZED, NUM_PAGES);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_LOAD_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(PDF_SIZE_IN_KB),
                eq(PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_UNKNOWN),
                eq(PDF_LOAD_REPORTED__TYPE__LINEARIZED_TYPE), eq(NUM_PAGES), eq(DOC_ID)), times(1));
    }

    @Test
    public void logPdfLoadReportedEvent_withNonLinearizedPdf() {
        mPdfEventLogger.logPdfLoadReportedEvent(LOAD_DURATION_MILLIS, PDF_SIZE_IN_KB, UNKNOWN,
                NON_LINEARIZED, NUM_PAGES);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_LOAD_REPORTED), eq(PROCESS_UID),
                        eq(LOAD_DURATION_MILLIS), eq(PDF_SIZE_IN_KB),
                        eq(PDF_LOAD_REPORTED__LOAD_RESULT__RESULT_UNKNOWN),
                        eq(PDF_LOAD_REPORTED__TYPE__NON_LINEARIZED_TYPE), eq(NUM_PAGES),
                        eq(DOC_ID)),
                times(1));
    }

    @Test
    public void logPdfSearchReportedEvent_pdfSearchSuccessful() {
        mPdfEventLogger.logSearchReportedEvent(LOAD_DURATION_MILLIS, QUERY_LENGTH,
                QUERY_PAGE_NUMBER, SUCCESS, NUM_PAGES, MATCH_COUNT);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_SEARCH_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(QUERY_LENGTH), eq(QUERY_PAGE_NUMBER),
                eq(PDF_SEARCH_REPORTED__API_RESPONSE_STATUS__RESPONSE_SUCCESS), eq(DOC_ID),
                eq(NUM_PAGES), eq(MATCH_COUNT)), times(1));
    }

    @Test
    public void logPdfSearchReportedEvent_pdfSearchFailed() {
        mPdfEventLogger.logSearchReportedEvent(LOAD_DURATION_MILLIS, QUERY_LENGTH,
                QUERY_PAGE_NUMBER, FAILURE, NUM_PAGES, MATCH_COUNT);

        // then
        ExtendedMockito.verify(() -> PdfStatsLog.write(eq(PDF_SEARCH_REPORTED), eq(PROCESS_UID),
                eq(LOAD_DURATION_MILLIS), eq(QUERY_LENGTH), eq(QUERY_PAGE_NUMBER),
                eq(PDF_SEARCH_REPORTED__API_RESPONSE_STATUS__RESPONSE_FAILURE), eq(DOC_ID),
                eq(NUM_PAGES), eq(MATCH_COUNT)), times(1));
    }

    @Test
    public void logPdfApiUsageReportedEvent_selectContent_withSuccess() {
        mPdfEventLogger.logPdfApiUsageReportedEvent(SELECT_CONTENT, SUCCESS);

        // then
        ExtendedMockito.verify(
                () -> PdfStatsLog.write(eq(PDF_API_USAGE_REPORTED), eq(PROCESS_UID), eq(DOC_ID),
                        eq(PDF_API_USAGE_REPORTED__API_TYPE__API_TYPE_SELECT_CONTENT),
                        eq(PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_SUCCESS)),
                times(1));
    }

    @Test
    public void logPdfApiUsageReportedEvent_selectContent_withFailure() {
        mPdfEventLogger.logPdfApiUsageReportedEvent(SELECT_CONTENT, FAILURE);

        // then
        ExtendedMockito.verify(
                () -> PdfStatsLog.write(eq(PDF_API_USAGE_REPORTED), eq(PROCESS_UID), eq(DOC_ID),
                        eq(PDF_API_USAGE_REPORTED__API_TYPE__API_TYPE_SELECT_CONTENT),
                        eq(PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_FAILURE)),
                times(1));
    }

    @Test
    public void logPdfApiUsageReportedEvent_withUnknownAPI() {
        mPdfEventLogger.logPdfApiUsageReportedEvent(PdfEventLogger.ApiTypes.UNKNOWN,
                PdfEventLogger.ApiResponseTypes.UNKNOWN);

        // then
        ExtendedMockito.verify(
                () -> PdfStatsLog.write(eq(PDF_API_USAGE_REPORTED), eq(PROCESS_UID), eq(DOC_ID),
                        eq(PDF_API_USAGE_REPORTED__API_TYPE__API_TYPE_UNKNOWN),
                        eq(PDF_API_USAGE_REPORTED__API_RESPONSE_STATUS__RESPONSE_UNKNOWN)),
                times(1));
    }
}
