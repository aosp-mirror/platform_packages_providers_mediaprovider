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

#include <android-base/file.h>
#include <gtest/gtest.h>

#include <memory>
#include <string>
#include <vector>

// Goes first due to conflicts.
#include "document.h"
#include "page.h"
#include "rect.h"
// #include "file/base/path.h"
#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

using pdfClient::Document;
using pdfClient::Page;
using pdfClient::Rectangle_i;

namespace {

static const std::string kTestdata = "testdata";
static const std::string kLinksFile = "sample_links.pdf";

int Area(const Rectangle_i& rect) {
    return rect.Width() * rect.Height();
}

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

ScopedFPDFDocument LoadTestDocument(const std::string filename) {
    return ScopedFPDFDocument(FPDF_LoadDocument(GetTestFile(filename).c_str(), nullptr));
}

TEST(Test, GetLinksUtf8) {
    Document doc(LoadTestDocument(kLinksFile), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    std::vector<Rectangle_i> rects;
    std::vector<int> link_to_rect;
    std::vector<std::string> urls;
    page->GetLinksUtf8(&rects, &link_to_rect, &urls);

    EXPECT_EQ(1, rects.size());
    EXPECT_GT(Area(rects[0]), 0);

    EXPECT_EQ(1, urls.size());
    EXPECT_EQ("http://www.antennahouse.com/purchase.htm", urls[0]);

    EXPECT_EQ(1, link_to_rect.size());
    EXPECT_EQ(0, link_to_rect[0]);
}

}  // namespace

// int main(int argc, char** argv) {
//     ::testing::InitGoogleTest(&argc, argv);
//     FPDF_InitLibrary();
//     int status = RUN_ALL_TESTS();
//     // Destroy the library to keep the memory leak checker happy.
//     FPDF_DestroyLibrary();
//     return status;
// }