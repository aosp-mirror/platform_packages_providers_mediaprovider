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

#include "pdf_document_jni.h"

#include <android/bitmap.h>
#include <assert.h>
#include <jni.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>

#include "document.h"
#include "fcntl.h"
#include "file.h"
#include "form_widget_info.h"
#include "jni_conversion.h"
#include "logging.h"
#include "page.h"
#include "rect.h"
// #include "util/java/scoped_local_ref.h"
#include <unistd.h>

#define LOG_TAG "pdf_document_jni"

using pdfClient::Document;
using pdfClient::FileReader;
using pdfClient::GotoLink;
using pdfClient::Page;
using pdfClient::Point_i;
using pdfClient::Rectangle_i;
using pdfClient::SelectionBoundary;
using pdfClient::Status;
using std::vector;

using pdfClient::LinuxFileOps;

namespace {
std::mutex mutex_;

/** Matrix organizes its values in row-major order. These constants correspond to each
 * value in Matrix.
 */
constexpr int kMScaleX = 0;  // horizontal scale factor
constexpr int kMSkewX = 1;   // horizontal skew factor
constexpr int kMTransX = 2;  // horizontal translation
constexpr int kMSkewY = 3;   // vertical skew factor
constexpr int kMScaleY = 4;  // vertical scale factor
constexpr int kMTransY = 5;  // vertical translation
constexpr int kMPersp0 = 6;  // input x perspective factor
constexpr int kMPersp1 = 7;  // input y perspective factor
constexpr int kMPersp2 = 8;  // perspective bias
}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    std::unique_lock<std::mutex> lock(mutex_);
    pdfClient::InitLibrary();
    // NOTE(olsen): We never call FPDF_DestroyLibrary. Would it add any benefit?
    return JNI_VERSION_1_6;
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_createFromFd(
        JNIEnv* env, jobject obj, jint jfd, jstring jpassword) {
    std::unique_lock<std::mutex> lock(mutex_);
    LinuxFileOps::FDCloser fd(jfd);
    const char* password = jpassword == NULL ? NULL : env->GetStringUTFChars(jpassword, NULL);
    LOGD("Creating FPDF_DOCUMENT from fd: %d", fd.get());
    std::unique_ptr<Document> doc;

    auto fileReader = std::make_unique<FileReader>(std::move(fd));
    size_t pdfSizeInBytes = fileReader->CompleteSize();
    Status status = Document::Load(std::move(fileReader), password,
                                   /* closeFdOnFailure= */ true, &doc);

    if (password) {
        env->ReleaseStringUTFChars(jpassword, password);
    }
    // doc is owned by the LoadPdfResult in java.
    return convert::ToJavaLoadPdfResult(env, status, std::move(doc), pdfSizeInBytes);
}

JNIEXPORT void JNICALL Java_android_graphics_pdf_PdfDocumentProxy_destroy(JNIEnv* env,
                                                                          jobject jPdfDocument) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    LOGD("Deleting Document: %p", doc);
    delete doc;
    LOGD("Destroyed Document: %p", doc);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_pdf_PdfDocumentProxy_saveToFd(JNIEnv* env,
                                                                               jobject jPdfDocument,
                                                                               jint jfd) {
    std::unique_lock<std::mutex> lock(mutex_);
    LinuxFileOps::FDCloser fd(jfd);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    LOGD("Saving Document %p to fd %d", doc, fd.get());
    return doc->SaveAs(std::move(fd));
}

// TODO(b/321979602): Cleanup Dimensions, reusing `android.util.Size`
JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageDimensions(
        JNIEnv* env, jobject jPdfDocument, jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);
    Rectangle_i dimensions = page->Dimensions();
    if (pdfClient::IsEmpty(dimensions)) {
        LOGE("pdfClient returned 0x0 page dimensions for page %d", pageNum);
        dimensions = pdfClient::IntRect(0, 0, 612, 792);  // Default to Letter size.
    }
    return convert::ToJavaDimensions(env, dimensions);
}

JNIEXPORT jint JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageWidth(JNIEnv* env,
                                                                               jobject jPdfDocument,
                                                                               jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);
    return page->Width();
}

JNIEXPORT jint JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageHeight(JNIEnv* env,
                                                                               jobject jPdfDocument,
                                                                               jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);
    return page->Height();
}

