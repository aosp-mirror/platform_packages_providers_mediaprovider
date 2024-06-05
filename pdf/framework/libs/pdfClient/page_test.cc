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

#include "page.h"

#include <android-base/file.h>
#include <gtest/gtest.h>

#include <memory>
#include <string>

// Goes first due to conflicts.
#include "document.h"
#include "rect.h"
// #include "file/base/path.h"
#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

namespace {

using ::pdfClient::Document;
using ::pdfClient::Page;
using ::pdfClient::Rectangle_i;

static const std::string kTestdata = "testdata";
static const std::string kSekretNoPassword = "sekret_no_password.pdf";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

ScopedFPDFDocument LoadTestDocument(const std::string filename) {
    return ScopedFPDFDocument(FPDF_LoadDocument(GetTestFile(filename).c_str(), nullptr));
}

// Note on coordinates used in below tests:
// This document has height == 792. Due to constraints of rect.h functions
// that require top < bottom, top/bottom are flipped from what page
// coordinates normally would be in these examples. So expected values when
// we consume the rectangles in this test are: top = (792 - bottom),
// bottom = (792 - top).

/*
 * Test that when a single rectangle is passed to NotifyInvalidRect
 * invalid_rect_ will match its coordinates.
 */
TEST(Test, NotifyInvalidRectSingleRectTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);

    std::shared_ptr<Page> page = doc.GetPage(0);
    EXPECT_FALSE(page->HasInvalidRect());
    page->NotifyInvalidRect(pdfClient::IntRect(100, 100, 200, 200));

    EXPECT_TRUE(page->HasInvalidRect());
    Rectangle_i expected = Rectangle_i{100, 592, 200, 692};
    ASSERT_EQ(expected, page->ConsumeInvalidRect());
}

/*
 * Tests the coalescing of rectangles. Result should be the minimal rectangle
 * that covers all rectangles that have been added.
 */
TEST(Test, NotifyInvalidRectCoalesceTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);

    std::shared_ptr<Page> page = doc.GetPage(0);
    EXPECT_FALSE(page->HasInvalidRect());

    page->NotifyInvalidRect(pdfClient::IntRect(100, 100, 200, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(400, 100, 500, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(100, 400, 200, 500));
    EXPECT_TRUE(page->HasInvalidRect());
    Rectangle_i expected = Rectangle_i{100, 292, 500, 692};
    ASSERT_EQ(expected, page->ConsumeInvalidRect());
}

/*
 * Tests adding a rectangle to invalid_rect_ whose area is already covered by
 * the existing rect. Should not change boundaries.
 */
TEST(Test, NotifyInvalidRectAlreadyCoveredTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);

    std::shared_ptr<Page> page = doc.GetPage(0);
    EXPECT_FALSE(page->HasInvalidRect());

    page->NotifyInvalidRect(pdfClient::IntRect(100, 100, 200, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(400, 100, 500, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(100, 400, 200, 500));
    // add a rectangle that's already covered by existing one
    page->NotifyInvalidRect(pdfClient::IntRect(400, 400, 500, 500));
    EXPECT_TRUE(page->HasInvalidRect());
    Rectangle_i expected = Rectangle_i{100, 292, 500, 692};
    ASSERT_EQ(expected, page->ConsumeInvalidRect());
}

/**
 * Try calling NotifyInvalidRect with negative indices. No error should be
 * thrown. Confirm all rectangles have been ignored by the page.
 */
TEST(Test, NotifyInvalidRectNegativeIndicesTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    page->NotifyInvalidRect(pdfClient::IntRect(-100, 100, 200, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(400, -100, 500, 200));
    page->NotifyInvalidRect(pdfClient::IntRect(100, 400, -200, 500));
    page->NotifyInvalidRect(pdfClient::IntRect(400, 400, 500, -500));
    EXPECT_FALSE(page->HasInvalidRect());
}

/**
 * Try calling NotifyInvalidRect with empty rectangles. No error should be
 * thrown. Confirm all rectangles have been ignored by the page.
 */
TEST(Test, NotifyInvalidRectEmptyRectanglesTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    page->NotifyInvalidRect(pdfClient::IntRect(100, 200, 100, 500));
    page->NotifyInvalidRect(pdfClient::IntRect(100, 400, 500, 400));
    page->NotifyInvalidRect(pdfClient::Rectangle_i{100, 200, 0, 500});
    page->NotifyInvalidRect(pdfClient::Rectangle_i{100, 400, 500, 0});
    EXPECT_FALSE(page->HasInvalidRect());
}

/**
 * Test that calling ConsumeInvalidRect resets the rectangle in the Page.
 */
TEST(Test, ConsumeInvalidRectResetsRectTest) {
    Document doc(LoadTestDocument(kSekretNoPassword), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    // doesn't have one
    EXPECT_FALSE(page->HasInvalidRect());
    page->NotifyInvalidRect(pdfClient::IntRect(100, 100, 200, 200));

    // now has one
    page->NotifyInvalidRect(pdfClient::IntRect(100, 100, 200, 200));
    EXPECT_TRUE(page->HasInvalidRect());

    // no longer has one
    page->ConsumeInvalidRect();
    EXPECT_FALSE(page->HasInvalidRect());

    // if we call Consume anyway we will receive empty rect
    Rectangle_i expected = Rectangle_i{0, 0, 0, 0};
    ASSERT_EQ(expected, page->ConsumeInvalidRect());
}

}  // namespace