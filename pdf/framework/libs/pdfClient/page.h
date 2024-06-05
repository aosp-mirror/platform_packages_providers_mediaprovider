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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_H_

#include <stdint.h>

#include <span>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

#include "cpp/fpdf_scopers.h"
#include "form_filler.h"
#include "form_widget_info.h"
#include "fpdfview.h"
#include "rect.h"

namespace pdfClient {

// A start index (inclusive) and a stop index (exclusive) into the string of
// codepoints that make up a range of text.
typedef std::pair<int, int> TextRange;

// A start index (inclusive) or stop index (exclusive) into the string of
// codepoints that make up a range of text, and a point on the boundary where
// the selection starts or stops.
struct SelectionBoundary {
    int index;
    Point_i point;
    bool is_rtl;

    SelectionBoundary(int i, int x, int y, bool r) : index(i), is_rtl(r) { point = IntPoint(x, y); }
};

struct GotoLinkDest {
    int page_number = 0;
    float x = 0;
    float y = 0;
    float zoom = 0;

    void set_page_number(int page_number) { this->page_number = page_number; }

    void set_x(float x) { this->x = x; }

    void set_y(float y) { this->y = y; }

    void set_zoom(float zoom) { this->zoom = zoom; }
};

struct GotoLink {
    std::vector<Rectangle_i> rect;
    GotoLinkDest dest;
};

// Wrapper on a FPDF_PAGE that adds rendering functionality.
class Page {
  public:
    // FPDF_PAGE is opened when constructed.
    Page(FPDF_DOCUMENT doc, int page_num, FormFiller* form_filler);

    // Move constructor.
    Page(Page&& p);

    virtual ~Page();

    int Width() const;

    int Height() const;

    Rectangle_i Dimensions() const;

    // Render the page to the output bitmap, applying the appropriate transform, clip, and
    // render mode as specified.
    void Render(FPDF_BITMAP bitmap, FS_MATRIX transform, int clip_left, int clip_top,
                int clip_right, int clip_bottom, int render_mode, int hide_text_annots);

    // The page has a transform that must be applied to all characters and objects
    // on the page. This transforms from the page's internal co-ordinate system
    // to the external co-ordinate system from (0, 0) to (Width(), Height()).
    Point_i ApplyPageTransform(const Point_d& input) const;
    Rectangle_i ApplyPageTransform(const Rectangle_d& input) const;
    Rectangle_i ApplyPageTransform(const Rectangle_i& input) const;

    // Transform from the external co-ordinate system (0, 0)-(Width(), Height())
    // back into the page's internal co-ordinate system.
    Point_d UnapplyPageTransform(const Point_i& input) const;

    int NumChars();

    uint32_t GetUnicode(int char_index);

    // Returns the entire text of the given page in UTF-8.
    std::string GetTextUtf8();

    // Returns part of the text of the given page in UTF-8.
    std::string GetTextUtf8(const int start_index, const int stop_index);

    // Appends each alt-text instance on the page to |result|.
    void GetAltTextUtf8(std::vector<std::string>* result) const;

    // Searches for the given word on the given page and returns the number of
    // matches. Ignores case and accents when searching.
    // If matches vector is not NULL, it is filled with the start and end indices
    // of each match - these are character indices according to FPDFText API.
    int FindMatchesUtf8(std::string_view utf8, std::vector<TextRange>* matches);

    // Same as above, but finds the bounding boxes of the matches. Returns the
    // number of matches and fills in the rects vector. Each match can take more
    // than one rect to bound, so the match_to_rect vector is filled so that
    // rects[match_to_rect[i]] is the first rectangle that belongs with match i.
    // Matches for which we cannot find a single bounding rectangle are discarded.
    // The char_indexes vector is filled with the char index that each match
    // starts at - the beginning of its TextRange.
    int BoundsOfMatchesUtf8(std::string_view utf8, std::vector<Rectangle_i>* rects,
                            std::vector<int>* match_to_rect, std::vector<int>* char_indexes);

