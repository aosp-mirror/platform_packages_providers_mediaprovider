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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_FILLER_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_FILLER_H_

#include <span>
#include <string>
#include <unordered_set>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "form_widget_info.h"
#include "fpdf_formfill.h"
#include "fpdfview.h"
#include "rect.h"

namespace pdfClient {

class Document;

// Handles all interactions with form filling elements including rendering and
// editing.
class FormFiller : public FPDF_FORMFILLINFO {
  public:
    explicit FormFiller(Document* document, FPDF_DOCUMENT fpdf_document);

    ~FormFiller();

    // Must be called before this FormFiller is used to complete any form filling
    // actions on the |page|.
    void NotifyAfterPageLoad(FPDF_PAGE page);

    // Must be called before releasing |page| to release resources when no
    // further form filling actions will be executed on the |page|.
    void NotifyBeforePageClose(FPDF_PAGE page);

    // Renders form widget content.
    bool RenderTile(FPDF_PAGE page, FPDF_BITMAP bitmap, FS_MATRIX transform, FS_RECTF clip,
                    int render_mode) const;

    // Obtain information about the form widget at |point| on the page, if any.
    // |point| is in page coordinates.
    FormWidgetInfo GetFormWidgetInfo(FPDF_PAGE page, const Point_d point);

    // Obtain information about the form widget with index |annotation_index| on
    // the page, if any.
    FormWidgetInfo GetFormWidgetInfo(FPDF_PAGE page, const int annotation_index);

    // Obtain form widget information for |annotation|, if any.
    FormWidgetInfo GetFormWidgetInfo(FPDF_PAGE page, FPDF_ANNOTATION annotation);

    // Obtain form widget information for all form field annotations on |page|,
    // optionally restricting by |type_ids| and store in |widget_infos|. See
    // fpdf_formfill.h for type constants. If |type_ids| is empty all form
    // widgets on |page| will be added to |widget_infos|, if any.
    void GetFormWidgetInfos(FPDF_PAGE page, const std::unordered_set<int>& type_ids,
                            std::vector<FormWidgetInfo>* widget_infos);

    // Perform a click at |point| on the page. Any focus in the document
    // resulting from this operation will be killed before returning (i.e. there
    // will be no "currently selected widget" resulting from the click). No-op if
    // no widget present at |point| or widget cannot be edited. Returns true if
    // click was performed. |point| is in page coordinates.
    bool ClickOnPoint(FPDF_PAGE page, const Point_d point);

    // Set the value text of the widget at |annotation_index| on |page|. No-op if
    // no widget present or widget cannot be edited. Returns true if text was
    // set, false otherwise.
    bool SetText(FPDF_PAGE page, const int annotation_index, const std::string_view text);

    // Set the |selected_indices| for the choice widget at |annotation_index| as
    // selected and deselect all other indices. No-op if no widget present or
    // widget cannot be edited. Returns true if indices were set, false otherwise.
    bool SetChoiceSelection(FPDF_PAGE page, const int annotation_index,
                            std::span<const int> selected_indices);

  private:
    // Returns true if the |annotation| is a widget.
    bool IsWidget(FPDF_ANNOTATION annotation);
    // Returns the form annotation at |point|, if present. Else nullptr.
    ScopedFPDFAnnotation GetFormAnnotation(FPDF_PAGE page, Point_d point);
    // Returns the form annotation at |index|, if present. Else nullptr.
    ScopedFPDFAnnotation GetFormAnnotation(FPDF_PAGE page, int index);

    // Return the type of widget at |point|, if present. Else -1.
    int GetFormFieldType(FPDF_PAGE page, Point_d point);
    // Return the type of widget the |annotation| is. -1 if not a widget.
    int GetFormFieldType(FPDF_PAGE page, FPDF_ANNOTATION annotation);

    // Get the coordinate rectangle that the |annotation| occupies on the page.
    // Result is in Page Coordinates.
    Rectangle_i GetAnnotationRect(FPDF_ANNOTATION annotation);

    // Return the index of the |annotation| on the |page| or -1  if not found.
    int GetAnnotationIndex(FPDF_PAGE page, FPDF_ANNOTATION annotation);

    // Returns the number of options the choice |annotation| contains. Returns -1
    // if annotation is not a choice type.
    int GetOptionCount(FPDF_ANNOTATION annotation);

    // Get Options for the choice |annotation|. Returns empty list if the
    // annotation is not a choice type or does not have options.
    std::vector<Option> GetOptions(FPDF_PAGE page, FPDF_ANNOTATION annotation);

    // Get the "MaxLen" value for the annotation. Returns -1 if the MaxLen value
    // is not present in the annotation dictionary.
    int GetMaxLen(FPDF_ANNOTATION annotation);

    // Get the text font size of a widget containing text. Returns 0 if the font
    // size was not found which indicates that widget text is autosized.
    float GetFontSize(FPDF_ANNOTATION annotation);

    // Gets the TU value for the form widget, if any. If TU is not provided will
    // return the T value, if any. If not provided returns empty string.
    std::string GetAccessibilityLabel(FPDF_ANNOTATION annotation);

    // Gets the text value for a read only form widget (i.e. widget for which
    // pdfium form filling operations are not permitted). Determines the checked
    // state and returns "true" or "false" for checkboxes and radio buttons.
    // Obtains the annotation dictionary "V" entry for all other types.
    std::string GetReadOnlyTextValue(int type, FPDF_ANNOTATION annotation);

    // Perform a click action on the document in Pdfium.
    void PerformClick(FPDF_PAGE page, const Point_d point);
    // Set the text of the field that is currently focused (in Pdfium) to |text|.
    void SetFieldText(FPDF_PAGE page, std::string_view text);
    // Set all the text of the field that is currently focused (in Pdfium) as
    // selected.
    void SelectAllFieldText(FPDF_PAGE page);
    // Replace the text that is currently selected in the focused field (in
    // Pdfium) with |replacement_text|.
    void ReplaceSelectedText(FPDF_PAGE page, std::string_view replacement_text);

    // Set Pdfium's focus to the widget at |point|, if any.
    bool SetFormFocus(FPDF_PAGE page, const Point_d point);
    // Set Pdfium's focus to the |annotation|.
    bool SetFormFocus(FPDF_PAGE page, FPDF_ANNOTATION annotation);
    // Kill all focus held in Pdfium, if any.
    bool KillFormFocus();

    // Implementation of method from FPDF_FORMFILLINFO. Pdfium will use this
    // method to inform FormFiller when a rectangle of |page|'s bitmap has been
    // invalidated. This occurs following form filling actions.
    static void Invalidate(FPDF_FORMFILLINFO* pThis, FPDF_PAGE page, double left, double top,
                           double right, double bottom);

    Document* document_;  // Not owned.
    ScopedFPDFFormHandle form_handle_;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_FILLER_H_