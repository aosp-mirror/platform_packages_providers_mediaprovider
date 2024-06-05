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
#include "fpdf_formfill.h"
#include "fpdfview.h"
#include "linux_fileops.h"
#include "page.h"
#include "rect.h"
#include "testing/document_utils.h"

using pdfClient::Document;
using pdfClient::FormWidgetInfo;
using pdfClient::LinuxFileOps;
using pdfClient::Page;
using pdfClient::Point_i;
using pdfClient::Rectangle_i;

/*
 * Form filling tests for interactions with click type widgets. These include
 * pushbuttons, checkboxes and radio buttons.
 */
namespace {

const std::string kTestdata = "testdata/formfilling/clickwidgets";
const std::string kClickForm = "click_form.pdf";
const std::string kClickFormCheckEdited = "click_form_check_edited.pdf";
const std::string kClickFormRadioEdited = "click_form_radio_edited.pdf";
const std::string kResetButtonForm = "reset_button_form.pdf";
const std::string kResetButtonFormAfter = "reset_button_form_after.pdf";

const Point_i kReadOnlyCheckboxDeviceCoords = pdfClient::IntPoint(145, 40);
const Point_i kCheckboxDeviceCoords = pdfClient::IntPoint(145, 80);
const Point_i kReadOnlyRadioButtonLeftButtonDeviceCoords = pdfClient::IntPoint(95, 190);
const Point_i kRadioButtonLeftButtonDeviceCoords = pdfClient::IntPoint(95, 240);
const Point_i kResetButtonDeviceCoords = pdfClient::IntPoint(150, 210);

bool RectCoversArea(const Rectangle_i& rect, int left, int top, int right, int bottom) {
    if (left < rect.left) return false;
    if (top < rect.top) return false;
    if (right > rect.right) return false;
    if (bottom > rect.bottom) return false;
    return true;
}

std::unique_ptr<Document> LoadDocument(const std::string file_name) {
    return pdfClient::testing::LoadDocument(
            pdfClient::testing::CreateTestFilePath(file_name, kTestdata));
}

/**
 * GetFormWidgetInfo for a read-only checkbox and check that all data returned
 * in the result matches expected.
 */
TEST(Test, ReadOnlyCheckBoxGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kReadOnlyCheckboxDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_CHECKBOX, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{135, 30, 155, 50};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_TRUE(result.read_only());
    EXPECT_EQ("true", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("readOnlyCheckbox", result.accessibility_label());

    // Not relevant to checkbox.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Send a click action to the coordinates of the read-only checkbox. Should not
 * perform a click or alter the state of the page. Verify that page bitmap has
 * not been changed by this action.
 */
TEST(Test, ReadOnlyCheckBoxClickOnPointDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyCheckboxDeviceCoords));
}

/**
 * Send a click action to the coordinates of the read-only checkbox. Should not
 * perform a click or alter the state of the page. Verify that page is not
 * holding any invalidated rectangles following this action.
 */
TEST(Test, ReadOnlyCheckBoxClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyCheckboxDeviceCoords));
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

/**
 * GetFormWidgetInfo for a checkbox and check that all data returned in the
 * result matches expected.
 */
TEST(Test, CheckBoxGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kCheckboxDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_CHECKBOX, result.widget_type());
    EXPECT_EQ(1, result.widget_index());

    Rectangle_i expected = Rectangle_i{135, 70, 155, 90};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("false", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("checkbox", result.accessibility_label());

    // Not relevant to checkbox.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Click on the checkbox (currently unchecked). Should be checked and the page
 * should display as expected.
 */
TEST(Test, CheckboxClickOnPoint) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(kCheckboxDeviceCoords);
    EXPECT_EQ(FPDF_FORMFIELD_CHECKBOX, fwiInitial.widget_type());
    EXPECT_EQ("false", fwiInitial.text_value());
    ASSERT_TRUE(page_zero->ClickOnPoint(kCheckboxDeviceCoords));
    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(kCheckboxDeviceCoords);
    EXPECT_EQ("true", fwiResult.text_value());
}

/**
 * Click on the checkbox. This action changes state so Page should be holding
 * an invalidated rectangle. At a minimum, should cover the area of the widget
 * we changed.
 */
TEST(Test, CheckboxClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_TRUE(page_zero->ClickOnPoint(kCheckboxDeviceCoords));
    EXPECT_TRUE(page_zero->HasInvalidRect());
    Rectangle_i invalid_rect = page_zero->ConsumeInvalidRect();
    EXPECT_TRUE(RectCoversArea(invalid_rect, 135, 70, 155, 90));
}

/**
 * GetFormWidgetInfo for a reset form button and check that all data returned
 * in the result matches expected.
 */
