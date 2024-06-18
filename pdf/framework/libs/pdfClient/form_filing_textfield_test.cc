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

#include <cstdlib>
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
 * Form filling tests for interactions with Text Field widgets.
 */
namespace {

const std::string kTestdata = "testdata/formfilling/textfield";

const std::string kTextForm = "text_form.pdf";
const std::string kTextFormEdited = "text_form_edited.pdf";
const std::string kTextFormCleared = "text_form_cleared.pdf";
const std::string kTextFormCharLimitEdited = "text_form_charlimit_edited.pdf";
const std::string kTextFormMultiLine = "text_form_multi_line.pdf";

const Point_i kReadOnlyLocationDeviceCoords = pdfClient::IntPoint(150, 85);
const Point_i kGeneralLocationDeviceCoords = pdfClient::IntPoint(150, 185);
const Point_i kCharLimitLocationDeviceCoords = pdfClient::IntPoint(150, 235);
const Point_i kMultiLineLocationDeviceCoords = pdfClient::IntPoint(150, 70);

const float kFontSizeComparisonDelta = 0.1;

std::unique_ptr<Document> LoadDocument(const std::string file_name) {
    return pdfClient::testing::LoadDocument(
            pdfClient::testing::CreateTestFilePath(file_name, kTestdata));
}

/**
 * Try to set the text of a read-only text field. No change should be made and
 * the end result should look identical to pre-editing.
 */
// TEST(Test, TextFieldReadOnlySetTextDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_FALSE(page_zero->SetFormFieldText(0, "Some New Text"));
//
//     std::unique_ptr<RawImage> edited_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *edited_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Send a click action to the coordinates of the read only text field and check
 * that all data returned in the result matches expected.
 */
TEST(Test, TextFieldReadOnlyGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kTextForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kReadOnlyLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_TEXTFIELD, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 70, 200, 100};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_TRUE(result.read_only());
    EXPECT_EQ("Mountain Lion", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ("ReadOnly", result.accessibility_label());

    // Not relevant to text field.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Set the text of a text field. The text should be set in the field and it
 * should display like the expected file.
 */
// TEST(Test, TextFieldSetText) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_TRUE(page_zero->SetFormFieldText(1, "Gecko taillllllllll"));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kTextFormEdited);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Clear the text of a text field by setting it to empty string. The field
 * should be cleared and it should display like the expected file.
 */
// TEST(Test, TextFieldClearText) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_TRUE(page_zero->SetFormFieldText(1, ""));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kTextFormCleared);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Send a click action to the coordinates of the text field and check
 * that all data returned in the result matches expected.
 */
TEST(Test, TextFieldGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kTextForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kGeneralLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_TEXTFIELD, result.widget_type());
    EXPECT_EQ(1, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 170, 200, 200};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Chameleon", result.text_value());
    EXPECT_TRUE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_TRUE(std::abs(12.0 - result.font_size()) < kFontSizeComparisonDelta);
    EXPECT_EQ("Text Box", result.accessibility_label());

    // Not relevant to text field.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Try to set the text of a text field with a char limit using a string longer
 * than the char limit. The substring from index (0, charLimit) should be
 * set in the field and it should display like the expected file.
 */
// TEST(Test, TextFieldCharLimitSetTextOverLimitTest) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> image_before_editing = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_TRUE(page_zero->SetFormFieldText(2, "Gecko taillllllllll"));
//
//     std::unique_ptr<RawImage> image_after_editing = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_FALSE(pdfClient::testing::LooksLike(*image_before_editing, *image_after_editing,
//                                            pdfClient::testing::kZeroToleranceDifference));
//
//     std::unique_ptr<Document> expected_doc = LoadDocument(kTextFormCharLimitEdited);
//     std::shared_ptr<Page> expected_page_zero = expected_doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> expected_image = pdfClient::testing::RenderPage(*expected_page_zero);
//
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*expected_image, *image_after_editing,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Send a click action to the coordinates of the text field with a character
 * limit and check that all data returned in the result matches expected.
 */
TEST(Test, TextFieldCharLimitGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kTextForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kCharLimitLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_TEXTFIELD, result.widget_type());
    EXPECT_EQ(2, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 225, 200, 250};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Elephant", result.text_value());
    EXPECT_TRUE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(10, result.max_length());
    EXPECT_TRUE(std::abs(12.0 - result.font_size()) < kFontSizeComparisonDelta);
    EXPECT_EQ("CharLimit", result.accessibility_label());

    // Not relevant to text field
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Send a click action to the coordinates of the multi-line text field and
 * check that all data returned in the result matches expected.
 */
TEST(Test, TextFieldMultiLineGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kTextFormMultiLine);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kMultiLineLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_TEXTFIELD, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 40, 200, 100};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Mountain\r\nLion", result.text_value());
    EXPECT_TRUE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_TRUE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_TRUE(std::abs(12.0 - result.font_size()) < kFontSizeComparisonDelta);
    EXPECT_EQ("ReadOnly", result.accessibility_label());

    // Not relevant to text field
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Try to call SetChoiceSelection on text fields. Should do nothing and should
 * not alter the state of the page. Verify that page bitmap has not been
 * changed by these actions.
 */
// TEST(Test, TextFieldSetChoiceSelectionDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     std::vector<int> selected_indices = {0};
//     page_zero->SetChoiceSelection(0, selected_indices);
//     page_zero->SetChoiceSelection(1, selected_indices);
//     page_zero->SetChoiceSelection(2, selected_indices);
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Clicking on textfield widgets should always be a no-op and never change the
 * rendering of the page.
 */
// TEST(Test, TextFieldClickOnPointDoesNotChangePage) {
//     std::unique_ptr<Document> doc = LoadDocument(kTextForm);
//     std::shared_ptr<Page> page_zero = doc->GetPage(0, true, true);
//     std::unique_ptr<RawImage> starting_image = pdfClient::testing::RenderPage(*page_zero);
//
//     EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
//     EXPECT_FALSE(page_zero->ClickOnPoint(kGeneralLocationDeviceCoords));
//     EXPECT_FALSE(page_zero->ClickOnPoint(kCharLimitLocationDeviceCoords));
//
//     std::unique_ptr<RawImage> after_click_image = pdfClient::testing::RenderPage(*page_zero);
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*starting_image, *after_click_image,
//                                           pdfClient::testing::kZeroToleranceDifference));
// }

/**
 * Clicking on textfield widgets should always be a no-op and never result in
 * Page holding invalidated rectangles.
 */
TEST(Test, TextFieldClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kTextForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kGeneralLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kCharLimitLocationDeviceCoords));
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

}  // namespace