    // Appends 0 or more rectangles to the given vector that surround the text
    // of the given page from the start index and the stop index.
    // Returns the number of rectangles used to surround the text.
    int GetTextBounds(const int start_index, const int stop_index, std::vector<Rectangle_i>* rects);

    // If there is a word at the given point, returns true and modifies the given
    // boundaries to point to each end of the word - otherwise returns false.
    bool SelectWordAt(const Point_i& point, SelectionBoundary* start, SelectionBoundary* stop);

    // Modifies the given selection boundary object in the following ways:
    // - The resulting boundary will have an index that is within the range
    // [0...n], where n is NumChars().
    // - The resulting boundary will have a point that is at the outer corner
    // of the char just inside the selection.
    void ConstrainBoundary(SelectionBoundary* boundary);

    int GetFontSize(int index);
    // Get the URLs and bounding rectangles for all links on the page.
    int GetLinksUtf8(std::vector<Rectangle_i>* rects, std::vector<int>* link_to_rect,
                     std::vector<std::string>* urls) const;

    // Returns the list of GotoLink for all GotoLinks on the page.
    std::vector<GotoLink> GetGotoLinks() const;

    // Perform any operations required to prepare this page for form filling.
    void InitializeFormFilling();

    // Perform any clean up operations after form filling is complete.
    void TerminateFormFilling();

    // Obtain information about the form widget at |point| on the page, if any.
    // |point| is in device coordinates.
    FormWidgetInfo GetFormWidgetInfo(Point_i point);

    // Obtain information about the form widget with index |annotation_index| on
    // the page, if any.
    FormWidgetInfo GetFormWidgetInfo(int annotation_index);

    // Obtain form widget information for all form field annotations on the page,
    // optionally restricting by |type_ids| and store in |widget_infos|. See
    // fpdf_formfill.h for type constants. If |type_ids| is empty all form
    // widgets on |page| will be added to |widget_infos|, if any.
    void GetFormWidgetInfos(const std::unordered_set<int>& type_ids,
                            std::vector<FormWidgetInfo>* widget_infos);

    // Perform a click at |point| on the page. Any focus in the document
    // resulting from this operation will be killed before returning.  No-op if
    // no widget present at |point| or widget cannot be edited. Returns true if
    // click was performed. |point| is in device coordinates.
    bool ClickOnPoint(Point_i point);

    // Set the value text of the widget at |annotation_index| on page. No-op if
    // no widget present or widget cannot be edited. Returns true if text was
    // set, false otherwise.
    bool SetFormFieldText(int annotation_index, std::string_view text);

    // Set the |selected_indices| for the choice widget at |annotation_index| as
    // selected and deselect all other indices. No-op if no widget present or
    // widget cannot be edited. Returns true if indices were set, false otherwise.
    bool SetChoiceSelection(int annotation_index, std::span<const int> selected_indices);

    // Informs the page that the |rect| of the page bitmap has been invalidated.
    // This takes place following form filling operations. |Rect| must be in page
    // coordinates.
    void NotifyInvalidRect(Rectangle_i rect);

    // Return whether or not an area of the bitmap has been invalidated.
    bool HasInvalidRect();

    // Returns the area of the page that has been invalidated and resets the
    // field. Rect returned in device coordinates.
    Rectangle_i ConsumeInvalidRect();

    // Returns FPDF_PAGE. This Page retains ownership. All operations that wish
    // to access FPDF_PAGE should to call methods of this class instead of
    // requesting the FPDF_PAGE directly through this method.
    void* page();

  private:
    // Convenience methods to access the variables dependent on an initialized
    // ScopedFPDFTextPage. We lazy init text_page_ for efficiency because many
    // page operations do not require it.
    FPDF_TEXTPAGE text_page();
    int first_printable_char_index();
    int last_printable_char_index();

    // Check that text_page_ and first/last_printable_char_index_ have been
    // initialized and do so if not.
    void EnsureTextPageInitialized();

