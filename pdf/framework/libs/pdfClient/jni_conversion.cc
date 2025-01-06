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

#include "jni_conversion.h"

#include <android/bitmap.h>
#include <string.h>

#include "rect.h"

using pdfClient::Document;
using pdfClient::ImageObject;
using pdfClient::LinuxFileOps;
using pdfClient::Matrix;
using pdfClient::PageObject;
using pdfClient::Rectangle_i;
using pdfClient::SelectionBoundary;
using std::string;
using std::vector;

namespace convert {

namespace {
static const char* kDimensions = "android/graphics/pdf/models/Dimensions";
static const char* kPdfDocument = "android/graphics/pdf/PdfDocumentProxy";
static const char* kLoadPdfResult = "android/graphics/pdf/models/jni/LoadPdfResult";
static const char* kLinkRects = "android/graphics/pdf/models/jni/LinkRects";
static const char* kMatchRects = "android/graphics/pdf/models/jni/MatchRects";
static const char* kSelection = "android/graphics/pdf/models/jni/PageSelection";
static const char* kBoundary = "android/graphics/pdf/models/jni/SelectionBoundary";
static const char* kFormWidgetInfo = "android/graphics/pdf/models/FormWidgetInfo";
static const char* kChoiceOption = "android/graphics/pdf/models/ListItem";
static const char* kGotoLinkDestination =
        "android/graphics/pdf/content/PdfPageGotoLinkContent$Destination";
static const char* kGotoLink = "android/graphics/pdf/content/PdfPageGotoLinkContent";
static const char* kPageObject = "android/graphics/pdf/component/PdfPageObject";
static const char* kImageObject = "android/graphics/pdf/component/PdfPageImageObject";

static const char* kBitmap = "android/graphics/Bitmap";
static const char* kBitmapConfig = "android/graphics/Bitmap$Config";
static const char* kMatrix = "android/graphics/Matrix";
static const char* kRect = "android/graphics/Rect";
static const char* kRectF = "android/graphics/RectF";
static const char* kInteger = "java/lang/Integer";
static const char* kString = "java/lang/String";
static const char* kObject = "java/lang/Object";
static const char* kArrayList = "java/util/ArrayList";
static const char* kList = "java/util/List";
static const char* kSet = "java/util/Set";
static const char* kIterator = "java/util/Iterator";
static const char* kFloat = "java/lang/Float";

// Helper methods to build up type signatures like "Ljava/lang/Object;" and
// function signatures like "(I)Ljava/lang/Integer;":
string sig(const char* raw) {
    if (strlen(raw) == 1)
        return raw;
    else {
        string res = "L";
        res += raw;
        res += ";";
        return res;
    }
}

// Function to build up type signatures like "Ljava/lang/Object;" and
// function signatures like "(I)Ljava/lang/Integer;":
template <typename... Args>
string funcsig(const char* return_type, const Args... params) {
    vector<const char*> vec = {params...};
    string res = "(";
    for (const char* param : vec) {
        res += sig(param);
    }
    res += ")";
    res += sig(return_type);
    return res;
}

// Classes can move around - if we want a long-lived pointer to one, we have
// get a global reference to it which will be updated if the class moves.
inline jclass GetPermClassRef(JNIEnv* env, const std::string& classname) {
    // NOTE: These references are held for the duration of the process.
    return (jclass)env->NewGlobalRef(env->FindClass(classname.c_str()));
}

// Convert an int to a java.lang.Integer.
jobject ToJavaInteger(JNIEnv* env, const int& i) {
    static jclass integer_class = GetPermClassRef(env, kInteger);
    static jmethodID value_of =
            env->GetStaticMethodID(integer_class, "valueOf", funcsig(kInteger, "I").c_str());
    return env->CallStaticObjectMethod(integer_class, value_of, i);
}

jobject ToJavaString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

// Copy a C++ vector to a java ArrayList, using the given function to convert.
template <class T>
jobject ToJavaList(JNIEnv* env, const vector<T>& input,
                   jobject (*ToJavaObject)(JNIEnv* env, const T&)) {
    static jclass arraylist_class = GetPermClassRef(env, kArrayList);
    static jmethodID init = env->GetMethodID(arraylist_class, "<init>", "(I)V");
    static jmethodID add = env->GetMethodID(arraylist_class, "add", funcsig("Z", kObject).c_str());

    jobject java_list = env->NewObject(arraylist_class, init, input.size());
    for (size_t i = 0; i < input.size(); i++) {
        jobject java_object = ToJavaObject(env, input[i]);
        env->CallBooleanMethod(java_list, add, java_object);
        env->DeleteLocalRef(java_object);
    }
    return java_list;
}

// Copy a C++ vector to a java ArrayList, using the given function to convert.
template <class T>
jobject ToJavaList(JNIEnv* env, const vector<T*>& input,
                   jobject (*ToJavaObject)(JNIEnv* env, const T*)) {
    static jclass arraylist_class = GetPermClassRef(env, kArrayList);
    static jmethodID init = env->GetMethodID(arraylist_class, "<init>", "(I)V");
    static jmethodID add = env->GetMethodID(arraylist_class, "add", funcsig("Z", kObject).c_str());

    jobject java_list = env->NewObject(arraylist_class, init, input.size());
    for (size_t i = 0; i < input.size(); i++) {
        jobject java_object = ToJavaObject(env, input[i]);
        env->CallBooleanMethod(java_list, add, java_object);
        env->DeleteLocalRef(java_object);
    }
    return java_list;
}

}  // namespace

jobject ToJavaPdfDocument(JNIEnv* env, std::unique_ptr<Document> doc) {
    static jclass pdf_doc_class = GetPermClassRef(env, kPdfDocument);
    static jmethodID init = env->GetMethodID(pdf_doc_class, "<init>", "(JI)V");

    int numPages = doc->NumPages();
    // Transfer ownership of |doc| to the Java object by releasing it.
    return env->NewObject(pdf_doc_class, init, (jlong)doc.release(), numPages);
}

jobject ToJavaLoadPdfResult(JNIEnv* env, const Status status, std::unique_ptr<Document> doc,
                            size_t pdfSizeInByte) {
    static jclass result_class = GetPermClassRef(env, kLoadPdfResult);
    static jmethodID init =
            env->GetMethodID(result_class, "<init>", funcsig("V", "I", kPdfDocument, "F").c_str());

    jobject jPdfDocument = (!doc) ? nullptr : ToJavaPdfDocument(env, std::move(doc));
    jfloat pdfSizeInKb = pdfSizeInByte / 1024.0f;
    return env->NewObject(result_class, init, (jint)status, jPdfDocument, pdfSizeInKb);
}

Document* GetPdfDocPtr(JNIEnv* env, jobject jPdfDocument) {
    static jfieldID pdp_field =
            env->GetFieldID(GetPermClassRef(env, kPdfDocument), "mPdfDocPtr", "J");
    jlong pdf_doc_ptr = env->GetLongField(jPdfDocument, pdp_field);
    return reinterpret_cast<Document*>(pdf_doc_ptr);
}

SelectionBoundary ToNativeBoundary(JNIEnv* env, jobject jBoundary) {
    static jclass boundary_class = GetPermClassRef(env, kBoundary);
    static jfieldID index_field = env->GetFieldID(boundary_class, "mIndex", "I");
    static jfieldID x_field = env->GetFieldID(boundary_class, "mX", "I");
    static jfieldID y_field = env->GetFieldID(boundary_class, "mY", "I");
    static jfieldID rtl_field = env->GetFieldID(boundary_class, "mIsRtl", "Z");

    return SelectionBoundary(
            env->GetIntField(jBoundary, index_field), env->GetIntField(jBoundary, x_field),
            env->GetIntField(jBoundary, y_field), env->GetBooleanField(jBoundary, rtl_field));
}

int ToNativeInteger(JNIEnv* env, jobject jInteger) {
    static jclass integer_class = GetPermClassRef(env, kInteger);
    static jmethodID get_int_value = env->GetMethodID(integer_class, "intValue", "()I");
    return env->CallIntMethod(jInteger, get_int_value);
}

vector<int> ToNativeIntegerVector(JNIEnv* env, jintArray jintArray) {
    jsize size = env->GetArrayLength(jintArray);
    vector<int> output(size);
    env->GetIntArrayRegion(jintArray, jsize{0}, size, &output[0]);
    return output;
}

std::unordered_set<int> ToNativeIntegerUnorderedSet(JNIEnv* env, jintArray jintArray) {
    jsize size = env->GetArrayLength(jintArray);
    vector<int> intermediate(size);
    env->GetIntArrayRegion(jintArray, jsize{0}, size, &intermediate[0]);
    return std::unordered_set<int>(std::begin(intermediate), std::end(intermediate));
}

jobject ToJavaRect(JNIEnv* env, const Rectangle_i& r) {
    static jclass rect_class = GetPermClassRef(env, kRect);
    static jmethodID init = env->GetMethodID(rect_class, "<init>", "(IIII)V");
    return env->NewObject(rect_class, init, r.left, r.top, r.right, r.bottom);
}

jobject ToJavaRectF(JNIEnv* env, const Rectangle_i& r) {
    static jclass rectF_class = GetPermClassRef(env, kRectF);
    static jmethodID init = env->GetMethodID(rectF_class, "<init>", "(FFFF)V");
    return env->NewObject(rectF_class, init, float(r.left), float(r.top), float(r.right),
                          float(r.bottom));
}

jobject ToJavaRects(JNIEnv* env, const vector<Rectangle_i>& rects) {
    return ToJavaList(env, rects, &ToJavaRect);
}

jobject ToJavaDimensions(JNIEnv* env, const Rectangle_i& r) {
    static jclass dim_class = GetPermClassRef(env, kDimensions);
    static jmethodID init = env->GetMethodID(dim_class, "<init>", "(II)V");
    return env->NewObject(dim_class, init, r.Width(), r.Height());
}

jobject ToJavaStrings(JNIEnv* env, const vector<std::string>& strings) {
    return ToJavaList(env, strings, &ToJavaString);
}

jobject ToJavaMatchRects(JNIEnv* env, const vector<Rectangle_i>& rects,
                         const vector<int>& match_to_rect, const vector<int>& char_indexes) {
    static jclass match_rects_class = GetPermClassRef(env, kMatchRects);
    static jmethodID init = env->GetMethodID(match_rects_class, "<init>",
                                             funcsig("V", kList, kList, kList).c_str());
    static jfieldID no_matches_field =
            env->GetStaticFieldID(match_rects_class, "NO_MATCHES", sig(kMatchRects).c_str());
    static jobject no_matches =
            env->NewGlobalRef(env->GetStaticObjectField(match_rects_class, no_matches_field));

    if (rects.empty()) {
        return no_matches;
    }
    jobject java_rects = ToJavaList(env, rects, &ToJavaRect);
    jobject java_m2r = ToJavaList(env, match_to_rect, &ToJavaInteger);
    jobject java_cidx = ToJavaList(env, char_indexes, &ToJavaInteger);
    return env->NewObject(match_rects_class, init, java_rects, java_m2r, java_cidx);
}

jobject ToJavaBoundary(JNIEnv* env, const SelectionBoundary& boundary) {
    static jclass boundary_class = GetPermClassRef(env, kBoundary);
    static jmethodID init = env->GetMethodID(boundary_class, "<init>", "(IIIZ)V");
    return env->NewObject(boundary_class, init, boundary.index, boundary.point.x, boundary.point.y,
                          boundary.is_rtl);
}

jobject ToJavaSelection(JNIEnv* env, const int page, const SelectionBoundary& start,
                        const SelectionBoundary& stop, const vector<Rectangle_i>& rects,
                        const std::string& text) {
    static jclass selection_class = GetPermClassRef(env, kSelection);
    static jmethodID init =
            env->GetMethodID(selection_class, "<init>",
                             funcsig("V", "I", kBoundary, kBoundary, kList, kString).c_str());

    // If rects is empty then it means that the text is empty as well.
    if (rects.empty()) {
        return nullptr;
    }

    jobject java_rects = ToJavaList(env, rects, &ToJavaRect);
    return env->NewObject(selection_class, init, page, ToJavaBoundary(env, start),
                          ToJavaBoundary(env, stop), java_rects, env->NewStringUTF(text.c_str()));
}

jobject ToJavaLinkRects(JNIEnv* env, const vector<Rectangle_i>& rects,
                        const vector<int>& link_to_rect, const vector<std::string>& urls) {
    static jclass link_rects_class = GetPermClassRef(env, kLinkRects);
    static jmethodID init =
            env->GetMethodID(link_rects_class, "<init>", funcsig("V", kList, kList, kList).c_str());
    static jfieldID no_links_field =
            env->GetStaticFieldID(link_rects_class, "NO_LINKS", sig(kLinkRects).c_str());
    static jobject no_links =
            env->NewGlobalRef(env->GetStaticObjectField(link_rects_class, no_links_field));

    if (rects.empty()) {
        return no_links;
    }
    jobject java_rects = ToJavaList(env, rects, &ToJavaRect);
    jobject java_l2r = ToJavaList(env, link_to_rect, &ToJavaInteger);
    jobject java_urls = ToJavaList(env, urls, &ToJavaString);
    return env->NewObject(link_rects_class, init, java_rects, java_l2r, java_urls);
}

jobject ToJavaChoiceOption(JNIEnv* env, const Option& option) {
    static jclass choice_option_class = GetPermClassRef(env, kChoiceOption);
    static jmethodID init =
            env->GetMethodID(choice_option_class, "<init>", funcsig("V", kString, "Z").c_str());
    jobject java_label = ToJavaString(env, option.label);
    return env->NewObject(choice_option_class, init, java_label, option.selected);
}

jobject ToJavaFormWidgetInfo(JNIEnv* env, const FormWidgetInfo& form_action_result) {
    static jclass click_result_class = GetPermClassRef(env, kFormWidgetInfo);

    static jmethodID init = env->GetMethodID(
            click_result_class, "<init>",
            funcsig("V", "I", "I", kRect, "Z", kString, kString, "Z", "Z", "Z", "I", "F", kList)
                    .c_str());

    jobject java_widget_rect = ToJavaRect(env, form_action_result.widget_rect());
    jobject java_text_value = ToJavaString(env, form_action_result.text_value());
    jobject java_accessibility_label = ToJavaString(env, form_action_result.accessibility_label());
    jobject java_choice_options = ToJavaList(env, form_action_result.options(), &ToJavaChoiceOption);

    return env->NewObject(click_result_class, init, form_action_result.widget_type(),
                          form_action_result.widget_index(), java_widget_rect,
                          form_action_result.read_only(), java_text_value, java_accessibility_label,
                          form_action_result.editable_text(), form_action_result.multiselect(),
                          form_action_result.multi_line_text(), form_action_result.max_length(),
                          form_action_result.font_size(), java_choice_options);
}

jobject ToJavaFormWidgetInfos(JNIEnv* env, const std::vector<FormWidgetInfo>& widget_infos) {
    return ToJavaList(env, widget_infos, &ToJavaFormWidgetInfo);
}

jobject ToJavaDestination(JNIEnv* env, const GotoLinkDest dest) {
    static jclass goto_link_dest_class = GetPermClassRef(env, kGotoLinkDestination);
    static jmethodID init = env->GetMethodID(goto_link_dest_class, "<init>",
                                             funcsig("V", "I", "F", "F", "F").c_str());

    return env->NewObject(goto_link_dest_class, init, dest.page_number, dest.x, dest.y, dest.zoom);
}

jobject ToJavaGotoLink(JNIEnv* env, const GotoLink& link) {
    static jclass goto_link_class = GetPermClassRef(env, kGotoLink);
    static jmethodID init = env->GetMethodID(goto_link_class, "<init>",
                                             funcsig("V", kList, kGotoLinkDestination).c_str());

    jobject java_rects = ToJavaList(env, link.rect, &ToJavaRectF);
    jobject goto_link_dest = ToJavaDestination(env, link.dest);

    return env->NewObject(goto_link_class, init, java_rects, goto_link_dest);
}

jobject ToJavaGotoLinks(JNIEnv* env, const vector<GotoLink>& links) {
    return ToJavaList(env, links, &ToJavaGotoLink);
}

jobject ToJavaBitmap(JNIEnv* env, void* buffer, int width, int height) {
    // Find Java Bitmap class
    static jclass bitmap_class = GetPermClassRef(env, kBitmap);

    // Get createBitmap method ID
    static jmethodID create_bitmap = env->GetStaticMethodID(
            bitmap_class, "createBitmap", funcsig(kBitmap, "I", "I", kBitmapConfig).c_str());

    // Get Bitmap.Config.ARGB_8888 field ID
    static jclass bitmap_config_class = GetPermClassRef(env, kBitmapConfig);
    static jfieldID argb8888_field =
            env->GetStaticFieldID(bitmap_config_class, "ARGB_8888", sig(kBitmapConfig).c_str());
    static jobject argb8888 =
            env->NewGlobalRef(env->GetStaticObjectField(bitmap_config_class, argb8888_field));

    // Create a Bitmap object
    jobject java_bitmap =
            env->CallStaticObjectMethod(bitmap_class, create_bitmap, width, height, argb8888);

    // Lock the Bitmap pixels for copying
    void* bitmap_pixels;
    if (AndroidBitmap_lockPixels(env, java_bitmap, &bitmap_pixels) < 0) {
        return NULL;
    }

    // Copy the buffer data into java Bitmap.
    std::memcpy(bitmap_pixels, buffer, width * height);  // 4 bytes per pixel (ARGB_8888)

    // Unlock the Bitmap pixels
    AndroidBitmap_unlockPixels(env, java_bitmap);

    return java_bitmap;
}

jfloatArray ToJavaFloatArray(JNIEnv* env, const float arr[], size_t length) {
    // Create Java float Array.
    jfloatArray java_float_array = env->NewFloatArray(length);

    // Copy data from the C++ float Array to the Java float Array
    env->SetFloatArrayRegion(java_float_array, 0, length, arr);

    return java_float_array;
}

jobject ToJavaMatrix(JNIEnv* env, const Matrix matrix) {
    // Find Java Matrix class
    static jclass matrix_class = GetPermClassRef(env, kMatrix);
    // Get the constructor method ID
    static jmethodID init = env->GetMethodID(matrix_class, "<init>", funcsig("V").c_str());

    // Create Java Matrix object.
    jobject java_matrix = env->NewObject(matrix_class, init);

    // Create Transform Array.
    float transform[9] = {matrix.a, matrix.c, matrix.e, matrix.b, matrix.d, matrix.f, 0, 0, 1};

    // Convert to Java floatArray.
    jfloatArray java_float_array =
            ToJavaFloatArray(env, transform, sizeof(transform) / sizeof(transform[0]));

    // Matrix setValues.
    static jmethodID set_values = env->GetMethodID(matrix_class, "setValues", "([F)V");
    env->CallVoidMethod(java_matrix, set_values, java_float_array);

    return java_matrix;
}

jobject ToJavaPdfPageObject(JNIEnv* env, const PageObject* page_object) {
    // Check for Native Supported Object.
    if (!page_object) {
        return NULL;
    }

    jobject java_page_object = nullptr;

    switch (page_object->GetType()) {
        case PageObject::Type::Image: {
            // Cast to ImageObject
            const ImageObject* image_object = static_cast<const ImageObject*>(page_object);

            // Find Java ImageObject Class.
            static jclass image_object_class = GetPermClassRef(env, kImageObject);
            // Get Constructor Id.
            static jmethodID init_image =
                    env->GetMethodID(image_object_class, "<init>", funcsig("V", kBitmap).c_str());

            // Get Bitmap readable buffer from ImageObject Data.
            void* buffer = image_object->GetBitmapReadableBuffer();

            // Create Java Bitmap from Native Bitmap Buffer.
            jobject java_bitmap =
                    ToJavaBitmap(env, buffer, image_object->width, image_object->height);

            // Create Java ImageObject Instance.
            java_page_object = env->NewObject(image_object_class, init_image, java_bitmap);

            break;
        }
        default:
            break;
    }

    // If no PageObject was created, return null
    if (java_page_object == nullptr) {
        return nullptr;
    }

    // Find Java PageObject class
    static jclass page_object_class = GetPermClassRef(env, kPageObject);

    // Set Java Matrix
    static jmethodID set_matrix =
            env->GetMethodID(page_object_class, "setMatrix", funcsig("V", kMatrix).c_str());
    env->CallVoidMethod(java_page_object, set_matrix, ToJavaMatrix(env, page_object->matrix));

    return java_page_object;
}

jobject ToJavaPdfPageObjects(JNIEnv* env, const vector<PageObject*>& page_objects) {
    return ToJavaList(env, page_objects, &ToJavaPdfPageObject);
}

std::unique_ptr<PageObject> ToNativePageObject(JNIEnv* env, jobject java_page_object) {
    // Find Java PageObject class and GetType
    static jclass page_object_class = GetPermClassRef(env, kPageObject);
    static jmethodID get_type = env->GetMethodID(page_object_class, "getPdfObjectType", "()I");
    jint page_object_type = env->CallIntMethod(java_page_object, get_type);

    // Pointer to PageObject
    std::unique_ptr<PageObject> page_object = nullptr;

    switch (static_cast<PageObject::Type>(page_object_type)) {
        case PageObject::Type::Image: {
            // Create ImageObject Data Instance.
            auto image_object = std::make_unique<ImageObject>();

            // Get Ref to Java ImageObject Class.
            static jclass image_object_class = GetPermClassRef(env, kImageObject);

            // Get the bitmap from the Java ImageObject
            static jmethodID get_bitmap =
                    env->GetMethodID(image_object_class, "getBitmap", funcsig(kBitmap).c_str());
            jobject java_bitmap = env->CallObjectMethod(java_page_object, get_bitmap);

            // Create an FPDF_BITMAP from the Android Bitmap
            void* bitmap_pixels;
            if (AndroidBitmap_lockPixels(env, java_bitmap, &bitmap_pixels) < 0) {
                break;
            }

            AndroidBitmapInfo bitmap_info;
            AndroidBitmap_getInfo(env, java_bitmap, &bitmap_info);
            const int stride = bitmap_info.width * 4;

            // Set ImageObject Data Bitmap
            image_object->bitmap = ScopedFPDFBitmap(FPDFBitmap_CreateEx(
                    bitmap_info.width, bitmap_info.height, FPDFBitmap_BGRA, bitmap_pixels, stride));

            // Unlock the Android Bitmap
            AndroidBitmap_unlockPixels(env, java_bitmap);

            page_object = std::move(image_object);
            break;
        }
        default:
            break;
    }

    if (!page_object) {
        return nullptr;
    }

    // Get Matrix from Java PageObject.
    static jmethodID get_matrix = env->GetMethodID(page_object_class, "getMatrix", "()[F");
    jfloatArray java_matrix_array =
            (jfloatArray)env->CallObjectMethod(java_page_object, get_matrix);

    // Copy Java Array to Native Array
    float transform[9];
    env->GetFloatArrayRegion(java_matrix_array, 0, 9, transform);

    // Set PageObject Data Matrix.
    page_object->matrix = {transform[0 /*kMScaleX*/], transform[3 /*kMSkewY*/],
                           transform[1 /*kMSkewX*/],  transform[4 /*kMScaleY*/],
                           transform[2 /*kMTransX*/], transform[5 /*kMTransY*/]};

    return page_object;
}

}  // namespace convert