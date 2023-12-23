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
 * Form filling tests for interactions with Listbox widgets.
 */
namespace {

const std::string kTestdata = "testdata/formfilling/listbox";

const std::string kListboxForm = "listbox_form.pdf";
const std::string kListboxFormCleared = "listbox_form_cleared.pdf";
const std::string kListboxFormIndexSelected = "listbox_form_index_selected.pdf";
const std::string kListboxFormMultiCleared = "listbox_form_multi_cleared.pdf";
const std::string kListboxFormMultiIndexSelected = "listbox_form_multi_index_selected.pdf";
const std::string kListboxFormMultiIndicesSelected = "listbox_form_multi_indices_selected.pdf";

const Point_i kReadOnlyLocationDeviceCoords = pdfClient::IntPoint(150, 85);
const Point_i kMultiSelectLocationDeviceCoords = pdfClient::IntPoint(150, 235);
const Point_i kGeneralLocationDeviceCoords = pdfClient::IntPoint(150, 360);

std::unique_ptr<Document> LoadDocument(const std::string file_name) {
    return pdfClient::testing::LoadDocument(
            pdfClient::testing::CreateTestFilePath(file_name, kTestdata));
}

/**
 * Try to set selected indices of a read-only listbox.
 * No change should be made and the end result should look identical to
 * pre-editing.
 */
// TEST(Test, ListboxReadOnlySetChoiceSelectionDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {0};
//     EXPECT_FALSE(page_zero->SetChoiceSelection(0, selected_indices));
//
//     std::unique_ptr<RawImage> edited_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *edited_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * GetFormWidgetInfo for a read only listbox and check that all data returned
 * in the result matches expected.
 */
TEST(Test, ListboxReadOnlyGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kReadOnlyLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_LISTBOX, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 70, 200, 100};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_TRUE(result.read_only());
    EXPECT_TRUE(result.text_value().empty());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("Listbox_ReadOnly", result.accessibility_label());

    // We should not waste time populating options for read-only boxes since we
    // can't change them.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Try to set selected index of a general (non-multiselect) listbox.
 * Selection should be made and end result should display like expected file.
 */
// TEST(Test, ListboxGeneralSetChoiceSelection) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {2};  // select "Qux"
//     EXPECT_TRUE(page_zero->SetChoiceSelection(2, selected_indices));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kListboxFormIndexSelected);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Try to set multiple indices selected in a general (non-multiselect) listbox.
 * No change should be made and the end result should look identical to
 * pre-editing.
 */
// TEST(Test, ListboxGeneralSetChoiceMultipleSelectionDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {1, 2};
//     EXPECT_FALSE(page_zero->SetChoiceSelection(2, selected_indices));
//
//     std::unique_ptr<RawImage> edited_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *edited_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Try to deselect all indices of a general (non-multiselect) listbox.
 * All selections should be cleared and end result should display like
 * expected file.
 */
// TEST(Test, ListboxGeneralClearSelection) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices;  // Nothing selected.
//     EXPECT_TRUE(page_zero->SetChoiceSelection(2, selected_indices));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kListboxFormCleared);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * GetFormWidgetInfo for a general (non-multiselect) listbox and check that all
 * data returned in the result matches expected.
 */
TEST(Test, ListboxGeneralGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kGeneralLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_LISTBOX, result.widget_type());
    EXPECT_EQ(2, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 320, 200, 400};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Foo", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("Listbox_SingleSelect", result.accessibility_label());

    EXPECT_TRUE(result.HasOptions());
    EXPECT_EQ(3, result.OptionCount());
}

/**
 * Try to set selected index of a multiselect listbox.
 * Selection should be made and end result should display like expected file.
 */
// TEST(Test, ListboxMultiSelectSetChoiceSelection) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {5};  // select "Fig"
//     EXPECT_TRUE(page_zero->SetChoiceSelection(1, selected_indices));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kListboxFormMultiIndexSelected);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Try to set multiple indices selected in a multiselect listbox.
 * Selections should be made and end result should display like expected file.
 */
// TEST(Test, ListboxMultiSelectSetChoiceMultipleSelection) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {3, 4, 7};
//     EXPECT_TRUE(page_zero->SetChoiceSelection(1, selected_indices));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kListboxFormMultiIndicesSelected);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Try to deselect all indices of a multiselect listbox.
 * All selections should be cleared and end result should display like
 * expected file.
 */
// TEST(Test, ListboxMultiSelectClearSelection) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices;  // Nothing selected.
//     EXPECT_TRUE(page_zero->SetChoiceSelection(1, selected_indices));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kListboxFormMultiCleared);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * GetFormWidgetInfo for a multiselect listbox and check that all data
 * returned in the result matches expected.
 */
TEST(Test, ListboxMultiSelectGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kMultiSelectLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_LISTBOX, result.widget_type());
    EXPECT_EQ(1, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 170, 200, 300};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Banana", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_TRUE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("Listbox_MultiSelect", result.accessibility_label());

    EXPECT_TRUE(result.HasOptions());
    EXPECT_EQ(26, result.OptionCount());
}

/**
 * Listboxes do not have editable text. Verify that setting text in these
 * widgets is a no-op.
 */
// TEST(Test, ListboxSetTextInvalidDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*page_zero);
//
//     std::string test_text = "Test Text";
//     EXPECT_FALSE(page_zero->SetFormFieldText(0, test_text));
//     EXPECT_FALSE(page_zero->SetFormFieldText(1, test_text));
//     EXPECT_FALSE(page_zero->SetFormFieldText(2, test_text));
//
//     std::unique_ptr<RawImage> edited_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *edited_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Clicking on listbox widgets should always be a no-op and never change the
 * rendering of the page.
 */
// TEST(Test, ListboxClickOnPointDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
//     EXPECT_FALSE(page_zero->ClickOnPoint(kGeneralLocationDeviceCoords));
//     EXPECT_FALSE(page_zero->ClickOnPoint(kMultiSelectLocationDeviceCoords));
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Clicking on listbox widgets should always be a no-op and never result in
 * Page holding invalidated rectangles.
 */
TEST(Test, ListboxClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kListboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kGeneralLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kMultiSelectLocationDeviceCoords));
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

}  // namespace