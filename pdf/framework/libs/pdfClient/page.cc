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

#include <stddef.h>
#include <stdint.h>

#include <algorithm>
#include <limits>
#include <span>
#include <string>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "form_filler.h"
#include "form_widget_info.h"
#include "fpdf_annot.h"
#include "fpdf_doc.h"
#include "fpdf_text.h"
#include "fpdfview.h"
#include "normalize.h"
#include "rect.h"
#include "utf.h"
#include "utils/annot_hider.h"
#include "utils/text.h"

using std::vector;

namespace pdfClient {

static const int kBytesPerPixel = 4;

static const Rectangle_i kEmptyIntRectangle = IntRect(0, 0, 0, 0);

// The acceptable fatness / inaccuracy of a user's finger in points.
static const int kFingerTolerance = 10;

static const int RENDER_MODE_FOR_DISPLAY = 1;
static const int RENDER_MODE_FOR_PRINT = 2;

Page::Page(FPDF_DOCUMENT doc, int page_num, FormFiller* form_filler)
    : document_(doc),
      page_(FPDF_LoadPage(doc, page_num)),
      form_filler_(form_filler),
      invalid_rect_(kEmptyIntRectangle) {}

Page::Page(Page&& p) = default;

Page::~Page() {}

int Page::Width() const {
    return FPDF_GetPageWidth(page_.get());
}

int Page::Height() const {
    return FPDF_GetPageHeight(page_.get());
}

Rectangle_i Page::Dimensions() const {
    return IntRect(0, 0, Width(), Height());
}

void Page::Render(FPDF_BITMAP bitmap, FS_MATRIX transform, int clip_left, int clip_top,
                  int clip_right, int clip_bottom, int render_mode, int hide_text_annots) {
    std::unordered_set<int> types;
    if (hide_text_annots) {
        types = {FPDF_ANNOT_TEXT, FPDF_ANNOT_HIGHLIGHT};
    }
    pdfClient_utils::AnnotHider annot_hider(page_.get(), types);
    int renderFlags = FPDF_REVERSE_BYTE_ORDER;
    if (render_mode == RENDER_MODE_FOR_DISPLAY) {
        renderFlags |= FPDF_LCD_TEXT;
    } else if (render_mode == RENDER_MODE_FOR_PRINT) {
        renderFlags |= FPDF_PRINTING;
    }

    FS_RECTF clip = {(float)clip_left, (float)clip_top, (float)clip_right, (float)clip_bottom};
    FPDF_RenderPageBitmapWithMatrix(bitmap, page_.get(), &transform, &clip, renderFlags);
}

Point_i Page::ApplyPageTransform(const Point_d& input) const {
    Point_i output;
    FPDF_PageToDevice(page_.get(), 0, 0, Width(), Height(), 0, input.x, input.y, &output.x,
                      &output.y);
    return output;
}

Rectangle_i Page::ApplyPageTransform(const Rectangle_d& input) const {
    return ApplyPageTransform(OuterIntRect(input));
}

Rectangle_i Page::ApplyPageTransform(const Rectangle_i& input) const {
    Point_i output1, output2;
    FPDF_PageToDevice(page_.get(), 0, 0, Width(), Height(), 0, input.left, input.top, &output1.x,
                      &output1.y);
    FPDF_PageToDevice(page_.get(), 0, 0, Width(), Height(), 0, input.right, input.bottom,
                      &output2.x, &output2.y);

    Rectangle_i output = IntRect(output1, output2);
    // Constrain output within the page.
    output = Intersect(output, Dimensions());
    return output;
}

Point_d Page::UnapplyPageTransform(const Point_i& input) const {
    Point_d output;
    FPDF_DeviceToPage(page_.get(), 0, 0, Width(), Height(), 0, input.x, input.y, &output.x,
                      &output.y);
    return output;
}

int Page::NumChars() {
    return FPDFText_CountChars(text_page());
}

uint32_t Page::GetUnicode(int char_index) {
    return FPDFText_GetUnicode(text_page(), char_index);
}

std::string Page::GetTextUtf8() {
    return GetTextUtf8(first_printable_char_index(), last_printable_char_index() + 1);
}

std::string Page::GetTextUtf8(const int start_index, const int stop_index) {
    std::string result;
    for (int i = start_index; i < stop_index; i++) {
        AppendpdfClientCodepointAsUtf8(GetUnicode(i), &result);
    }
    return result;
}

void Page::GetAltTextUtf8(vector<std::string>* result) const {
    ::pdfClient_utils::GetAltText(page_.get(), result);
}

int Page::FindMatchesUtf8(std::string_view utf8, vector<TextRange>* matches) {
    std::u32string query(Utf8ToUtf32(utf8));
    // Normalize characters of string for searching - ignore case and accents.
    NormalizeStringForSearch(&query);
    TextRange match;
    int page_start = first_printable_char_index();
    int page_stop = last_printable_char_index() + 1;
    int num_matches = 0;
    while (FindMatch(query, page_start, page_stop, &match)) {
        if (matches != nullptr) {
            matches->push_back(match);
        }
        num_matches++;
        page_start = match.second;
    }
    return num_matches;
}

int Page::BoundsOfMatchesUtf8(std::string_view utf8, vector<Rectangle_i>* rects,
                              vector<int>* match_to_rect, vector<int>* char_indexes) {
    vector<TextRange> matches;
    int num_matches = FindMatchesUtf8(utf8, &matches);
    int num_rects = 0;
    int num_matches_with_rects = 0;
    for (int i = 0; i < num_matches; i++) {
        int start = matches[i].first, stop = matches[i].second;
        int num_rects_for_match = GetTextBounds(start, stop, rects);
        if (num_rects_for_match == 0) {
            continue;
        }
        if (match_to_rect != nullptr) {
            match_to_rect->push_back(num_rects);
        }
        if (char_indexes != nullptr) {
            char_indexes->push_back(start);
        }
        num_rects += num_rects_for_match;
        num_matches_with_rects++;
    }
    return num_matches_with_rects;
}

int Page::GetTextBounds(const int start_index, const int stop_index, vector<Rectangle_i>* rects) {
    int num_rects = 0;
    Rectangle_d rect = DoubleRect(0, 0, 0, 0);
    for (int index = start_index; index < stop_index; index++) {
        double x1, x2, y1, y2;
        // This call doesn't apply the page transform - have to apply later.
        FPDFText_GetCharBox(text_page(), index, &x1, &x2, &y1, &y2);
        if (x1 != x2 && y1 != y2) {
            if (IsEmpty(rect)) {
                rect = DoubleRect(x1, y1, x2, y2);
            } else {
                rect = Union(rect, DoubleRect(x1, y1, x2, y2));
            }
        }
        // Starting a new line - push current rect, start a new rect.
        if (IsLineBreak(GetUnicode(index))) {
            if (!IsEmpty(rect)) {
                num_rects++;
                rects->push_back(ApplyPageTransform(rect));
            }
            rect = DoubleRect(0, 0, 0, 0);
        }
    }
    // Push the last current rect.
    if (!IsEmpty(rect)) {
        num_rects++;
        rects->push_back(ApplyPageTransform(rect));
    }
    return num_rects;
}

bool Page::SelectWordAt(const Point_i& point, SelectionBoundary* start, SelectionBoundary* stop) {
    Point_d char_point = UnapplyPageTransform(point);
    int char_index = FPDFText_GetCharIndexAtPos(text_page(), char_point.x, char_point.y,
                                                kFingerTolerance, kFingerTolerance);
    if (char_index < 0 || IsWordBreak(GetUnicode(char_index))) {
        return false;  // No word at the given point to select.
    }
    start->index = GetWordStartIndex(char_index);
    stop->index = GetWordStopIndex(char_index);
    ConstrainBoundary(start);
    ConstrainBoundary(stop);
    return true;
}

void Page::ConstrainBoundary(SelectionBoundary* boundary) {
    if (boundary->index < 0) {
        // Index is not specified - find the nearest index to the given point.
        *boundary = GetBoundaryAtPoint(boundary->point);
    } else {
        // Index is specified - find the point at that index.
        int index = std::max(boundary->index, first_printable_char_index());
        index = std::min(index, last_printable_char_index() + 1);
        *boundary = GetBoundaryAtIndex(index);
    }
}

int Page::GetFontSize(int index) {
    return FPDFText_GetFontSize(text_page(), index);
}

int Page::GetLinksUtf8(vector<Rectangle_i>* rects, vector<int>* link_to_rect,
                       vector<std::string>* urls) const {
    return GetAnnotatedLinksUtf8(rects, link_to_rect, urls) +
           GetInferredLinksUtf8(rects, link_to_rect, urls);
}

vector<GotoLink> Page::GetGotoLinks() const {
    vector<GotoLink> links;

    FPDF_LINK link = nullptr;
    int pos = 0;
    while (FPDFLink_Enumerate(page_.get(), &pos, &link)) {
        if (!IsGotoLink(link)) {
            continue;
        }
        // Get the bounds of the actual link
        vector<Rectangle_i> goto_link_rects;
        Rectangle_i rect = GetRect(link);
        goto_link_rects.push_back(rect);

        GotoLinkDest* goto_link_dest = new GotoLinkDest();

        // Get and parse the destination
        FPDF_DEST fpdf_dest = FPDFLink_GetDest(document_, link);
        goto_link_dest->set_page_number(FPDFDest_GetDestPageIndex(document_, fpdf_dest));

        FPDF_BOOL has_x_coord;
        FPDF_BOOL has_y_coord;
        FPDF_BOOL has_zoom;
        FS_FLOAT x;
        FS_FLOAT y;
        FS_FLOAT zoom;
        FPDF_BOOL success = FPDFDest_GetLocationInPage(fpdf_dest, &has_x_coord, &has_y_coord,
                                                       &has_zoom, &x, &y, &zoom);

        if (!success) {
            continue;
        }
        if (has_x_coord) {
            auto point = DoublePoint(x, 0);
            auto tPoint = ApplyPageTransform(point);
            goto_link_dest->set_x(tPoint.x);
        }
        if (has_y_coord) {
            auto point = DoublePoint(0, y);
            auto tPoint = ApplyPageTransform(point);
            goto_link_dest->set_y(tPoint.y);
        }
        if (has_zoom) {
            goto_link_dest->set_zoom(zoom);
        }

        GotoLink goto_link = GotoLink{goto_link_rects, *goto_link_dest};

        links.push_back(goto_link);
    }
    return links;
}

void Page::InitializeFormFilling() {
    form_filler_->NotifyAfterPageLoad(page_.get());
}

void Page::TerminateFormFilling() {
    form_filler_->NotifyBeforePageClose(page_.get());
}

FormWidgetInfo Page::GetFormWidgetInfo(Point_i point) {
    Point_d page_point = UnapplyPageTransform(point);
    FormWidgetInfo result = form_filler_->GetFormWidgetInfo(page_.get(), page_point);
    if (result.FoundWidget()) {
        // widget_rect is in page coords, transform to device coords before
        // returning to user.
        Rectangle_i transformed_widget_rect = ApplyPageTransform(result.widget_rect());
        result.set_widget_rect(transformed_widget_rect);
    }

    // Consume any rectangle that was invalidated by this action. Some
    // info-gathering actions may cause temporary invalidation without
    // actually doing anything that we need to redraw for.
    ConsumeInvalidRect();
    return result;
}

FormWidgetInfo Page::GetFormWidgetInfo(int annotation_index) {
    FormWidgetInfo result = form_filler_->GetFormWidgetInfo(page_.get(), annotation_index);
    if (result.FoundWidget()) {
        // widget_rect is in page coords; transform to device coords before
        // returning to user.
        Rectangle_i transformed_widget_rect = ApplyPageTransform(result.widget_rect());
        result.set_widget_rect(transformed_widget_rect);
    }

    // Consume any rectangle that was invalidated by this action. Some
    // info-gathering actions may cause temporary invalidation without
    // actually doing anything that we need to redraw for.
    ConsumeInvalidRect();
    return result;
}

void Page::GetFormWidgetInfos(const std::unordered_set<int>& type_ids,
                              std::vector<FormWidgetInfo>* widget_infos) {
    form_filler_->GetFormWidgetInfos(page_.get(), type_ids, widget_infos);
    for (FormWidgetInfo& widget_info : *widget_infos) {
        // widget_rect is in page coords; transform to device coords before
        // returning to user.
        Rectangle_i transformed_widget_rect = ApplyPageTransform(widget_info.widget_rect());
        widget_info.set_widget_rect(transformed_widget_rect);
    }

    // Consume any rectangles that were invalidated by this action. Some
    // info-gathering actions may cause temporary invalidation without
    // actually doing anything that we need to redraw for.
    ConsumeInvalidRect();
}

bool Page::ClickOnPoint(Point_i point) {
    Point_d page_point = UnapplyPageTransform(point);
    return form_filler_->ClickOnPoint(page_.get(), page_point);
}
bool Page::SetFormFieldText(int annotation_index, std::string_view text) {
    return form_filler_->SetText(page_.get(), annotation_index, text);
}

bool Page::SetChoiceSelection(int annotation_index, std::span<const int> selected_indices) {
    return form_filler_->SetChoiceSelection(page_.get(), annotation_index, selected_indices);
}
void Page::NotifyInvalidRect(Rectangle_i rect) {
    if (rect.left < 0 || rect.top < 0 || rect.right < 0 || rect.bottom < 0 || IsEmpty(rect)) {
        return;
    }

    Rectangle_i device_rect = ApplyPageTransform(rect);
    // If invalid_rect_ is currently empty, avoid unioning so we don't extend
    // |rect|'s top left corner to (0,0) for no reason.
    if (IsEmpty(invalid_rect_)) {
        invalid_rect_ = device_rect;
        return;
    }

    invalid_rect_ = Union(invalid_rect_, device_rect);
}

bool Page::HasInvalidRect() {
    return !IsEmpty(invalid_rect_);
}

Rectangle_i Page::ConsumeInvalidRect() {
    Rectangle_i copy = invalid_rect_;
    invalid_rect_ = kEmptyIntRectangle;
    return copy;
}

void* Page::page() {
    return page_.get();
}

FPDF_TEXTPAGE Page::text_page() {
    EnsureTextPageInitialized();
    return text_page_.get();
}

int Page::first_printable_char_index() {
    EnsureTextPageInitialized();
    return first_printable_char_index_;
}

int Page::last_printable_char_index() {
    EnsureTextPageInitialized();
    return last_printable_char_index_;
}

void Page::EnsureTextPageInitialized() {
    if (text_page_) {
        return;
    }
    text_page_.reset(FPDFText_LoadPage(page_.get()));

    int num_chars = NumChars();

    int i;
    for (i = 0; i < num_chars && IsWordBreak(GetUnicode(i)); i++) {
    }
    first_printable_char_index_ = i;

    for (i = num_chars - 1; i >= first_printable_char_index_ && IsWordBreak(GetUnicode(i)); i--) {
    }
    last_printable_char_index_ = i;
}

void Page::InPlaceSwapRedBlueChannels(void* pixels, const int num_pixels) const {
    uint8_t* channels = static_cast<uint8_t*>(pixels);
    uint8_t* channel1 = channels;
    uint8_t* channel3 = channels + 2;

    for (int i = 0; i < num_pixels; ++i, channel1 += kBytesPerPixel, channel3 += kBytesPerPixel) {
        std::swap(*channel1, *channel3);
    }
}

bool Page::FindMatch(const std::u32string& query, const int page_start, const int page_stop,
                     TextRange* match) {
    if (query.empty()) {
        return false;
    }

    int max_match_start = page_stop - query.length();
    for (int m = page_start; m <= max_match_start; m++) {
        if (IsMatch(query, m, page_stop, match)) {
            return true;
        }
    }
    return false;
}

bool Page::IsMatch(const std::u32string& query, const int match_start, const int page_stop,
                   TextRange* match) {
    int page_index = match_start;
    size_t query_index = 0;
    uint32_t page_char = 0, prev_char = 0;
    while (query_index < query.length()) {
        prev_char = page_char;
        page_char = GetUnicode(page_index);

        if (NormalizeForSearch(page_char) == query[query_index]) {
            // This codepoint matches (ignoring case and accents). Move to next.
            query_index++;
            page_index++;
        } else if (IsSkippableForSearch(page_char, prev_char) && query_index > 0) {
            // Don't increment query index - skip over skippable character.
            page_index++;
            if ((page_stop - page_index) < (query.length() - query_index)) {
                return false;  // Not enough room for query string before page_stop.
            }
        } else {
            return false;
        }
    }
    // Update match to contain page indices of match start and match stop.
    match->first = match_start;
    match->second = page_index;
    return true;
}

SelectionBoundary Page::GetBoundaryAtIndex(const int index) {
    return GetBoundaryAtIndex(index, IsRtlAtIndex(index));
}

bool Page::IsRtlAtIndex(const int index) {
    int start_index = GetWordStartIndex(index);
    int stop_index = GetWordStopIndex(index);
    int word_length = stop_index - start_index;
    if (word_length <= 1) {
        // Can't tell directionality from a single character, guess LTR.
        return false;
    }
    Rectangle_i start_bounds = GetCharBounds(start_index);
    Rectangle_i stop_bounds = GetCharBounds(stop_index - 1);
    return start_bounds.Center().x > stop_bounds.Center().x;
}

SelectionBoundary Page::GetBoundaryAtIndex(const int index, bool is_rtl) {
    // Normally we align the boundary on the start edge of next character:
    int char_index = index;
    bool use_end_edge = false;

    // Printable characters have well defined bounding boxes, word-breaks (spaces
    // and newlines) may not - so we use the end edge of the previous printable
    // character instead if the next character is not printable.
    if (index == NumChars() || IsWordBreak(GetUnicode(index))) {
        char_index = index - 1;
        use_end_edge = true;
    }
    bool use_right_edge = use_end_edge ^ is_rtl;

    SelectionBoundary boundary(index, 0, 0, is_rtl);
    Rectangle_i char_bounds = GetCharBounds(char_index);
    boundary.point.x = use_right_edge ? char_bounds.right : char_bounds.left;
    // Use the baseline (not the bottom) of the char as the y-value.
    boundary.point.y = GetCharOrigin(char_index).y;
    return boundary;
}

SelectionBoundary Page::GetBoundaryAtPoint(const Point_i& point) {
    SelectionBoundary best_boundary(0, point.x, point.y, false);
    int best_distance_sq = std::numeric_limits<int>::max();

    bool prev_char_is_word_char = false;
    bool is_rtl = false;
    for (int index = first_printable_char_index(); index <= last_printable_char_index() + 1;
         index++) {
        bool cur_char_is_word_char =
                (index <= last_printable_char_index()) && !IsWordBreak(GetUnicode(index));
        // Starting a new word:
        if (cur_char_is_word_char && !prev_char_is_word_char) {
            // Finding out RTL involves looking at each end of the word,
            // so we only do it at the start of each word:
            is_rtl = IsRtlAtIndex(index);
        }
        if (cur_char_is_word_char || prev_char_is_word_char) {
            SelectionBoundary boundary = GetBoundaryAtIndex(index, is_rtl);
            int dx = boundary.point.x - point.x;
            int dy = boundary.point.y - point.y;
            int distance_sq = dx * dx + dy * dy;
            if (distance_sq < best_distance_sq) {
                best_boundary = boundary;
                best_distance_sq = distance_sq;
            }
        }
        prev_char_is_word_char = cur_char_is_word_char;
    }
    return best_boundary;
}

int Page::GetWordStartIndex(const int index) {
    int start_index = index;
    while (start_index > 0 && !IsWordBreak(GetUnicode(start_index - 1))) {
        --start_index;  // Move start_index to the start of the word.
    }
    return start_index;
}

int Page::GetWordStopIndex(const int index) {
    int stop_index = index;
    int num_chars = NumChars();
    while (stop_index < num_chars && !IsWordBreak(GetUnicode(stop_index))) {
        ++stop_index;  // Move stop_index to the end of the word.
    }
    return stop_index;
}

Rectangle_d Page::GetRawCharBounds(const int char_index) {
    double x1, x2, y1, y2;
    FPDFText_GetCharBox(text_page(), char_index, &x1, &x2, &y1, &y2);
    return DoubleRect(x1, y1, x2, y2);
}

Rectangle_i Page::GetCharBounds(const int char_index) {
    return ApplyPageTransform(GetRawCharBounds(char_index));
}

Point_i Page::GetCharOrigin(const int char_index) {
    double x = 0.0, y = 0.0;
    FPDFText_GetCharOrigin(text_page(), char_index, &x, &y);
    return ApplyPageTransform(DoublePoint(x, y));
}

int Page::GetAnnotatedLinksUtf8(vector<Rectangle_i>* rects, vector<int>* link_to_rect,
                                vector<std::string>* urls) const {
    FPDF_LINK link = nullptr;
    int pos = 0;
    int num_links_with_rect = 0;
    while (FPDFLink_Enumerate(page_.get(), &pos, &link)) {
        if (!IsUrlLink(link)) {
            continue;
        }

        std::string url = GetUrlUtf8(link);
        Rectangle_i rect = GetRect(link);
        if (IsEmpty(rect)) {
            continue;
        }

        link_to_rect->push_back(rects->size());
        rects->push_back(rect);
        urls->push_back(url);
        num_links_with_rect++;
    }
    return num_links_with_rect;
}

int Page::GetInferredLinksUtf8(vector<Rectangle_i>* rects, vector<int>* link_to_rect,
                               vector<std::string>* urls) const {
    // TODO(b/312730882): Infer links by looking for http:// and similar and for
    // email addresses to use as mailto: links. There are some pdfClient methods for
    // doing this, but these have some bugs which need patching or working around.
    return 0;
}

std::string Page::GetUrlUtf8(FPDF_LINK link) const {
    FPDF_ACTION action = FPDFLink_GetAction(link);
    // Allocate a string big enough to hold the URL.
    std::string url(FPDFAction_GetURIPath(document_, action, nullptr, 0), '\0');
    // Then write the URL to it.
    FPDFAction_GetURIPath(document_, action, &url[0], url.length());
    EraseTrailingNulls(&url);
    return url;
}

Rectangle_i Page::GetRect(FPDF_LINK link) const {
    FS_RECTF r;
    if (!FPDFLink_GetAnnotRect(link, &r)) {
        return Rectangle_i();
    }

    Rectangle_d rect_d = DoubleRect(r.left, r.top, r.right, r.bottom);
    return ApplyPageTransform(rect_d);
}

bool Page::IsGotoLink(FPDF_LINK link) const {
    FPDF_ACTION action = FPDFLink_GetAction(link);
    return action != nullptr && FPDFAction_GetType(action) == PDFACTION_GOTO;
}

bool Page::IsUrlLink(FPDF_LINK link) const {
    FPDF_ACTION action = FPDFLink_GetAction(link);
    return action != nullptr && FPDFAction_GetType(action) == PDFACTION_URI;
}

}  // namespace pdfClient