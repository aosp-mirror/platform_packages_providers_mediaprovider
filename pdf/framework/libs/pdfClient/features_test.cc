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

#include "features.h"

#include <android-base/file.h>
#include <gtest/gtest.h>

#include <memory>
#include <string>

// Goes first due to conflicts.
#include "document.h"
// #include "file/base/path.h"
#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

using pdfClient::Document;
using pdfClient::Feature;

namespace {

static const std::string kTestdata = "testdata";
static const std::string kFormFile = "offer.pdf";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

ScopedFPDFDocument LoadTestDocument(const std::string filename) {
    return ScopedFPDFDocument(FPDF_LoadDocument(GetTestFile(filename).c_str(), nullptr));
}

TEST(Test, CountFieldsAndControls) {
    Document doc(LoadTestDocument(kFormFile), false);

    EXPECT_EQ(Feature::FORM_TEXT_FIELD | Feature::FORM_BUTTON | Feature::ANNOTATION_SHAPE,
              doc.GetPage(0)->GetFeatures());
    EXPECT_EQ(Feature::FORM_TEXT_FIELD | Feature::FORM_BUTTON, doc.GetPage(1)->GetFeatures());
}

}  // namespace