    // Android bitmaps are in ARGB order. pdfClient emits bitmaps which have red and
    // blue swapped when treated as Android bitmaps - but this function fixes it.
    // NOTE: This might rely on little-endian architecture.
    void InPlaceSwapRedBlueChannels(void* pixels, const int num_pixels) const;

    // Looks for an instance of the given UTF32 string on the given page, starting
    // not before the page_start index and ending before the page_stop index.
    // If found, returns true and updates the TextRange. Case/accent insensitive.
    bool FindMatch(const std::u32string& query, const int page_start, const int page_stop,
                   TextRange* match);

    // Checks if the page matches the given UTF32 string at the given match_start
    // index that ends before the page_stop index. If it matches, returns true
    // and updates the TextRange. Case/accent insensitive.
    bool IsMatch(const std::u32string& query, const int match_start, const int page_stop,
                 TextRange* match);

    // Returns a SelectionBoundary at a particular index - 0 means before the char
    // at index 0, 1 means after char 0 but before the char at index 1, and so on.
    SelectionBoundary GetBoundaryAtIndex(const int index);

    // Returns whether text is flowing left or right at a particular index.
    bool IsRtlAtIndex(const int index);

    // Returns a SelectionBoundary at a particular index, once we already know
    // which way the text is flowing at that index.
    SelectionBoundary GetBoundaryAtIndex(const int index, bool is_rtl);

    // Returns a SelectionBoundary as near as possible to the given point.
    SelectionBoundary GetBoundaryAtPoint(const Point_i& point);

    // Given a boundary index to the middle or either end of a word, returns
    // the boundary index of the start of that word - which is the index of the
    // first char that is part of that word.
    int GetWordStartIndex(const int index);

    // Given a boundary index to the middle or either end of a word, returns
    // the boundary index of the stop of that word - which is the index of the
    // first char that is immediately after that word, but not part of it.
    int GetWordStopIndex(const int index);

    // Returns the rectangle that bounds the given char - page transform is not
    // yet applied, must be applied later.
    Rectangle_d GetRawCharBounds(int char_index);

    // Returns the rectangle that bounds the given char, with the page transform
    // already applied.
    Rectangle_i GetCharBounds(int char_index);

    // Returns the origin of the given char, with the page transform applied.
    Point_i GetCharOrigin(int char_index);

    // Get the URLs and bounding rectangles for annotation links only - text
    // that has been annotated to link to some URL.
    int GetAnnotatedLinksUtf8(std::vector<Rectangle_i>* rects, std::vector<int>* link_to_rect,
                              std::vector<std::string>* urls) const;

    // Get the URLs and bounding rectangles for inferred links only - text that
    // we recognize as a potential link since it starts with http:// or similar.
    int GetInferredLinksUtf8(std::vector<Rectangle_i>* rects, std::vector<int>* link_to_rect,
                             std::vector<std::string>* urls) const;

    bool IsGotoLink(FPDF_LINK link) const;

    bool IsUrlLink(FPDF_LINK link) const;

    // Get the URL of the given link, in UTF-8.
    std::string GetUrlUtf8(FPDF_LINK link) const;

    // Get the bounds of the given link, in page co-ordinates.
    Rectangle_i GetRect(FPDF_LINK link) const;

    FPDF_DOCUMENT document_;  // Not owned.

    ScopedFPDFPage page_;

    FormFiller* const form_filler_;  // Not owned.

    // these variables lazily initialized, should be accessed via corresponding
    // accessor methods
    ScopedFPDFTextPage text_page_;
    int first_printable_char_index_;
    int last_printable_char_index_;

    // Rectangle representing an area of the bitmap for this page that has been
    // reported as invalidated. Will be coalesced from all rectangles that are
    // reported as invalidated since the last time this rectangle was consumed.
    // Rectangles are invalidated due to form filling operations.
    // Rectangle is in Device Coordinates.
    Rectangle_i invalid_rect_;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_PAGE_H_