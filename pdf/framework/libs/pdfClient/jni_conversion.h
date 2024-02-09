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

#ifndef MEDIAPROVIDER_PDF_JNI_CONVERSION_H_
#define MEDIAPROVIDER_PDF_JNI_CONVERSION_H_

#include <jni.h>

#include <vector>

#include "document.h"
#include "file.h"
#include "form_widget_info.h"
#include "page.h"
#include "rect.h"

using pdfClient::Document;
using pdfClient::FormWidgetInfo;
using pdfClient::Option;
using pdfClient::Rectangle_i;
using pdfClient::SelectionBoundary;
using pdfClient::Status;
using std::vector;

namespace convert {

// Creates a Java PdfDocument object to wrap this Document instance.
jobject ToJavaPdfDocument(JNIEnv* env, const Document* doc);

// Creates a Java LoadPdfResult object using c++ objects status and Document
jobject ToJavaLoadPdfResult(JNIEnv* env, const Status status, std::unique_ptr<Document> doc);

// Gets the PDF document pointer from the PdfDocument java object.
Document* GetPdfDocPtr(JNIEnv* env, jobject jPdfDocument);

// Convert a Java SelectionBoundary to a C++ SelectionBoundary.
SelectionBoundary ToNativeBoundary(JNIEnv* env, jobject jBoundary);

// Convert a Java Integer to an C++ int.
int ToNativeInteger(JNIEnv* env, jobject jInteger);

// Convert a Java List<Integer> to C++ vector<int>.
vector<int> ToNativeIntegerVector(JNIEnv* env, jobject jIntegerList);

// Convert a Java Set<Integer> to C++ std::unordered_set<int>.
std::unordered_set<int> ToNativeIntegerUnorderedSet(JNIEnv* env, jobject jIntegerSet);

// Convert a pdfClient rectangle to an android.graphics.Rect.
jobject ToJavaRect(JNIEnv* env, const Rectangle_i& r);

// Convert a vector of pdfClient Rectangle_i to List<android.graphics.Rect>.
jobject ToJavaRects(JNIEnv* env, const vector<Rectangle_i>& rects);

// Convert a pdfClient rectangle to a projector Dimensions.
jobject ToJavaDimensions(JNIEnv* env, const Rectangle_i& r);

// Convert the vector of UTF-8 strings into a List<String>.
jobject ToJavaStrings(JNIEnv* env, const std::vector<std::string>& strings);

// Convert the vector of pdfClient rectangles into a projector MatchRects.
jobject ToJavaMatchRects(JNIEnv* env, const std::vector<Rectangle_i>& rects,
                         const vector<int>& match_to_rect, const vector<int>& char_indexes);

// Convert a C++ SelectionBoundary to Java Selection$Boundary.
jobject ToJavaBoundary(JNIEnv* env, const SelectionBoundary& boundary);

// Convert the boundaries and vector of rectangles to a projector Selection.
jobject ToJavaSelection(JNIEnv* env, const int page, const SelectionBoundary& start,
                        const SelectionBoundary& stop, const vector<Rectangle_i>& rects,
                        const std::string& text);

// Convert the vector of pdfClient rectangles into a projector LinkRects.
jobject ToJavaLinkRects(JNIEnv* env, const std::vector<Rectangle_i>& rects,
                        const vector<int>& link_to_rect, const vector<std::string>& urls);

// Convert the pdfClient::Option into a projector ChoiceOption.
jobject ToJavaChoiceOption(JNIEnv* env, const Option& option);

// Obtain a projector WidgetOption value for the widgetType id.
jobject ToJavaWidgetType(JNIEnv* env, int widgetType);

// Convert the pdfClient::FormWidgetInfo into a projector FormWidgetInfo.
jobject ToJavaFormWidgetInfo(JNIEnv* env, const FormWidgetInfo& form_action_result);

// Convert a vector<pdfClient::FormWidgetInfo> into a Java List of projector
// FormWidgetInfo.
jobject ToJavaFormWidgetInfos(JNIEnv* env, const std::vector<FormWidgetInfo>& widget_infos);

}  // namespace convert

#endif  // MEDIAPROVIDER_PDF_JNI_CONVERSION_H_