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

#include "form_filler.h"

#include <algorithm>
#include <cmath>
#include <memory>
#include <span>
#include <string>
#include <utility>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "document.h"
#include "form_widget_info.h"
#include "fpdf_annot.h"
#include "fpdf_formfill.h"
#include "fpdf_fwlevent.h"
#include "fpdfview.h"
#include "pdfClient_formfillinfo.h"
#include "rect.h"
#include "utils/annot.h"
#include "utils/text.h"
#include "utils/utf.h"

// Utility methods to group common widget type and feature checks.
namespace {
bool IsClickActionType(int type) {
    return type == FPDF_FORMFIELD_PUSHBUTTON || type == FPDF_FORMFIELD_CHECKBOX ||
           type == FPDF_FORMFIELD_RADIOBUTTON;
}

bool IsChoiceType(int type) {
    return type == FPDF_FORMFIELD_COMBOBOX || type == FPDF_FORMFIELD_LISTBOX;
}

bool IsTextFieldType(int type) {
    return type == FPDF_FORMFIELD_TEXTFIELD;
}

bool IsSupportedType(int type) {
    return IsClickActionType(type) || IsChoiceType(type) || IsTextFieldType(type);
}

bool IsCheckType(int type) {
    return type == FPDF_FORMFIELD_CHECKBOX || type == FPDF_FORMFIELD_RADIOBUTTON;
}

bool IsReadOnly(int formfield_flags) {
    return (FPDF_FORMFLAG_READONLY & formfield_flags) != 0;
}

bool IsMultiSelect(int type, int formfield_flags) {
    return type == FPDF_FORMFIELD_LISTBOX && ((1 << 21) & formfield_flags) != 0;
}

bool IsMultiLineText(int type, int formfield_flags) {
    return type == FPDF_FORMFIELD_TEXTFIELD && (FPDF_FORMFLAG_TEXT_MULTILINE & formfield_flags) != 0;
}

bool IsEditableText(int type, int formfield_flags) {
    if (type == FPDF_FORMFIELD_TEXTFIELD) {
        return true;
    }

    if (type != FPDF_FORMFIELD_COMBOBOX) {
        return false;
    }
    return (FPDF_FORMFLAG_CHOICE_EDIT & formfield_flags) != 0;
}
}  // namespace

