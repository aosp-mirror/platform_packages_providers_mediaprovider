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

#include <android-base/file.h>
#include <gtest/gtest.h>

#include <memory>
#include <span>
#include <string>
#include <vector>

// Goes first due to conflicts.
#include "cpp/fpdf_scopers.h"
#include "document.h"
#include "fpdfview.h"
#include "page.h"
#include "rect.h"

namespace {

using ::pdfClient::Document;
using ::pdfClient::Page;
using ::pdfClient::Rectangle_i;

static const std::string kTestdata = "testdata";
static const std::string kChineseFile = "chinese.pdf";
static const std::string kFrenchFile = "french.pdf";
static const std::string kSpannerFile = "spanner.pdf";
static const std::string kAltTextFile = "alt_text.pdf";
static const std::string kBugSwitzerland = "bug_switzerland.pdf";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

ScopedFPDFDocument LoadTestDocument(const std::string filename) {
    return ScopedFPDFDocument(FPDF_LoadDocument(GetTestFile(filename).c_str(), nullptr));
}

int Area(const Rectangle_i& rect) {
    return rect.Width() * rect.Height();
}

int NumRectsForMatch(std::span<const Rectangle_i> rects, std::span<const int> match_to_rect,
                     int match) {
    if (match < 0 || match >= match_to_rect.size()) {
        return 0;
    }
    if (match + 1 == match_to_rect.size()) {
        return rects.size() - match_to_rect[match];
    }
    return match_to_rect[match + 1] - match_to_rect[match];
}

TEST(Test, SearchPageText_french) {
    Document doc(LoadTestDocument(kFrenchFile), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    const std::string expected_word = "généralement";
    const std::string wrong_case = "GÉNérALEment";
    const std::string missing_accents = "GENerALEment";
    const std::string unexpected_word = "discothèque";

    std::string page_text = page->GetTextUtf8();

    // We can find exact matches in the contents using string::find.
    EXPECT_NE(std::string::npos, page_text.find(expected_word));
    // But we can't find it by any of the variations, or the unexpected word.
    EXPECT_EQ(std::string::npos, page_text.find(wrong_case));
    EXPECT_EQ(std::string::npos, page_text.find(missing_accents));
    EXPECT_EQ(std::string::npos, page_text.find(wrong_case));

    // We can find it by any of the variations of it using FindMatchesUtf8.
    EXPECT_EQ(1, page->FindMatchesUtf8(expected_word, nullptr));
    EXPECT_EQ(1, page->FindMatchesUtf8(wrong_case, nullptr));
    EXPECT_EQ(1, page->FindMatchesUtf8(missing_accents, nullptr));
    // But still can't find a word if it isn't there at all.
    EXPECT_EQ(0, page->FindMatchesUtf8(unexpected_word, nullptr));
}

TEST(Test, SearchPageText_chinese) {
    Document doc(LoadTestDocument(kChineseFile), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    const std::string chinese_word = "你好";
    const std::string english_word = "hello";
    const std::string japanese_word = "先生";

    std::string page_text = page->GetTextUtf8();
    // Page text should contain the chinese word and the english word.
    EXPECT_NE(std::string::npos, page_text.find(chinese_word));
    EXPECT_NE(std::string::npos, page_text.find(english_word));
    // But not japanese word.
    EXPECT_EQ(std::string::npos, page_text.find(japanese_word));

    // We can find the chinese word and the latin word.
    EXPECT_EQ(4, page->FindMatchesUtf8(chinese_word, nullptr));
    EXPECT_EQ(4, page->FindMatchesUtf8(english_word, nullptr));
    // But not the japanese word, since it isn't there.
    EXPECT_EQ(0, page->FindMatchesUtf8(japanese_word, nullptr));
}

TEST(Test, SearchPageText_hyphens) {
    Document doc(LoadTestDocument(kSpannerFile), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    // Punctuation is generally not ignored.
    // There is one instance of "C. Corbett":
    EXPECT_EQ(1, page->FindMatchesUtf8("C. Corbett", nullptr));
    // Cannot find it by searching "C Corbett"
    EXPECT_EQ(0, page->FindMatchesUtf8("C Corbett", nullptr));

    // There are two instances of "wide-area":
    EXPECT_EQ(2, page->FindMatchesUtf8("wide-area", nullptr));
    // Cannot find it by searching "widearea".
    EXPECT_EQ(0, page->FindMatchesUtf8("widearea", nullptr));

    // Support is found 4 times if you find the line broken "sup-/nport":
    EXPECT_EQ(4, page->FindMatchesUtf8("support", nullptr));
    // Only the line-broken version can also be found by searching sup-port.
    EXPECT_EQ(1, page->FindMatchesUtf8("sup-port", nullptr));
    // Can't find it by adding hyphens in other parts of the word.
    EXPECT_EQ(0, page->FindMatchesUtf8("s-upport", nullptr));

    // Globally-distributed is found 4 times if you find the line broken
    // "globally-\ndistributed":
    EXPECT_EQ(4, page->FindMatchesUtf8("globally-distributed", nullptr));
    // Since we don't know if the hyphen belongs there if the word wasn't
    // line-broken, that match can be found like this too:
    EXPECT_EQ(1, page->FindMatchesUtf8("globallydistributed", nullptr));

    // Failure modes is found once if you find the line broken "failure/nmodes"
    EXPECT_EQ(1, page->FindMatchesUtf8("failure modes", nullptr));
    // Shouldn't be found by searching for "failuremodes"
    EXPECT_EQ(0, page->FindMatchesUtf8("failuremodes", nullptr));
}

TEST(Test, GetTextBounds_hyphens) {
    Document doc(LoadTestDocument(kSpannerFile), false);
    std::shared_ptr<Page> page = doc.GetPage(0);

    Rectangle_i page_rect = page->Dimensions();

    std::vector<Rectangle_i> gd_bounds;
    std::vector<int> gd_m2r;  // match_to_rect
    // Finds 4 matches for globally-distributed.
    EXPECT_EQ(4, page->BoundsOfMatchesUtf8("globally-distributed", &gd_bounds, &gd_m2r, nullptr));
    EXPECT_EQ(4, gd_m2r.size());

    // But 5 rectangles since one match is broken onto two lines.
    EXPECT_EQ(5, gd_bounds.size());
    // The second one is broken onto two lines - this should look like so.
    EXPECT_EQ(1, NumRectsForMatch(gd_bounds, gd_m2r, 0));
    EXPECT_EQ(2, NumRectsForMatch(gd_bounds, gd_m2r, 1));
    EXPECT_EQ(1, NumRectsForMatch(gd_bounds, gd_m2r, 2));
    EXPECT_EQ(1, NumRectsForMatch(gd_bounds, gd_m2r, 3));

    for (int i = 0; i < 5; i++) {
        // Any bounds should be of positive area, smaller than the page.
        EXPECT_GT(Area(gd_bounds[i]), 0);
        EXPECT_LT(Area(gd_bounds[i]), Area(page_rect));
        // And it should be entirely on the page:
        Rectangle_i copy = gd_bounds[i];
        copy = Intersect(copy, page_rect);
        EXPECT_EQ(gd_bounds[i], copy);
    }

    // First match is in a big font the heading, should have the biggest area:
    for (int i = 1; i < 5; i++) {
        EXPECT_GT(Area(gd_bounds[0]), Area(gd_bounds[i]));
    }

    std::vector<Rectangle_i> g_bounds;
    EXPECT_EQ(4, page->BoundsOfMatchesUtf8("globally-", &g_bounds, nullptr, nullptr));
    std::vector<Rectangle_i> d_bounds;
    EXPECT_EQ(7, page->BoundsOfMatchesUtf8("distributed", &d_bounds, nullptr, nullptr));

    // The second globally-distributed is split onto two lines - it should be
    // made of two rectangles, one which surrounds "globally-" and one which
    // surrounds "distributed".
    EXPECT_EQ(g_bounds[1], gd_bounds[1]);  // "globally-" rectangle.
    EXPECT_EQ(d_bounds[1], gd_bounds[2]);  // "distributed" rectangle.

    std::vector<Rectangle_i> fm_bounds;
    std::vector<int> fm_m2r;  // match_to_rect
    // Failure modes is split onto two lines, should have one match:
    EXPECT_EQ(1, page->BoundsOfMatchesUtf8("failure modes", &fm_bounds, &fm_m2r, nullptr));
    EXPECT_EQ(1, fm_m2r.size());
    // But two rectangles:
    EXPECT_EQ(2, fm_bounds.size());
    EXPECT_EQ(2, NumRectsForMatch(fm_bounds, fm_m2r, 0));

    // Should get same results with different whitespace:
    EXPECT_EQ(1, page->BoundsOfMatchesUtf8("failure\r\n  modes", &fm_bounds, &fm_m2r, nullptr));
}

TEST(Test, ExtractAltText) {
    Document doc(LoadTestDocument(kAltTextFile), false);
    std::shared_ptr<Page> page = doc.GetPage(6);

    std::vector<std::string> alt_texts;
    page->GetAltTextUtf8(&alt_texts);
    // For now testing returned vector size GT 0, once we address include path
    // we can consider gmock matchers for uncommenting following lines to
    // confirm vector content
    EXPECT_GT(alt_texts.size(), 0);
}

TEST(Test, BugSwitzerland) {
    Document doc(LoadTestDocument(kBugSwitzerland), false);
    // Opening this text page shouldn't crash - http://b/17684639
    std::shared_ptr<Page> page = doc.GetPage(0);
    EXPECT_EQ(1, page->FindMatchesUtf8("Switzerland", nullptr));
}

}  // namespace