JNIEXPORT jboolean JNICALL Java_android_graphics_pdf_PdfDocumentProxy_render(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jobject jbitmap, jint clipLeft,
        jint clipTop, jint clipRight, jint clipBottom, jfloatArray jTransform, jint renderMode,
        jint showAnnotTypes, jboolean renderFormFields) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);

    // android.graphics.Bitmap -> FPDF_Bitmap
    void* bitmap_pixels;
    if (AndroidBitmap_lockPixels(env, jbitmap, &bitmap_pixels) < 0) {
        LOGE("Couldn't get bitmap pixel address");
        return false;
    }
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, jbitmap, &info);
    const int stride = info.width * 4;
    FPDF_BITMAP bitmap =
            FPDFBitmap_CreateEx(info.width, info.height, FPDFBitmap_BGRA, bitmap_pixels, stride);

    // android.graphics.Matrix (SkMatrix) -> FS_Matrix
    float transform[9];
    env->GetFloatArrayRegion(jTransform, 0, 9, transform);
    if (transform[kMPersp0] != 0 || transform[kMPersp1] != 0 || transform[kMPersp2] != 1) {
        LOGE("Non-affine transform provided");
        return false;
    }

    FS_MATRIX pdfiumTransform = {transform[kMScaleX], transform[kMSkewY],  transform[kMSkewX],
                                 transform[kMScaleY], transform[kMTransX], transform[kMTransY]};

    // Actually render via Page
    std::shared_ptr<Page> page = doc->GetPage(pageNum);
    page->Render(bitmap, pdfiumTransform, clipLeft, clipTop, clipRight, clipBottom, renderMode,
                 showAnnotTypes, renderFormFields);
    if (AndroidBitmap_unlockPixels(env, jbitmap) < 0) {
        LOGE("Couldn't unlock bitmap pixel address");
        return false;
    }
    return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_pdf_PdfDocumentProxy_cloneWithoutSecurity(
        JNIEnv* env, jobject jPdfDocument, jint destination) {
    std::unique_lock<std::mutex> lock(mutex_);
    LinuxFileOps::FDCloser fd(destination);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    return doc->CloneDocumentWithoutSecurity(std::move(fd));
}

JNIEXPORT jstring JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);

    std::string text = page->GetTextUtf8();
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageAltText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);

    vector<std::string> alt_texts;
    page->GetAltTextUtf8(&alt_texts);
    return convert::ToJavaStrings(env, alt_texts);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_searchPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jstring query) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);
    const char* query_native = env->GetStringUTFChars(query, NULL);

    vector<Rectangle_i> rects;
    vector<int> match_to_rect;
    vector<int> char_indexes;
    page->BoundsOfMatchesUtf8(query_native, &rects, &match_to_rect, &char_indexes);
    jobject match_rects = convert::ToJavaMatchRects(env, rects, match_to_rect, char_indexes);

    env->ReleaseStringUTFChars(query, query_native);
    return match_rects;
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_selectPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jobject start, jobject stop) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);

    SelectionBoundary native_start = convert::ToNativeBoundary(env, start);
    SelectionBoundary native_stop = convert::ToNativeBoundary(env, stop);

    if (native_start.index == -1 && native_stop.index == -1 &&
        native_start.point == native_stop.point) {
        // Starting a new selection at a point.
        Point_i point = native_start.point;
        if (!page->SelectWordAt(point, &native_start, &native_stop)) {
            return NULL;
        }
    } else {
        // Updating an existing selection.
        page->ConstrainBoundary(&native_start);
        page->ConstrainBoundary(&native_stop);
        // Make sure start <= stop - one may have been dragged past the other.
        if (native_start.index > native_stop.index) {
            std::swap(native_start, native_stop);
        }
    }

    vector<Rectangle_i> rects;
    page->GetTextBounds(native_start.index, native_stop.index, &rects);
    std::string text(page->GetTextUtf8(native_start.index, native_stop.index));
    return convert::ToJavaSelection(env, pageNum, native_start, native_stop, rects, text);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageLinks(
        JNIEnv* env, jobject jPdfDocument, jint pageNum) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);

    vector<Rectangle_i> rects;
    vector<int> link_to_rect;
    vector<std::string> urls;
    page->GetLinksUtf8(&rects, &link_to_rect, &urls);

    return convert::ToJavaLinkRects(env, rects, link_to_rect, urls);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getPageGotoLinks(
        JNIEnv* env, jobject jPdfDocument, jint pageNum) {
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum);

    vector<GotoLink> links = page->GetGotoLinks();

    return convert::ToJavaGotoLinks(env, links);
}

