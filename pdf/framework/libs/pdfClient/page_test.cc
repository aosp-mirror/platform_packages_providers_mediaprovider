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
#include <android/api-level.h>
#include <gtest/gtest.h>

#include <memory>
#include <string>

#include "page_object.h"

// Goes first due to conflicts.
#include "document.h"
#include "rect.h"
// #include "file/base/path.h"
#include "cpp/fpdf_scopers.h"
#include "fpdfview.h"

namespace {

using ::pdfClient::Document;
using ::pdfClient::ImageObject;
using ::pdfClient::Page;
using ::pdfClient::PageObject;
using ::pdfClient::Rectangle_i;

static const std::string kTestdata = "testdata";
static const std::string kSekretNoPassword = "sekret_no_password.pdf";
static const std::string kImageObject = "image_object.pdf";

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

TEST(Test, InvalidPageNumberTest) {
    if (android_get_device_api_level() < __ANDROID_API_S__) {
        // Pdf client is not exposed on R and for some unknown reason this test fails
        // on non-64 architectures. See - b/381994039
        // We could disable all the tests here for R.
        GTEST_SKIP();
    }
    Document doc(LoadTestDocument(kSekretNoPassword), false);
    // The document has only one page but we fetch the second one.
    std::shared_ptr<Page> page = doc.GetPage(1);

    // The above call succeeds and returns a non-null ptr.
    ASSERT_NE(nullptr, page);
    // Even though the underlying pointer is null.
    ASSERT_EQ(nullptr, page->page());

    // Rest of the calls should give some default values.
    EXPECT_EQ(-1, page->NumChars());
    std::string str_with_null = "\0";
    EXPECT_EQ(1, page->GetTextUtf8().size());  // Returns "\0"
    EXPECT_EQ('\0', page->GetUnicode(0));
    EXPECT_EQ(0, page->Width());
    EXPECT_EQ(0, page->Height());

    Rectangle_i expected = Rectangle_i{0, 0, 0, 0};
    EXPECT_EQ(expected, page->Dimensions());
    EXPECT_EQ(false, page->HasInvalidRect());
    EXPECT_EQ(0, page->GetGotoLinks().size());
    // The following should not crash, we do not expect anything in return.
    page->InitializeFormFilling();
    page->TerminateFormFilling();
}

TEST(Test, GetPageObjectsTest) {
    Document doc(LoadTestDocument(kImageObject), false);

    std::shared_ptr<Page> page = doc.GetPage(0);
    std::vector<PageObject*> pageObjects = page->GetPageObjects();

    // Check for PageObjects size.
    ASSERT_EQ(1, pageObjects.size());
    // Check for the PageObject to be ImageObject.
    ASSERT_EQ(PageObject::Type::Image, pageObjects[0]->GetType());
}

TEST(Test, AddPageObjectTest) {
    Document doc(LoadTestDocument(kImageObject), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    std::vector<PageObject*> initialPageObjects = page->GetPageObjects();

    // Create Image Object.
    auto imageObject = std::make_unique<ImageObject>();

    // Create FPDF Bitmap.
    FPDF_BITMAP bitmap = FPDFBitmap_Create(6000, 4000, 1);
    FPDFBitmap_FillRect(bitmap, 0, 0, 3000, 4000, 0xFF000000);
    imageObject->bitmap = bitmap;

    // Set Matrix.
    imageObject->matrix = {1.0f, 0, 0, 1.0f, 0, 0};

    // Add the page object.
    ASSERT_EQ(page->AddPageObject(std::move(imageObject)), initialPageObjects.size());

    // Get Updated PageObjects
    std::vector<PageObject*> updatedPageObjects = page->GetPageObjects(true);

    // Assert that the size has increased by one.
    ASSERT_EQ(initialPageObjects.size() + 1, updatedPageObjects.size());
}

TEST(Test, RemovePageObjectTest) {
    Document doc(LoadTestDocument(kImageObject), false);

    std::shared_ptr<Page> page = doc.GetPage(0);

    std::vector<PageObject*> initialPageObjects = page->GetPageObjects();

    // Remove a pageObject
    EXPECT_TRUE(page->RemovePageObject(0));
    // Get Updated PageObjects after removal
    std::vector<PageObject*> updatedPageObjects = page->GetPageObjects(true);

    ASSERT_EQ(initialPageObjects.size() - 1, updatedPageObjects.size());
}

TEST(Test, UpdatePageObjectTest) {
    Document doc(LoadTestDocument(kImageObject), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    // Get initial page objects.
    std::vector<PageObject*> initialPageObjects = page->GetPageObjects();

    // Create Image Object.
    auto imageObject = std::make_unique<ImageObject>();

    // Create FPDF Bitmap.
    FPDF_BITMAP bitmap = FPDFBitmap_Create(6000, 4000, 1);
    FPDFBitmap_FillRect(bitmap, 0, 0, 6000, 4000, 0xFF0000FF);
    imageObject->bitmap = bitmap;

    // Set Matrix.
    imageObject->matrix = {2.0f, 0, 0, 2.0f, 0, 0};

    // Update the page object.
    EXPECT_TRUE(page->UpdatePageObject(0, std::move(imageObject)));

    // Get the updated page objects.
    std::vector<PageObject*> updatedPageObjects = page->GetPageObjects(true);

    // Check for size equality.
    ASSERT_EQ(initialPageObjects.size(), updatedPageObjects.size());

    // Check for updated bitmap.
    ASSERT_EQ(FPDFBitmap_GetWidth(updatedPageObjects[0]->AsImage()->bitmap), 6000);
    ASSERT_EQ(FPDFBitmap_GetHeight(updatedPageObjects[0]->AsImage()->bitmap), 4000);

    // Check for updated matrix.
    ASSERT_EQ(updatedPageObjects[0]->matrix.a, 2.0f);
    ASSERT_EQ(updatedPageObjects[0]->matrix.b, 0.0f);
    ASSERT_EQ(updatedPageObjects[0]->matrix.c, 0.0f);
    ASSERT_EQ(updatedPageObjects[0]->matrix.d, 2.0f);
    ASSERT_EQ(updatedPageObjects[0]->matrix.e, 0.0f);
    ASSERT_EQ(updatedPageObjects[0]->matrix.f, 0.0f);
}

}  // namespace