namespace {}
namespace pdfClient {

static const int kDefaultFormOperationModifiers = 0;
static const int kPdfiumACharacterOffset = 1;  // see CPWL_EditCtrl::OnChar
static const Rectangle_i kDefaultAnnotationRect = IntRect(-1, -1, -1, -1);

FormFiller::FormFiller(Document* document, FPDF_DOCUMENT fpdf_document) : document_(document) {
    // FPDF_FORMFILLINFO interface.
    pdfClient::StubFormFillInfo(this);
    FFI_Invalidate = &Invalidate;
    form_handle_.reset(FPDFDOC_InitFormFillEnvironment(fpdf_document, this));
}

FormFiller::~FormFiller() {}

bool FormFiller::RenderTile(FPDF_PAGE page, FPDF_BITMAP bitmap, FS_MATRIX transform, FS_RECTF clip,
                            int render_mode) const {
    if (form_handle_) {
        // This renders forms - checkboxes, text fields, and annotations.
        FPDF_FFLDrawWithMatrix(form_handle_.get(), bitmap, page, &transform, &clip, render_mode);
        return true;
    }
    return false;
}

void FormFiller::NotifyAfterPageLoad(FPDF_PAGE page) {
    FORM_OnAfterLoadPage(page, form_handle_.get());
}

void FormFiller::NotifyBeforePageClose(FPDF_PAGE page) {
    FORM_OnBeforeClosePage(page, form_handle_.get());
}

FormWidgetInfo FormFiller::GetFormWidgetInfo(FPDF_PAGE page, const Point_d point) {
    ScopedFPDFAnnotation annotation = GetFormAnnotation(page, point);
    return GetFormWidgetInfo(page, annotation.get());
}

FormWidgetInfo FormFiller::GetFormWidgetInfo(FPDF_PAGE page, const int annotation_index) {
    ScopedFPDFAnnotation annotation = GetFormAnnotation(page, annotation_index);
    return GetFormWidgetInfo(page, annotation.get());
}

FormWidgetInfo FormFiller::GetFormWidgetInfo(FPDF_PAGE page, FPDF_ANNOTATION annotation) {
    if (!page || !annotation) {
        return FormWidgetInfo();
    }

    int type = GetFormFieldType(page, annotation);

    // No form filling operation, no index to return.
    if (!IsSupportedType(type)) {
        return FormWidgetInfo();
    }

    int formfield_flags = FPDFAnnot_GetFormFieldFlags(form_handle_.get(), annotation);

    FormWidgetInfo result;
    result.set_widget_type(type);
    result.set_widget_index(GetAnnotationIndex(page, annotation));
    result.set_widget_rect(GetAnnotationRect(annotation));
    result.set_accessibility_label(GetAccessibilityLabel(annotation));

    // No form filling operation permitted, valid widget info to return.
    if (IsReadOnly(formfield_flags)) {
        result.set_read_only(true);
        // Provide the best value we can at this point for screen reading.
        result.set_text_value(GetReadOnlyTextValue(type, annotation));
        return result;
    }

    // We have all the info we need already, return.
    if (IsClickActionType(type)) {
        result.set_text_value(GetReadOnlyTextValue(type, annotation));
        return result;
    }

    SetFormFocus(page, annotation);

    result.set_text_value(pdfClient_utils::FORM_GetFocusedText(form_handle_.get(), page));

    if (IsChoiceType(type)) {
        result.set_options(GetOptions(page, annotation));
        if (type == FPDF_FORMFIELD_LISTBOX) {
            result.set_multiselect(IsMultiSelect(type, formfield_flags));
        }
    }

    bool editable_text = IsEditableText(type, formfield_flags);
    result.set_editable_text(editable_text);

    if (editable_text) {
        result.set_max_length(GetMaxLen(annotation));
        result.set_font_size(GetFontSize(annotation));
    }

    if (type == FPDF_FORMFIELD_TEXTFIELD) {
        result.set_multi_line_text(IsMultiLineText(type, formfield_flags));
    }

    KillFormFocus();

    return result;
}

void FormFiller::GetFormWidgetInfos(FPDF_PAGE page, const std::unordered_set<int>& type_ids,
                                    std::vector<FormWidgetInfo>* widget_infos) {
    std::vector<ScopedFPDFAnnotation> widget_annots;
    pdfClient_utils::GetVisibleAnnotsOfType(page, {FPDF_ANNOT_WIDGET}, &widget_annots);

    if (widget_annots.empty()) {
        return;
    }

    bool filter_by_type = !type_ids.empty();
    for (const auto& widget_annot : widget_annots) {
        if (filter_by_type &&
            !(type_ids.find(GetFormFieldType(page, widget_annot.get())) != type_ids.end())) {
            continue;
        }
        FormWidgetInfo result = GetFormWidgetInfo(page, widget_annot.get());
        if (result.FoundWidget()) {
            widget_infos->push_back(std::move(result));
        }
    }
}

bool FormFiller::ClickOnPoint(FPDF_PAGE page, const Point_d point) {
    int type = GetFormFieldType(page, point);

    if (!IsClickActionType(type)) {
        return false;
    }

    ScopedFPDFAnnotation annotation = GetFormAnnotation(page, point);
    int formfield_flags = FPDFAnnot_GetFormFieldFlags(form_handle_.get(), annotation.get());

    if (IsReadOnly(formfield_flags)) {
        return false;
    }

    PerformClick(page, point);
    KillFormFocus();
    return true;
}

bool FormFiller::SetText(FPDF_PAGE page, const int annotation_index, const std::string_view text) {
    ScopedFPDFAnnotation annotation = GetFormAnnotation(page, annotation_index);
    if (!annotation) {
        return false;
    }

    int formfield_flags = FPDFAnnot_GetFormFieldFlags(form_handle_.get(), annotation.get());

    if (IsReadOnly(formfield_flags)) {
        return false;
    }

    int type = GetFormFieldType(page, annotation.get());
    if (!IsEditableText(type, formfield_flags)) {
        return false;
    }

    SetFormFocus(page, annotation.get());
    SetFieldText(page, text);
    KillFormFocus();

    return true;
}

bool FormFiller::SetChoiceSelection(FPDF_PAGE page, const int annotation_index,
                                    std::span<const int> selected_indices) {
    ScopedFPDFAnnotation annotation = GetFormAnnotation(page, annotation_index);
    if (!annotation) {
        return false;
    }

    int type = GetFormFieldType(page, annotation.get());
    int formfield_flags = FPDFAnnot_GetFormFieldFlags(form_handle_.get(), annotation.get());

    if (!IsChoiceType(type) || IsReadOnly(formfield_flags)) {
        return false;
    }

    int option_count = FPDFAnnot_GetOptionCount(form_handle_.get(), annotation.get());

    // Confirm all requested indices valid.
    for (int i = 0; i < selected_indices.size(); i++) {
        if (selected_indices[i] < 0 || selected_indices[i] >= option_count) {
            return false;
        }
    }

    // Combobox must have exactly one selection.
    if (type == FPDF_FORMFIELD_COMBOBOX && selected_indices.size() != 1) {
        return false;
    }

    // Non-multiselect Listbox must have 0 or 1 selections.
    if (type == FPDF_FORMFIELD_LISTBOX && !IsMultiSelect(type, formfield_flags) &&
        selected_indices.size() > 1) {
        return false;
    }

    SetFormFocus(page, annotation.get());

    if (type == FPDF_FORMFIELD_COMBOBOX) {
        FORM_SetIndexSelected(form_handle_.get(), page, selected_indices[0], true);
    } else {
        // Deselect all indices.
        for (int i = 0; i < option_count; i++) {
            FORM_SetIndexSelected(form_handle_.get(), page, i, false);
        }

        // Select the requested indices.
        for (int i = 0; i < selected_indices.size(); i++) {
            FORM_SetIndexSelected(form_handle_.get(), page, selected_indices[i], true);
        }
    }
    KillFormFocus();
    return true;
}

bool FormFiller::IsWidget(FPDF_ANNOTATION annotation) {
    return FPDFAnnot_GetSubtype(annotation) == FPDF_ANNOT_WIDGET;
}

ScopedFPDFAnnotation FormFiller::GetFormAnnotation(FPDF_PAGE page, Point_d point) {
    const FS_POINTF point_f = {static_cast<float>(point.x), static_cast<float>(point.y)};
    FPDF_ANNOTATION annotation = FPDFAnnot_GetFormFieldAtPoint(form_handle_.get(), page, &point_f);

    return ScopedFPDFAnnotation(annotation);
}

ScopedFPDFAnnotation FormFiller::GetFormAnnotation(FPDF_PAGE page, int index) {
    FPDF_ANNOTATION annotation = FPDFPage_GetAnnot(page, index);

    if (!annotation || !IsWidget(annotation)) {
        return nullptr;
    }

    return ScopedFPDFAnnotation(annotation);
}

int FormFiller::GetFormFieldType(FPDF_PAGE page, Point_d point) {
    return FPDFPage_HasFormFieldAtPoint(form_handle_.get(), page, point.x, point.y);
}

int FormFiller::GetFormFieldType(FPDF_PAGE page, FPDF_ANNOTATION annotation) {
    Rectangle_i rect = GetAnnotationRect(annotation);
    double mid_height = rect.top - ((rect.top - rect.bottom) / 2);
    double mid_width = rect.right - ((rect.right - rect.left) / 2);
    return GetFormFieldType(page, DoublePoint(mid_width, mid_height));
}

Rectangle_i FormFiller::GetAnnotationRect(FPDF_ANNOTATION annotation) {
    FS_RECTF rect;
    FPDF_BOOL success = FPDFAnnot_GetRect(annotation, &rect);

    if (!success) {
        return kDefaultAnnotationRect;
    }

    return Rectangle_i{
            static_cast<int>(std::floor(rect.left)), static_cast<int>(std::ceil(rect.top)),
            static_cast<int>(std::ceil(rect.right)), static_cast<int>(std::floor(rect.bottom))};
}

int FormFiller::GetAnnotationIndex(FPDF_PAGE page, FPDF_ANNOTATION annotation) {
    return FPDFPage_GetAnnotIndex(page, annotation);
}

int FormFiller::GetOptionCount(FPDF_ANNOTATION annotation) {
    return FPDFAnnot_GetOptionCount(form_handle_.get(), annotation);
}

std::vector<Option> FormFiller::GetOptions(FPDF_PAGE page, FPDF_ANNOTATION annotation) {
    int option_count = GetOptionCount(annotation);
    std::vector<Option> options;
    options.reserve(std::max(option_count, 0));

    for (int i = 0; i < option_count; i++) {
        std::string label =
                pdfClient_utils::FPDFAnnot_GetOptionLabel(form_handle_.get(), annotation, i);
        FPDF_BOOL selected = FORM_IsIndexSelected(form_handle_.get(), page, i);
        options.push_back(Option{i, std::move(label), static_cast<bool>(selected)});
    }
    return options;
}

int FormFiller::GetMaxLen(FPDF_ANNOTATION annotation) {
    float value;
    FPDF_BOOL found = FPDFAnnot_GetNumberValue(annotation, "MaxLen", &value);
    if (!found) {
        return -1;
    }

    return static_cast<int>(value);
}

float FormFiller::GetFontSize(FPDF_ANNOTATION annotation) {
    float value;
    if (!FPDFAnnot_GetFontSize(form_handle_.get(), annotation, &value)) {
        return 0.f;
    }
    return value;
}

void FormFiller::PerformClick(FPDF_PAGE page, const Point_d point) {
    FORM_OnMouseMove(form_handle_.get(), page, kDefaultFormOperationModifiers, point.x, point.y);
    FORM_OnLButtonDown(form_handle_.get(), page, kDefaultFormOperationModifiers, point.x, point.y);
    FORM_OnLButtonUp(form_handle_.get(), page, kDefaultFormOperationModifiers, point.x, point.y);
}

std::string FormFiller::GetAccessibilityLabel(FPDF_ANNOTATION annotation) {
    std::string value = pdfClient_utils::FPDFAnnot_GetStringValue(annotation, "TU");

    if (value.empty()) {
        value = pdfClient_utils::FPDFAnnot_GetStringValue(annotation, "T");
    }

    return value;
}

std::string FormFiller::GetReadOnlyTextValue(int type, FPDF_ANNOTATION annotation) {
    if (IsCheckType(type)) {
        return FPDFAnnot_IsChecked(form_handle_.get(), annotation) ? "true" : "false";
    }
    return pdfClient_utils::FPDFAnnot_GetStringValue(annotation, "V");
}

void FormFiller::SetFieldText(FPDF_PAGE page, std::string_view text) {
    SelectAllFieldText(page);
    ReplaceSelectedText(page, text);
}

void FormFiller::SelectAllFieldText(FPDF_PAGE page) {
    FORM_OnKeyDown(form_handle_.get(), page, kPdfiumACharacterOffset, FWL_EVENTFLAG_ControlKey);
    FORM_OnChar(form_handle_.get(), page, kPdfiumACharacterOffset, FWL_EVENTFLAG_ControlKey);
    FORM_OnKeyUp(form_handle_.get(), page, kPdfiumACharacterOffset, FWL_EVENTFLAG_ControlKey);
}

void FormFiller::ReplaceSelectedText(FPDF_PAGE page, std::string_view replacement_text) {
    std::u16string utf_sixteen_replacement_text = pdfClient_utils::Utf8ToUtf16Le(replacement_text);
    FPDF_WIDESTRING widestring_replacement_text =
            reinterpret_cast<FPDF_WIDESTRING>(utf_sixteen_replacement_text.c_str());
    FORM_ReplaceSelection(form_handle_.get(), page, widestring_replacement_text);
}

bool FormFiller::SetFormFocus(FPDF_PAGE page, const Point_d point) {
    return FORM_OnFocus(form_handle_.get(), page, kDefaultFormOperationModifiers, point.x, point.y);
}

bool FormFiller::SetFormFocus(FPDF_PAGE page, FPDF_ANNOTATION annotation) {
    Rectangle_i rect = GetAnnotationRect(annotation);
    double mid_height = rect.top - ((rect.top - rect.bottom) / 2);
    double mid_width = rect.right - ((rect.right - rect.left) / 2);
    return SetFormFocus(page, DoublePoint(mid_width, mid_height));
}

bool FormFiller::KillFormFocus() {
    return FORM_ForceToKillFocus(form_handle_.get());
}

void FormFiller::Invalidate(FPDF_FORMFILLINFO* pThis, FPDF_PAGE page, double left, double top,
                            double right, double bottom) {
    FormFiller* form_filler = static_cast<FormFiller*>(pThis);
    Rectangle_i rect = IntRect(left, top, right, bottom);
    form_filler->document_->NotifyInvalidRect(page, rect);
}
}  // namespace pdfClient