TEST(Test, ResetButtonGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kResetButtonForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kResetButtonDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_PUSHBUTTON, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{75, 180, 225, 240};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_TRUE(result.text_value().empty());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("ResetButton", result.accessibility_label());

    // Not relevant to push button.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Click on the reset button. All values should be reset to their default DV
 * values and page should display as expected. The text field in this form has
 * DV value "Mouse" and current V value of "Elephant" so should be updated by
 * this action.
 */
TEST(Test, ResetButtonClickOnPoint) {
    std::unique_ptr<Document> doc = LoadDocument(kResetButtonForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(1);
    EXPECT_EQ("Elephant", fwiInitial.text_value());

    EXPECT_TRUE(page_zero->ClickOnPoint(kResetButtonDeviceCoords));

    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(1);
    EXPECT_EQ("Mouse", fwiResult.text_value());
}

/**
 * Click on the reset button. This action changes state so Page should be
 * holding an invalidated rectangle. Reset has the potential to affect all
 * widgets on the page. This form contains one other widget, a text field,
 * which will be reset to its DV value. At a minimum, the rectangle covering
 * that text field should have been invalidated.
 */
TEST(Test, ResetButtonClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kResetButtonForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_TRUE(page_zero->ClickOnPoint(kResetButtonDeviceCoords));
    EXPECT_TRUE(page_zero->HasInvalidRect());
    Rectangle_i invalid_rect = page_zero->ConsumeInvalidRect();
    EXPECT_TRUE(RectCoversArea(invalid_rect, 100, 75, 200, 100));
}

/**
 * GetFormWidgetInfo with the coordinates of the left-most read-only radio
 * button and check that all data returned in the result matches expected.
 */
TEST(Test, ReadOnlyRadioButtonGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kReadOnlyRadioButtonLeftButtonDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_RADIOBUTTON, result.widget_type());
    EXPECT_EQ(2, result.widget_index());

    // Note: this is the coords of the left radio button, not the full set.
    Rectangle_i expected = Rectangle_i{85, 180, 105, 200};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_TRUE(result.read_only());
    EXPECT_EQ("false", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_TRUE(result.accessibility_label().empty());

    // Not relevant to radio button.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Send a click action to the coordinates of the left-most read-only radio
 * button. Should not perform a click or alter the state of the page. Verify
 * that page bitmap has not been changed by this action.
 */
TEST(Test, ReadOnlyRadioButtonClickOnPointDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    ASSERT_FALSE(page_zero->ClickOnPoint(kReadOnlyRadioButtonLeftButtonDeviceCoords));
}

/**
 * Send a click action to the coordinates of the left-most read-only radio
 * button. Should not perform a click or alter the state of the page. Verify
 * that page is not holding any invalidated rectangles following this action.
 */
TEST(Test, ReadOnlyRadioButtonClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyRadioButtonLeftButtonDeviceCoords));
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

/**
 * GetFormWidgetInfo with the coordinates of the left-most radio button and
 * check that all data returned in the result matches expected.
 */
TEST(Test, RadioButtonGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kRadioButtonLeftButtonDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_RADIOBUTTON, result.widget_type());
    EXPECT_EQ(5, result.widget_index());

    // Note: this is the coords of the left radio button, not the full set.
    Rectangle_i expected = Rectangle_i{85, 230, 105, 250};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("false", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_TRUE(result.accessibility_label().empty());

    // Not relevant to radio button.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Click on an unselected option in a set of radio buttons. Selection should
 * be moved to that option, removed from the previous option and the page
 * should display as expected.
 */
TEST(Test, RadioButtonClickOnPoint) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(kRadioButtonLeftButtonDeviceCoords);
    EXPECT_EQ("false", fwiInitial.text_value());

    EXPECT_TRUE(page_zero->ClickOnPoint(kRadioButtonLeftButtonDeviceCoords));

    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(kRadioButtonLeftButtonDeviceCoords);
    EXPECT_EQ("true", fwiResult.text_value());
}
/**
 * Click on an unselected option in a set of radio buttons. This action changes
 * state so Page should be holding an invalidated rectangle. Both left-most and
 * right-most radio buttons in the group were affected by this change as the
 * former gained value and the latter lost it. At a minimum, should cover the
 * rectangle that includes both those buttons.
 */
TEST(Test, RadioButtonClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kClickForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_TRUE(page_zero->ClickOnPoint(kRadioButtonLeftButtonDeviceCoords));
    EXPECT_TRUE(page_zero->HasInvalidRect());
    Rectangle_i invalid_rect = page_zero->ConsumeInvalidRect();
    EXPECT_TRUE(RectCoversArea(invalid_rect, 85, 230, 205, 250));
}

}  // namespace
