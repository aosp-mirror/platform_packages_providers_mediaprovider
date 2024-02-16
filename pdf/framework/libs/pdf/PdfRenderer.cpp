/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <android/bitmap.h>
#include <nativehelper/JNIHelp.h>
#include <sys/types.h>
#include <unistd.h>

#include <vector>

#include "PdfUtils.h"
#include "fpdfview.h"

namespace {
/**
 * Matrix organizes its values in row-major order. These constants correspond to each
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

namespace android {

static const int RENDER_MODE_FOR_DISPLAY = 1;
static const int RENDER_MODE_FOR_PRINT = 2;

static struct {
    jfieldID x;
    jfieldID y;
} gPointClassInfo;

static jlong nativeOpenPageAndGetSize(JNIEnv* env, jclass thiz, jlong documentPtr, jint pageIndex,
                                      jobject outSize) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);

    FPDF_PAGE page = FPDF_LoadPage(document, pageIndex);
    if (!page) {
        jniThrowException(env, "java/lang/IllegalStateException", "cannot load page");
        return -1;
    }

    double width = 0;
    double height = 0;

    int result = FPDF_GetPageSizeByIndex(document, pageIndex, &width, &height);
    if (!result) {
        jniThrowException(env, "java/lang/IllegalStateException", "cannot get page size");
        return -1;
    }

    env->SetIntField(outSize, gPointClassInfo.x, width);
    env->SetIntField(outSize, gPointClassInfo.y, height);

    return reinterpret_cast<jlong>(page);
}

static void nativeClosePage(JNIEnv* env, jclass thiz, jlong pagePtr) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    FPDF_ClosePage(page);
}

static void nativeRenderPage(JNIEnv* env, jclass thiz, jlong documentPtr, jlong pagePtr,
                             jobject jbitmap, jint clipLeft, jint clipTop, jint clipRight,
                             jint clipBottom, jfloatArray jtransform, jint renderMode) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    void* bitmap_pixels;
    if (AndroidBitmap_lockPixels(env, jbitmap, &bitmap_pixels) < 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Could not extract pixel address from bitmap.");
        return;
    }

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, jbitmap, &info);

    const int stride = info.width * 4;

    FPDF_BITMAP bitmap =
            FPDFBitmap_CreateEx(info.width, info.height, FPDFBitmap_BGRA, bitmap_pixels, stride);

    int renderFlags = FPDF_REVERSE_BYTE_ORDER;
    if (renderMode == RENDER_MODE_FOR_DISPLAY) {
        renderFlags |= FPDF_LCD_TEXT;
    } else if (renderMode == RENDER_MODE_FOR_PRINT) {
        renderFlags |= FPDF_PRINTING;
    }

    float transform[9];
    env->GetFloatArrayRegion(jtransform, 0, 9, transform);
    // Transforms with perspective are unsupported by pdfium, and are documented to be unsupported
    // by the API.
    if (transform[kMPersp0] != 0 || transform[kMPersp1] != 0 || transform[kMPersp2] != 1) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Non-affine transform provided.");
        AndroidBitmap_unlockPixels(env, jbitmap);
        return;
    }

    FS_MATRIX pdfTransform = {transform[kMScaleX], transform[kMSkewY],  transform[kMSkewX],
                              transform[kMScaleY], transform[kMTransX], transform[kMTransY]};

    FS_RECTF clip = {(float)clipLeft, (float)clipTop, (float)clipRight, (float)clipBottom};
    FPDF_RenderPageBitmapWithMatrix(bitmap, page, &pdfTransform, &clip, renderFlags);

    if (AndroidBitmap_unlockPixels(env, jbitmap) < 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "Could not unlock Bitmap pixels.");
        return;
    }
}

static const JNINativeMethod gPdfRenderer_Methods[] = {
        {"nativeCreate", "(IJ)J", (void*)nativeOpen},
        {"nativeClose", "(J)V", (void*)nativeClose},
        {"nativeGetPageCount", "(J)I", (void*)nativeGetPageCount},
        {"nativeScaleForPrinting", "(J)Z", (void*)nativeScaleForPrinting},
        {"nativeRenderPage", "(JJLandroid/graphics/Bitmap;IIII[FI)V", (void*)nativeRenderPage},
        {"nativeOpenPageAndGetSize", "(JILandroid/graphics/Point;)J",
         (void*)nativeOpenPageAndGetSize},
        {"nativeClosePage", "(J)V", (void*)nativeClosePage}};

int register_android_graphics_pdf_PdfRenderer(JNIEnv* env) {
    int result = RegisterMethodsOrDie(env, "android/graphics/pdf/PdfRenderer", gPdfRenderer_Methods,
                                      NELEM(gPdfRenderer_Methods));

    jclass clazz = FindClassOrDie(env, "android/graphics/Point");
    gPointClassInfo.x = GetFieldIDOrDie(env, clazz, "x", "I");
    gPointClassInfo.y = GetFieldIDOrDie(env, clazz, "y", "I");

    return result;
};

};  // namespace android
