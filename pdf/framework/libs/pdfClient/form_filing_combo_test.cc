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
 * Form filling tests for interactions with Combobox widgets.
 */
namespace {

const char kTestdata[] = "testdata/formfilling/combobox";

const std::string kComboboxForm = "combobox_form.pdf";
const std::string kComboboxFormUneditableIndexSelected =
        "combobox_form_uneditable_index_selected.pdf";
const std::string kComboboxFormEditableIndexSelected = "combobox_form_editable_index_selected.pdf";
const std::string kComboboxFormEditableTextEdited = "combobox_form_editable_text_edited.pdf";

const Point_i kReadOnlyLocationDeviceCoords = pdfClient::IntPoint(150, 85);
const Point_i kUneditableLocationDeviceCoords = pdfClient::IntPoint(150, 185);
const Point_i kEditableLocationDeviceCoords = pdfClient::IntPoint(150, 235);

const float kFontSizeComparisonDelta = 0.1;

std::unique_ptr<Document> LoadDocument(const std::string file_name) {
    return pdfClient::testing::LoadDocument(
            pdfClient::testing::CreateTestFilePath(file_name, kTestdata));
}

/**
 * Try to set the text of a read-only combobox.
 * No change should be made and the end result should look identical to
 * pre-editing.
 */
TEST(Test, ComboboxReadOnlySetTextDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->SetFormFieldText(0, "Custom Text"));
}

/**
 * Try to set selected indices of a read-only combobox.
 * No change should be made and the end result should look identical to
 * pre-editing.
 */
TEST(Test, ComboboxReadOnlySetChoiceSelectionDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    std::vector<int> selected_indices = {0};
    EXPECT_FALSE(page_zero->SetChoiceSelection(0, selected_indices));
}

/**
 * GetFormWidgetInfo for a read only combobox and check that all data returned
 * in the result matches expected.
 */
TEST(Test, ComboboxReadOnlyGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kReadOnlyLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, result.widget_type());
    EXPECT_EQ(0, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 70, 200, 100};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_TRUE(result.read_only());
    EXPECT_EQ("Frog", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("Combo_ReadOnly", result.accessibility_label());

    // We should not waste time populating options for read-only boxes since we
    // can't change them.
    EXPECT_FALSE(result.HasOptions());
    EXPECT_EQ(0, result.OptionCount());
    EXPECT_TRUE(result.options().empty());
}

/**
 * Try to set the text of an uneditable combobox.
 * No change should be made and the end result should look identical to
 * pre-editing.
 */
TEST(Test, ComboboxUneditableSetTextDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    EXPECT_FALSE(page_zero->SetFormFieldText(1, "Custom Text"));
}

/**
 * Try to set selected index of an uneditable combobox.
 * Selection should be made and end result should display like expected file.
 */
TEST(Test, ComboboxUneditableSetChoiceSelection) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(1);
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, fwiInitial.widget_type());
    EXPECT_EQ("Banana", fwiInitial.text_value());

    std::vector<int> selected_indices = {17};  // select "Raspberry"
    EXPECT_TRUE(page_zero->SetChoiceSelection(1, selected_indices));

    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(1);
    EXPECT_EQ("Raspberry", fwiResult.text_value());
}

/**
 * GetFormWidgetInfo for an uneditable combobox and check that all data
 * returned in the result matches expected.
 */
TEST(Test, ComboboxUneditableGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kUneditableLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, result.widget_type());
    EXPECT_EQ(1, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 170, 200, 200};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("Banana", result.text_value());
    EXPECT_FALSE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_EQ(0.0, result.font_size());
    EXPECT_EQ("Combo1", result.accessibility_label());

    EXPECT_TRUE(result.HasOptions());
    EXPECT_EQ(26, result.OptionCount());
}

/**
 * Try to set the text of an editable combobox.
 * Text should be set and end result should display like expected file.
 */
TEST(Test, ComboboxEditableSetText) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(2);
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, fwiInitial.widget_type());
    EXPECT_EQ("", fwiInitial.text_value());

    EXPECT_TRUE(page_zero->SetFormFieldText(2, "Custom Text"));

    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(2);
    EXPECT_EQ("Custom Text", fwiResult.text_value());
}

