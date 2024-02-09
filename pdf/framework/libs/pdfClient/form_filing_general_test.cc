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

#include <gtest/gtest.h>

#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

#include "document.h"
#include "form_widget_info.h"
#include "page.h"
#include "rect.h"
#include "testing/document_utils.h"
// #include "testing/looks_like.h"
//  #include "image/base/rawimage.h"
#include "fpdf_formfill.h"
#include "fpdfview.h"

using pdfClient::Document;
using pdfClient::FormWidgetInfo;
using pdfClient::Page;
using pdfClient::Point_i;
using pdfClient::Rectangle_i;

/*
 * These tests cover general form filling edge cases. For form operations
 * see the form_filling_<type>_test.cc for the given widget type.
 */
namespace {

const std::string kTestdata = "testdata/formfilling/combobox";
const std::string kFormName = "combobox_form.pdf";
const Point_i kEmptyPointDeviceCoords = pdfClient::IntPoint(0, 0);
const Point_i kComboboxDeviceCoords = pdfClient::IntPoint(150, 235);

std::unique_ptr<Document> LoadDocument(const std::string file_name) {
    return pdfClient::testing::LoadDocument(
            pdfClient::testing::CreateTestFilePath(file_name, kTestdata));
}

/**
 * GetFormWidgetInfo of an empty point on the form and verify that the returned
 * values are as expected.
 */
TEST(Test, GetFormWidgetInfoEmptyPoint) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kEmptyPointDeviceCoords);

    EXPECT_FALSE(result.FoundWidget());
    EXPECT_EQ(-1, result.widget_type());
    EXPECT_EQ(-1, result.widget_index());

    Rectangle_i expected = Rectangle_i{-1, -1, -1, -1};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_TRUE(result.text_value().empty());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_TRUE(result.accessibility_label().empty());

    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Click on an empty point on the form. Should not change state and page bitmap
 * should not have been changed by this action.
 */
// TEST(Test, ClickOnEmptyPointDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kFormName);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     page_zero->ClickOnPoint(kEmptyPointDeviceCoords);
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Call SetChoiceSelection for indices out of range. No error should occur and
 * state of page/page bitmap should not be changed by this action.
 */
// TEST(Test, SetChoiceSelectionInvalidIndexDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kFormName);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {1};
//     EXPECT_FALSE(page_zero->SetChoiceSelection(-1, selected_indices));
//     EXPECT_FALSE(page_zero->SetChoiceSelection(100, selected_indices));
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Call SetFormFieldText for indices out of range. No error should occur and
 * state of page/page bitmap should not be changed by this action.
 */
// TEST(Test, SetTextInvalidIndexDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kFormName);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_FALSE(page_zero->SetFormFieldText(-1, "Valid Text"));
//     EXPECT_FALSE(page_zero->SetFormFieldText(100, "Valid Text"));
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * GetFormWidgetInfo should never change state and therefore should never
 * should never the way the page is rendered.
 */
// TEST(Test, GetFormWidgetInfoDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kFormName);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     page_zero->GetFormWidgetInfo(kComboboxDeviceCoords);
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * GetFormWidgetInfo should never change state and therefore should never
 * result in Page holding invalidated rectangles.
 */
TEST(Test, GetFormWidgetInfoInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    page_zero->GetFormWidgetInfo(kComboboxDeviceCoords);
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

/**
 * GetFormWidgetInfo for a valid form annotation index returns widget info.
 */
TEST(Test, GetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo result = page_zero->GetFormWidgetInfo(0);
    EXPECT_TRUE(result.FoundWidget());
}

/**
 * GetFormWidgetInfo for an invalid annotation index returns an empty widget
 * info and does not cause error.
 */
TEST(Test, GetFormWidgetInfo_InvalidIndex) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo result = page_zero->GetFormWidgetInfo(10);
    EXPECT_FALSE(result.FoundWidget());
}

/**
 * GetFormWidgetInfo should never change state and therefore should never
 * result in Page holding invalidated rectangles.
 */
TEST(Test, GetFormWidgetInfo_InvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo result = page_zero->GetFormWidgetInfo(0);
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

/**
 * Get all form widgets on the page via GetFormWidgetInfos.
 */
TEST(Test, GetFormWidgetInfos) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    std::vector<FormWidgetInfo> widget_infos;
    std::unordered_set<int> noop_type_filter;
    page_zero->GetFormWidgetInfos(noop_type_filter, &widget_infos);
    EXPECT_EQ(3, widget_infos.size());

    // Just do a very basic check to make sure they contain data.
    for (const auto& widget_info : widget_infos) {
        EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, widget_info.widget_type());
    }
}

/**
 * GetFormWidgetInfos using a type filter.
 */
TEST(Test, GetFormWidgetInfos_Filtering) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    std::vector<FormWidgetInfo> combo_widget_infos;
    std::unordered_set<int> combobox_filter = {FPDF_FORMFIELD_COMBOBOX};
    page_zero->GetFormWidgetInfos(combobox_filter, &combo_widget_infos);
    EXPECT_EQ(3, combo_widget_infos.size());

    for (const auto& widget_info : combo_widget_infos) {
        EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, widget_info.widget_type());
    }

    std::vector<FormWidgetInfo> widget_infos;
    std::unordered_set<int> listbox_filter = {FPDF_FORMFIELD_LISTBOX};
    page_zero->GetFormWidgetInfos(listbox_filter, &widget_infos);
    EXPECT_EQ(0, widget_infos.size());
}

/**
 * GetFormWidgetInfos should never change state and therefore should never
 * result in Page holding invalidated rectangles.
 */
TEST(Test, GetFormWidgetInfos_InvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kFormName);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    std::vector<FormWidgetInfo> widget_infos;
    std::unordered_set<int> noop_type_filter;
    page_zero->GetFormWidgetInfos(noop_type_filter, &widget_infos);
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

}  // namespace