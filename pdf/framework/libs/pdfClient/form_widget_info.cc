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

#include "form_widget_info.h"

#include <string>
#include <vector>

#include "rect.h"

using std::vector;

namespace pdfClient {

const Rectangle_i kDefaultRect = IntRect(-1, -1, -1, -1);
const float kAutoSizeFontSize = 0;

FormWidgetInfo::FormWidgetInfo()
    : widget_type_(-1),
      widget_index_(-1),
      widget_rect_(kDefaultRect),
      read_only_(false),
      editable_text_(false),
      multiselect_(false),
      multi_line_text_(false),
      max_length_(-1),
      font_size_(kAutoSizeFontSize) {}

FormWidgetInfo::~FormWidgetInfo() {}

bool FormWidgetInfo::FoundWidget() {
    return widget_type_ >= 0;
}

int FormWidgetInfo::OptionCount() const {
    return options_.size();
}

bool FormWidgetInfo::HasOptions() const {
    return !options_.empty();
}

int FormWidgetInfo::widget_type() const {
    return widget_type_;
}

void FormWidgetInfo::set_widget_type(int widget_type) {
    widget_type_ = widget_type;
}

int FormWidgetInfo::widget_index() const {
    return widget_index_;
}

void FormWidgetInfo::set_widget_index(int widget_index) {
    widget_index_ = widget_index;
}

Rectangle_i FormWidgetInfo::widget_rect() const {
    return widget_rect_;
}

void FormWidgetInfo::set_widget_rect(Rectangle_i widget_rect) {
    widget_rect_ = widget_rect;
}

bool FormWidgetInfo::read_only() const {
    return read_only_;
}

void FormWidgetInfo::set_read_only(bool read_only) {
    read_only_ = read_only;
}

std::string FormWidgetInfo::text_value() const {
    return text_value_;
}

void FormWidgetInfo::set_text_value(std::string_view text_value) {
    text_value_ = std::string(text_value);
}

std::string FormWidgetInfo::accessibility_label() const {
    return accessibility_label_;
}

void FormWidgetInfo::set_accessibility_label(std::string_view accessibility_label) {
    accessibility_label_ = std::string(accessibility_label);
}

bool FormWidgetInfo::editable_text() const {
    return editable_text_;
}

void FormWidgetInfo::set_editable_text(bool editable_text) {
    editable_text_ = editable_text;
}

bool FormWidgetInfo::multiselect() const {
    return multiselect_;
}

void FormWidgetInfo::set_multiselect(bool multiselect) {
    multiselect_ = multiselect;
}

bool FormWidgetInfo::multi_line_text() const {
    return multi_line_text_;
}

void FormWidgetInfo::set_multi_line_text(bool multi_line_text) {
    multi_line_text_ = multi_line_text;
}

int FormWidgetInfo::max_length() const {
    return max_length_;
}

void FormWidgetInfo::set_max_length(int max_length) {
    max_length_ = max_length;
}

float FormWidgetInfo::font_size() const {
    return font_size_;
}

void FormWidgetInfo::set_font_size(float font_size) {
    font_size_ = font_size;
}

const vector<Option>& FormWidgetInfo::options() const {
    return options_;
}

void FormWidgetInfo::set_options(const vector<Option>& options) {
    options_ = options;
}

}  // namespace pdfClient