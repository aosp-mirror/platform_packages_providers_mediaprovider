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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_WIDGET_INFO_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_WIDGET_INFO_H_

#include <string>
#include <vector>

#include "rect.h"

namespace pdfClient {

struct Option {
    int index;
    std::string label;
    bool selected;
};

// Value class of relevant information about a single form widget.
class FormWidgetInfo {
  public:
    FormWidgetInfo();
    ~FormWidgetInfo();

    // True if action found widget.
    bool FoundWidget();
    int OptionCount() const;
    bool HasOptions() const;

    int widget_type() const;
    void set_widget_type(int widget_type);
    int widget_index() const;
    void set_widget_index(int widget_index);
    Rectangle_i widget_rect() const;
    void set_widget_rect(Rectangle_i widget_rect);
    bool read_only() const;
    void set_read_only(bool read_only);
    std::string text_value() const;
    void set_text_value(std::string_view text_value);
    std::string accessibility_label() const;
    void set_accessibility_label(std::string_view accessibility_label);
    bool editable_text() const;
    void set_editable_text(bool editable_text);
    bool multiselect() const;
    void set_multiselect(bool multiselect);
    bool multi_line_text() const;
    void set_multi_line_text(bool multi_line_text);
    int max_length() const;
    void set_max_length(int max_length);
    float font_size() const;
    void set_font_size(float font_size);
    const std::vector<Option>& options() const;
    void set_options(const std::vector<Option>& options);

  private:
    int widget_type_;
    int widget_index_;
    Rectangle_i widget_rect_;
    bool read_only_;
    std::string text_value_;
    std::string accessibility_label_;
    // Text/combo only, true if user can set text manually.
    bool editable_text_;
    // Listboxes only.
    bool multiselect_;
    // Text field only.
    bool multi_line_text_;
    // Text field only.
    int max_length_;
    // Editable Text fields only.
    float font_size_;
    // Combo/listboxes only.
    std::vector<Option> options_;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FORM_WIDGET_INFO_H_