JNIEXPORT void JNICALL Java_android_graphics_pdf_PdfDocumentProxy_retainPage(JNIEnv* env,
                                                                             jobject jPdfDocument,
                                                                             jint pageNum) {
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    doc->GetPage(pageNum, true);
}

JNIEXPORT void JNICALL Java_android_graphics_pdf_PdfDocumentProxy_releasePage(JNIEnv* env,
                                                                              jobject jPdfDocument,
                                                                              jint pageNum) {
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    doc->ReleaseRetainedPage(pageNum);
}

JNIEXPORT jboolean JNICALL
Java_android_graphics_pdf_PdfDocumentProxy_scaleForPrinting(JNIEnv* env, jobject jPdfDocument) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    return doc->ShouldScaleForPrinting();
}

JNIEXPORT jboolean JNICALL
Java_android_graphics_pdf_PdfDocumentProxy_isPdfLinearized(JNIEnv* env, jobject jPdfDocument) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    return doc->IsLinearized();
}

JNIEXPORT jint JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormType(JNIEnv* env,
                                                                            jobject jPdfDocument) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    return doc->GetFormType();
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormWidgetInfo__III(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint x, jint y) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    Point_i point{x, y};
    FormWidgetInfo result = page->GetFormWidgetInfo(point);
    if (!result.FoundWidget()) {
        LOGE("No widget found at point x = %d, y = %d", x, y);
        doc->ReleaseRetainedPage(pageNum);
        return NULL;
    }

    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaFormWidgetInfo(env, result);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormWidgetInfo__II(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint index) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    FormWidgetInfo result = page->GetFormWidgetInfo(index);
    if (!result.FoundWidget()) {
        LOGE("No widget found at this index %d", index);
        doc->ReleaseRetainedPage(pageNum);
        return NULL;
    }

    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaFormWidgetInfo(env, result);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormWidgetInfos(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jintArray jTypeIds) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    std::unordered_set<int> type_ids = convert::ToNativeIntegerUnorderedSet(env, jTypeIds);

    std::vector<FormWidgetInfo> widget_infos;
    page->GetFormWidgetInfos(type_ids, &widget_infos);

    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaFormWidgetInfos(env, widget_infos);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_clickOnPage(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint x, jint y) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    Point_i point{x, y};
    bool clicked = page->ClickOnPoint(point);
    if (!clicked) {
        LOGE("Cannot click on this widget");
        doc->ReleaseRetainedPage(pageNum);
        return NULL;
    }

    vector<Rectangle_i> invalid_rects;
    if (page->HasInvalidRect()) {
        invalid_rects.push_back(page->ConsumeInvalidRect());
    }
    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaRects(env, invalid_rects);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_setFormFieldText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint annotationIndex, jstring jText) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    const char* text = jText == nullptr ? "" : env->GetStringUTFChars(jText, nullptr);
    bool set = page->SetFormFieldText(annotationIndex, text);
    if (!set) {
        LOGE("Cannot set form field text on this widget.");
        doc->ReleaseRetainedPage(pageNum);
        return NULL;
    }

    if (jText) {
        env->ReleaseStringUTFChars(jText, text);
    }

    vector<Rectangle_i> invalid_rects;
    if (page->HasInvalidRect()) {
        invalid_rects.push_back(page->ConsumeInvalidRect());
    }
    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaRects(env, invalid_rects);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_setFormFieldSelectedIndices(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint annotationIndex,
        jintArray jSelectedIndices) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    vector<int> selected_indices = convert::ToNativeIntegerVector(env, jSelectedIndices);
    bool set = page->SetChoiceSelection(annotationIndex, selected_indices);
    if (!set) {
        LOGE("Cannot set selected indices on this widget.");
        doc->ReleaseRetainedPage(pageNum);
        return NULL;
    }

    vector<Rectangle_i> invalid_rects;
    if (page->HasInvalidRect()) {
        invalid_rects.push_back(page->ConsumeInvalidRect());
    }
    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaRects(env, invalid_rects);
}
