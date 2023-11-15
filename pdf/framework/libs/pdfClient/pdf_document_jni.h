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

#include <jni.h>
#include <stdio.h>

#ifndef MEDIAPROVIDER_JNI_PDF_DOCUMENT_JNI_H_
#define MEDIAPROVIDER_JNI_PDF_DOCUMENT_JNI_H_

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_createFromFd(
        JNIEnv* env, jclass, jint jfd, jstring jpassword);

// NOTE: All of these functions have an extra parameter called jobject
// jPdfDocument because for non-static methods, Java includes a hidden
// parameter that refers to this instance of the object.
JNIEXPORT void JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_destroy(JNIEnv* env, jobject jPdfDocument);

JNIEXPORT jboolean JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_saveToFd(
        JNIEnv* env, jobject jPdfDocument, jint jfd);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageDimensions(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jint JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageFeatures(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jboolean JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_renderPageFd(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint w, jint h, jboolean hideTextAnnots,
        jboolean retainPage, jint fd);

JNIEXPORT jboolean JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_renderTileFd(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint pageWidth, jint pageHeight, jint left,
        jint top, jint tileWidth, jint tileHeight, jboolean hideTextAnnots, jboolean retainPage,
        jint fd);

JNIEXPORT jboolean JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_cloneWithoutSecurity(JNIEnv* env,
                                                                            jobject jPdfDocument,
                                                                            jint destination);

JNIEXPORT jstring JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageAltText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_searchPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jstring query);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_selectPageText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jobject start, jobject stop);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageLinks(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jbyteArray JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_getPageGotoLinksByteArray(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT void JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_releasePage(
        JNIEnv* env, jobject jPdfDocument, jint pageNum);

JNIEXPORT jboolean JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_isPdfLinearized(
        JNIEnv* env, jobject jPdfDocument);

JNIEXPORT jint JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getFormType(
        JNIEnv* env, jobject jPdfDocument);

JNIEXPORT jobject JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_getFormWidgetInfo__III(JNIEnv* env,
                                                                              jobject jPdfDocument,
                                                                              jint pageNum, jint x,
                                                                              jint y);

JNIEXPORT jobject JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_getFormWidgetInfo__II(JNIEnv* env,
                                                                             jobject jPdfDocument,
                                                                             jint pageNum,
                                                                             jint index);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_getFormWidgetInfos(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jobject jTypeIds);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_clickOnPage(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint x, jint y);

JNIEXPORT jobject JNICALL Java_com_google_android_apps_viewer_pdflib_PdfDocument_setFormFieldText(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint annotationIndex, jstring jText);

JNIEXPORT jobject JNICALL
Java_com_google_android_apps_viewer_pdflib_PdfDocument_setFormFieldSelectedIndices(
        JNIEnv* env, jobject jPdfDocument, jint pageNum, jint annotationIndex,
        jobject jSelectedIndices);

#ifdef __cplusplus
}
#endif

#endif  // MEDIAPROVIDER_JNI_PDF_DOCUMENT_JNI_H_