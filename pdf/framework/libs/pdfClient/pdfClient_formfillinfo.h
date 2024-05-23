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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_EXTERNAL_FORMFILLINFO_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_EXTERNAL_FORMFILLINFO_H_

#include "fpdf_formfill.h"

namespace pdfClient {

static void FormInvalidate(FPDF_FORMFILLINFO* pThis, FPDF_PAGE page, double l, double t, double r,
                           double b) {
    // Nothing required.
}

static void FormSetCursor(FPDF_FORMFILLINFO* pThis, int nCursorType) {
    // Nothing required.
}

static int FormSetTimer(FPDF_FORMFILLINFO* pThis, int uElapse, TimerCallback lpTimerFunc) {
    return 0;
}

static void FormKillTimer(FPDF_FORMFILLINFO* pThis, int nTimerID) {
    // Nothing required.
}

static FPDF_SYSTEMTIME FormGetLocalTime(FPDF_FORMFILLINFO* pThis) {
    return FPDF_SYSTEMTIME();
}

static FPDF_PAGE FormGetPage(FPDF_FORMFILLINFO* pThis, FPDF_DOCUMENT doc, int pageIndex) {
    return nullptr;
}

static FPDF_PAGE FormGetCurrentPage(FPDF_FORMFILLINFO* pThis, FPDF_DOCUMENT doc) {
    return nullptr;
}

static int FormGetRotation(FPDF_FORMFILLINFO* pThis, FPDF_PAGE page) {
    return 0;  // Unrotated.
}

static void FormExecuteNamedAction(FPDF_FORMFILLINFO* pThis, FPDF_BYTESTRING namedAction) {
    // Nothing required.
}

static void FormSetTextFieldFocus(FPDF_FORMFILLINFO* pThis, FPDF_WIDESTRING value,
                                  FPDF_DWORD valueLen, FPDF_BOOL isFocus) {
    // Nothing required.
}

// Stubs out all the function pointers in the FormFillInfo that are required
// with empty implementations. Those functions that are actually needed can
// be set to something useful after making this call.
inline void StubFormFillInfo(FPDF_FORMFILLINFO* ffi) {
    ffi->version = 1;
    ffi->FFI_Invalidate = &FormInvalidate;
    ffi->FFI_SetCursor = &FormSetCursor;
    ffi->FFI_SetTimer = &FormSetTimer;
    ffi->FFI_KillTimer = &FormKillTimer;
    ffi->FFI_GetLocalTime = &FormGetLocalTime;
    ffi->FFI_GetPage = &FormGetPage;
    ffi->FFI_GetCurrentPage = &FormGetCurrentPage;
    ffi->FFI_GetRotation = &FormGetRotation;
    ffi->FFI_ExecuteNamedAction = &FormExecuteNamedAction;
    ffi->FFI_SetTextFieldFocus = &FormSetTextFieldFocus;
    // Implementation not required for the following:
    ffi->m_pJsPlatform = nullptr;
    ffi->Release = nullptr;
    ffi->FFI_OnChange = nullptr;
    ffi->FFI_OutputSelectedRect = nullptr;
    ffi->FFI_DoURIAction = nullptr;
    ffi->FFI_DoGoToAction = nullptr;
}

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_EXTERNAL_FORMFILLINFO_H_