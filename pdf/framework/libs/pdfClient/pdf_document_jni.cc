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

#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>

#include "document.h"
#include "file.h"
#include "form_widget_info.h"
#include "logging.h"
#include "page.h"
// #include "proto/goto_links.proto.h" @Todo b/307870155
#include "fcntl.h"
#include "jni_conversion.h"
#include "rect.h"
// #include "util/java/scoped_local_ref.h"
#include <unistd.h>

#define LOG_TAG "pdf_document_jni"

// using util::java::ScopedLocalRef;

using pdfClient::Document;
using pdfClient::FileReader;
// using pdfClient::GotoLink;
// using pdfClient::GotoLinkList;
using pdfClient::Page;
using pdfClient::Point_i;
using pdfClient::Rectangle_i;
using pdfClient::SelectionBoundary;
using pdfClient::Status;
using std::vector;

using pdfClient::BufferWriter;
using pdfClient::FdWriter;
using pdfClient::LinuxFileOps;

namespace {
std::mutex mutex_;

bool RenderTileFd(JNIEnv* env, jobject jPdfDocument, int pageNum, int pageWidth, int pageHeight,
                  const Rectangle_i& tile, jboolean hideTextAnnots, jboolean retainPage, int fd);

}  // namespace

// Serializes the proto message into jbyteArray. Originally from
// google3/gws/framework/java/proto_util.cc?rcl=234527948&l=69-94.
// ScopedLocalRef<jbyteArray> CppProtoToBytes(JNIEnv* env, const proto2::MessageLite& proto) {
//    const int byte_size = proto.ByteSizeLong();
//    ScopedLocalRef<jbyteArray> array(env->NewByteArray(byte_size), env);
//    if (!array) {
//        return ScopedLocalRef<jbyteArray>(nullptr, env);
//    }
//    void* ptr = env->GetPrimitiveArrayCritical(array.get(), nullptr);
//    if (!ptr) {
//        return ScopedLocalRef<jbyteArray>(nullptr, env);
//    }
//    proto.SerializeWithCachedSizesToArray(reinterpret_cast<uint8*>(ptr));
//    env->ReleasePrimitiveArrayCritical(array.get(), ptr, 0);
//    return array;
//} @Todo b/307870155

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
    Status status = Document::Load(std::move(fileReader), password,
                                   /* closeFdOnFailure= */ true, &doc);

    if (password) {
        env->ReleaseStringUTFChars(jpassword, password);
    }
    // doc is owned by the LoadPdfResult in java.
    return convert::ToJavaLoadPdfResult(env, status, std::move(doc));
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

JNIEXPORT jboolean JNICALL Java_android_graphics_pdf_PdfDocumentProxy_renderPageFd(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint w, jint h, jboolean hideTextAnnots,
        jboolean retainPage, jint fd) {
    std::unique_lock<std::mutex> lock(mutex_);
    Rectangle_i tile = pdfClient::IntRect(0, 0, w, h);
    LOGD("Start renderPageFd: Page %d at (%d x %d)", pageNum, w, h);

    bool ret = RenderTileFd(env, jPdfDocument, pageNum, w, h, tile, hideTextAnnots, retainPage, fd);

    LOGD("Finish renderPageFd");
    return ret;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_pdf_PdfDocumentProxy_renderTileFd(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint pageWidth, jint pageHeight, jint left,
        jint top, jint tileWidth, jint tileHeight, jboolean hideTextAnnots, jboolean retainPage,
        jint fd) {
    std::unique_lock<std::mutex> lock(mutex_);
    Rectangle_i tile = pdfClient::IntRectWithSize(left, top, tileWidth, tileHeight);
    LOGD("Start renderTileFd: Page %d at (%d x %d), Tile (%d, %d)", pageNum, pageWidth, pageHeight,
         tile.Width(), tile.Height());

    bool ret = RenderTileFd(env, jPdfDocument, pageNum, pageWidth, pageHeight, tile, hideTextAnnots,
                            retainPage, fd);

    LOGD("Finish renderTileFd");
    return ret;
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

// TODO(b/307870155): Resolve GoToLinks proto issue and clean up
// Java_android_graphics_pdf_PdfDocumentProxy_getPageGotoLinksByteArray(
//         JNIEnv* env, jobject jPdfDocument, jint pageNum) {
//     Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
//     std::shared_ptr<Page> page = doc->GetPage(pageNum);
//
//     GotoLinkList links = page->GetGotoLinks();
//
//     ScopedLocalRef<jbyteArray> output_bytes = CppProtoToBytes(env, links);
//     return output_bytes.release();
// } @Todo b/307870155

JNIEXPORT void JNICALL Java_android_graphics_pdf_PdfDocumentProxy_releasePage(JNIEnv* env,
                                                                              jobject jPdfDocument,
                                                                              jint pageNum) {
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    doc->ReleaseRetainedPage(pageNum);
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

    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaFormWidgetInfo(env, result);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormWidgetInfo__II(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint index) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    FormWidgetInfo result = page->GetFormWidgetInfo(index);

    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaFormWidgetInfo(env, result);
}

JNIEXPORT jobject JNICALL Java_android_graphics_pdf_PdfDocumentProxy_getFormWidgetInfos(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jobject jTypeIds) {
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
    page->ClickOnPoint(point);

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
    page->SetFormFieldText(annotationIndex, text);

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
        jobject jSelectedIndices) {
    std::unique_lock<std::mutex> lock(mutex_);
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, true);

    vector<int> selected_indices = convert::ToNativeIntegerVector(env, jSelectedIndices);
    page->SetChoiceSelection(annotationIndex, selected_indices);

    vector<Rectangle_i> invalid_rects;
    if (page->HasInvalidRect()) {
        invalid_rects.push_back(page->ConsumeInvalidRect());
    }
    doc->ReleaseRetainedPage(pageNum);
    return convert::ToJavaRects(env, invalid_rects);
}

namespace {

bool RenderTileFd(JNIEnv* env, jobject jPdfDocument, jint pageNum, int pageWidth, int pageHeight,
                  const Rectangle_i& tile, jboolean hideTextAnnots, jboolean retainPage, int fd) {
    Document* doc = convert::GetPdfDocPtr(env, jPdfDocument);
    std::shared_ptr<Page> page = doc->GetPage(pageNum, retainPage);

    FdWriter fd_writer(fd);
    return page->RenderTile(pageWidth, pageHeight, tile, hideTextAnnots, &fd_writer);
}

}  // namespace