/**
 * Try to set selected index of an editable combobox.
 * Selection should be made and end result should display like expected file.
 */
TEST(Test, ComboboxEditableSetChoiceSelection) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    FormWidgetInfo fwiInitial = page_zero->GetFormWidgetInfo(2);
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, fwiInitial.widget_type());
    EXPECT_EQ("", fwiInitial.text_value());

    std::vector<int> selected_indices = {1};  // select "Bar"
    EXPECT_TRUE(page_zero->SetChoiceSelection(2, selected_indices));

    FormWidgetInfo fwiResult = page_zero->GetFormWidgetInfo(2);
    EXPECT_EQ("Bar", fwiResult.text_value());
}

/**
 * GetFormWidgetInfo for an editable combobox and check that all data returned
 * in the result matches expected.
 */
TEST(Test, ComboboxEditableGetFormWidgetInfo) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    FormWidgetInfo result = page_zero->GetFormWidgetInfo(kEditableLocationDeviceCoords);

    EXPECT_TRUE(result.FoundWidget());
    EXPECT_EQ(FPDF_FORMFIELD_COMBOBOX, result.widget_type());
    EXPECT_EQ(2, result.widget_index());

    Rectangle_i expected = Rectangle_i{100, 220, 200, 250};
    ASSERT_EQ(expected, result.widget_rect());

    EXPECT_FALSE(result.read_only());
    EXPECT_EQ("", result.text_value());
    EXPECT_TRUE(result.editable_text());
    EXPECT_FALSE(result.multiselect());
    EXPECT_FALSE(result.multi_line_text());
    EXPECT_EQ(-1, result.max_length());
    EXPECT_TRUE(std::abs(12.0 - result.font_size()) < kFontSizeComparisonDelta);
    EXPECT_EQ("Combo_Editable", result.accessibility_label());

    EXPECT_TRUE(result.HasOptions());
    EXPECT_EQ(3, result.OptionCount());
}

/**
 * Try to set deselect all indices of the comboboxes.
 * No changes should be made and the end result should look identical to
 * pre-editing.
 */
TEST(Test, ComboboxSetChoiceSelectionInvalidEmptyListDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    std::vector<int> selected_indices;
    EXPECT_FALSE(page_zero->SetChoiceSelection(0, selected_indices));
    EXPECT_FALSE(page_zero->SetChoiceSelection(1, selected_indices));
    EXPECT_FALSE(page_zero->SetChoiceSelection(2, selected_indices));
}

/**
 * Try to set more than one index as selected in the comboboxes.
 * No changes should be made and the end result should look identical to
 * pre-editing.
 */
TEST(Test, ComboboxSetChoiceSelectionInvalidMoreThanOneSelectedDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);
    std::vector<int> selected_indices = {0, 1};

    EXPECT_FALSE(page_zero->SetChoiceSelection(0, selected_indices));
    EXPECT_FALSE(page_zero->SetChoiceSelection(1, selected_indices));
    EXPECT_FALSE(page_zero->SetChoiceSelection(2, selected_indices));
}

/**
 * Clicking on combobox widgets should always be a no-op and never change the
 * rendering of the page.
 */
TEST(Test, ComboboxClickOnPointDoesNotChangePage) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kUneditableLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kEditableLocationDeviceCoords));
}

/**
 * Clicking on combobox widgets should always be a no-op and never result in
 * Page holding invalidated rectangles.
 */
TEST(Test, ComboboxClickOnPointInvalidRects) {
    std::unique_ptr<Document> doc = LoadDocument(kComboboxForm);
    std::shared_ptr<Page> page_zero = doc->GetPage(0, true);

    EXPECT_FALSE(page_zero->ClickOnPoint(kReadOnlyLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kUneditableLocationDeviceCoords));
    EXPECT_FALSE(page_zero->ClickOnPoint(kEditableLocationDeviceCoords));
    EXPECT_FALSE(page_zero->HasInvalidRect());
}

}  // namespace
