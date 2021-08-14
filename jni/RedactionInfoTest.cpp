/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * See the License for the specic language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "RedactionInfoTest"

#include <gtest/gtest.h>

#include <memory>
#include <ostream>
#include <vector>

#include "libfuse_jni/RedactionInfo.h"

using namespace mediaprovider::fuse;

using std::unique_ptr;
using std::vector;

std::ostream& operator<<(std::ostream& os, const ReadRange& rr) {
    os << "{ " << rr.start << ", " << rr.size << ", " << rr.is_redaction << " }";
    return os;
}

TEST(RedactionInfoTest, testNoRedactionRanges) {
    RedactionInfo info(0, nullptr);
    EXPECT_EQ(0, info.size());
    EXPECT_EQ(false, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, std::numeric_limits<size_t>::max(), &out);
    EXPECT_EQ(0, out.size());
}

// Test the case where there is 1 redaction range.
TEST(RedactionInfoTest, testSingleRedactionRange) {
    off64_t ranges[2] = {
            1,
            10,
    };

    RedactionInfo info(1, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Overlapping ranges
    std::vector<ReadRange> out;
    info.getReadRanges(0, 1000, &out);  // read offsets [0, 1000)
    EXPECT_EQ(3, out.size());
    EXPECT_EQ(ReadRange(0, 1, false), out[0]);
    EXPECT_EQ(ReadRange(1, 9, true), out[1]);
    EXPECT_EQ(ReadRange(10, 990, false), out[2]);

    out.clear();
    info.getReadRanges(0, 5, &out);  // read offsets [0, 5)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(0, 1, false), out[0]);  // offsets: [0, 1) len = 1
    EXPECT_EQ(ReadRange(1, 4, true), out[1]);   // offsets: [1, 5) len = 4

    out.clear();
    info.getReadRanges(1, 10, &out);  // read offsets [1, 11)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(1, 9, true), out[0]);    // offsets: [1, 10) len = 9
    EXPECT_EQ(ReadRange(10, 1, false), out[1]);  // offsets: [10, 11) len = 1

    // Read ranges that start or end with the boundary of the redacted area.
    out.clear();
    info.getReadRanges(5, 5, &out);  // read offsets [5, 10)
    EXPECT_EQ(1, out.size());
    EXPECT_EQ(ReadRange(5, 5, true), out[0]);  // offsets: [5, 10) len = 5

    out.clear();
    info.getReadRanges(1, 5, &out);  // read offsets [1, 6)
    EXPECT_EQ(1, out.size());
    EXPECT_EQ(ReadRange(1, 5, true), out[0]);  // offsets: [1, 6) len = 5

    // Read ranges adjoining the redacted area.
    out.clear();
    info.getReadRanges(10, 10, &out);  // read offsets [10, 20)
    EXPECT_EQ(0, out.size());

    out.clear();
    info.getReadRanges(0, 1, &out);  // read offsets [0, 1)
    EXPECT_EQ(0, out.size());

    // Read Range outside the redacted area.
    out.clear();
    info.getReadRanges(200, 10, &out);  // read offsets [200, 210)
    EXPECT_EQ(0, out.size());
}

// Multiple redaction ranges within a given area.
TEST(RedactionInfoTest, testSortedAndNonOverlappingRedactionRanges) {
    // [10, 20), [30, 40), [40, 50)
    off64_t ranges[4] = {10, 20, 30, 40};

    RedactionInfo info = RedactionInfo(2, ranges);
    EXPECT_EQ(2, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 40, &out);  // read offsets [0, 40)
    EXPECT_EQ(4, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[2]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 10, true), out[3]);   // offsets [30, 40) len = 10

    // Read request straddling two ranges.
    out.clear();
    info.getReadRanges(5, 30, &out);  // read offsets [5, 35)
    EXPECT_EQ(4, out.size());
    EXPECT_EQ(ReadRange(5, 5, false), out[0]);    // offsets: [5, 10) len = 5
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[2]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 5, true), out[3]);    // offsets [30, 35) len = 5

    // Read request overlapping first range only.
    out.clear();
    info.getReadRanges(5, 10, &out);  // read offsets [5, 15)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(5, 5, false), out[0]);  // offsets: [5, 10) len = 5
    EXPECT_EQ(ReadRange(10, 5, true), out[1]);  // offsets: [10, 15) len = 5

    // Read request overlapping last range only.
    out.clear();
    info.getReadRanges(35, 10, &out);  // read offsets [35, 45)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(35, 5, true), out[0]);   // offsets: [35, 40) len = 5
    EXPECT_EQ(ReadRange(40, 5, false), out[1]);  // offsets: [40, 45) len = 5

    // Read request overlapping no ranges.
    out.clear();
    info.getReadRanges(0, 10, &out);  // read offsets [0, 10)
    EXPECT_EQ(0, out.size());
    out.clear();
    info.getReadRanges(21, 5, &out);  // read offsets [21, 26)
    EXPECT_EQ(0, out.size());
    out.clear();
    info.getReadRanges(40, 10, &out);  // read offsets [40, 50)
    EXPECT_EQ(0, out.size());
}

// Multiple redaction ranges overlapping with read range.
TEST(RedactionInfoTest, testReadRangeOverlappingWithRedactionRanges) {
    // [10, 20), [30, 40)
    off64_t ranges[4] = {10, 20, 30, 40};

    RedactionInfo info = RedactionInfo(2, ranges);
    EXPECT_EQ(2, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    // Read request overlaps with end of the ranges.
    info.getReadRanges(20, 20, &out);  // read offsets [20, 40)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(20, 10, false), out[0]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 10, true), out[1]);   // offsets: [30, 40) len = 10

    // Read request overlapping with start of the ranges
    out.clear();
    info.getReadRanges(10, 20, &out);  // read offsets [10, 30)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(10, 10, true), out[0]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[1]);  // offsets: [20, 30) len = 10

    // Read request overlaps with start of one and end of other range.
    out.clear();
    info.getReadRanges(10, 30, &out);  // read offsets [10, 40)
    EXPECT_EQ(3, out.size());
    EXPECT_EQ(ReadRange(10, 10, true), out[0]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[1]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 10, true), out[2]);   // offsets: [30, 40) len = 10

    // Read request overlaps with end of one and start of other range.
    out.clear();
    info.getReadRanges(20, 10, &out);  // read offsets [20, 30)
    EXPECT_EQ(0, out.size());
}

TEST(RedactionInfoTest, testRedactionRangesSorted) {
    off64_t ranges[6] = {30, 40, 50, 60, 10, 20};

    RedactionInfo info = RedactionInfo(3, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 60, &out);  // read offsets [0, 60)
    EXPECT_EQ(6, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[2]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 10, true), out[3]);   // offsets [30, 40) len = 10
    EXPECT_EQ(ReadRange(40, 10, false), out[4]);  // offsets [40, 50) len = 10
    EXPECT_EQ(ReadRange(50, 10, true), out[5]);   // offsets [50, 60) len = 10

    // Read request overlapping first range only.
    out.clear();
    info.getReadRanges(5, 10, &out);  // read offsets [5, 15)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(5, 5, false), out[0]);  // offsets: [5, 10) len = 5
    EXPECT_EQ(ReadRange(10, 5, true), out[1]);  // offsets: [10, 15) len = 5

    // Read request overlapping last range only.
    out.clear();
    info.getReadRanges(55, 10, &out);  // read offsets [55, 65)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(55, 5, true), out[0]);   // offsets: [55, 60) len = 5
    EXPECT_EQ(ReadRange(60, 5, false), out[1]);  // offsets: [60, 65) len = 5

    // Read request overlapping no ranges.
    out.clear();
    info.getReadRanges(0, 10, &out);  // read offsets [0, 10)
    EXPECT_EQ(0, out.size());
    out.clear();
    info.getReadRanges(60, 10, &out);  // read offsets [60, 70)
    EXPECT_EQ(0, out.size());
}

// Test that the ranges are both sorted and merged
TEST(RedactionInfoTest, testSortAndMergeRedactionRanges) {
    // Ranges are: [10, 20), [25, 40), [50, 60)
    off64_t ranges[8] = {30, 40, 10, 20, 25, 30, 50, 60};

    RedactionInfo info = RedactionInfo(4, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 60, &out);  // read offsets [0, 60)
    EXPECT_EQ(6, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 5, false), out[2]);   // offsets: [20, 25) len = 5
    EXPECT_EQ(ReadRange(25, 15, true), out[3]);   // offsets [25, 40) len = 15
    EXPECT_EQ(ReadRange(40, 10, false), out[4]);  // offsets [40, 50) len = 10
    EXPECT_EQ(ReadRange(50, 10, true), out[5]);   // offsets [50, 60) len = 10
}

// Test that the ranges are both sorted and merged when there's an overlap.
//
// TODO: Can this ever happen ? Will we ever be in a state where we need to
// redact exif attributes that have overlapping ranges ?
TEST(RedactionInfoTest, testSortAndMergeRedactionRanges_overlap) {
    // Ranges are: [10, 20), [25, 40), [50, 60)
    off64_t ranges[8] = {30, 40, 10, 20, 25, 34, 50, 60};

    RedactionInfo info = RedactionInfo(4, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 60, &out);  // read offsets [0, 60)
    EXPECT_EQ(6, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 5, false), out[2]);   // offsets: [20, 25) len = 5
    EXPECT_EQ(ReadRange(25, 15, true), out[3]);   // offsets [25, 40) len = 15
    EXPECT_EQ(ReadRange(40, 10, false), out[4]);  // offsets [40, 50) len = 10
    EXPECT_EQ(ReadRange(50, 10, true), out[5]);   // offsets [50, 60) len = 10
}

// WARNING: The tests below assume that merging of ranges happen during
// object construction (which is asserted by the check on |info.size()|.
// Therefore, we don't write redundant tests for boundary conditions that
// we've covered above. If this ever changes, these tests need to be expanded.
TEST(RedactionInfoTest, testMergeAllRangesIntoSingleRange) {
    // Ranges are: [8, 24)
    off64_t ranges[8] = {10, 20, 8, 14, 14, 24, 12, 16};

    RedactionInfo info = RedactionInfo(4, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 30, &out);  // read offsets [0, 30)
    EXPECT_EQ(3, out.size());
    EXPECT_EQ(ReadRange(0, 8, false), out[0]);   // offsets: [0, 8) len = 8
    EXPECT_EQ(ReadRange(8, 16, true), out[1]);   // offsets: [8, 24) len = 16
    EXPECT_EQ(ReadRange(24, 6, false), out[2]);  // offsets: [24, 30) len = 6

    // Ranges are: [85, 100)
    off64_t ranges2[10] = {90, 95, 95, 100, 85, 91, 92, 94, 99, 100};
    info = RedactionInfo(5, ranges2);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    out.clear();
    info.getReadRanges(80, 30, &out);  // read offsets [80, 110)
    EXPECT_EQ(3, out.size());
    EXPECT_EQ(ReadRange(80, 5, false), out[0]);    // offsets: [80, 85) len = 5
    EXPECT_EQ(ReadRange(85, 15, true), out[1]);    // offsets: [85, 100) len = 15
    EXPECT_EQ(ReadRange(100, 10, false), out[2]);  // offsets: [100, 110) len = 10
}

TEST(RedactionInfoTest, testMergeMultipleRanges) {
    // Ranges are: [10, 30), [60, 80)
    off64_t ranges[8] = {20, 30, 10, 20, 70, 80, 60, 70};

    RedactionInfo info = RedactionInfo(4, ranges);
    EXPECT_EQ(2, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    std::vector<ReadRange> out;
    info.getReadRanges(0, 100, &out);  // read offsets [0, 100)
    EXPECT_EQ(5, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 20, true), out[1]);   // offsets: [10, 30) len = 20
    EXPECT_EQ(ReadRange(30, 30, false), out[2]);  // offsets: [30, 60) len = 30
    EXPECT_EQ(ReadRange(60, 20, true), out[3]);   // offsets [60, 80) len = 20
    EXPECT_EQ(ReadRange(80, 20, false), out[4]);  // offsets [80, 100) len = 20
}

// Redaction ranges of size zero.
TEST(RedactionInfoTest, testRedactionRangesZeroSize) {
    // [10, 20), [30, 40)
    off64_t ranges[6] = {10, 20, 30, 40, 25, 25};

    RedactionInfo info = RedactionInfo(3, ranges);
    EXPECT_EQ(2, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Normal read request, should skip range with zero size
    std::vector<ReadRange> out;
    info.getReadRanges(0, 40, &out);  // read offsets [0, 40)
    EXPECT_EQ(4, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);   // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);   // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 10, false), out[2]);  // offsets: [20, 30) len = 10
    EXPECT_EQ(ReadRange(30, 10, true), out[3]);   // offsets [30, 40) len = 10

    // Read request starting at offset overlapping with zero size range.
    out.clear();
    info.getReadRanges(25, 10, &out);  // read offsets [25, 35)
    EXPECT_EQ(2, out.size());
    EXPECT_EQ(ReadRange(25, 5, false), out[0]);  // offsets: [25, 30) len = 5
    EXPECT_EQ(ReadRange(30, 5, true), out[1]);   // offsets [30, 35) len = 5

    // 1 byte read request starting at offset overlapping with zero size range.
    out.clear();
    info.getReadRanges(25, 1, &out);  // read offsets [25, 26)
    EXPECT_EQ(0, out.size());

    // Read request ending at offset overlapping with zero size range.
    out.clear();
    info.getReadRanges(0, 25, &out);  // read offsets [0, 25)
    EXPECT_EQ(3, out.size());
    EXPECT_EQ(ReadRange(0, 10, false), out[0]);  // offsets: [0, 10) len = 10
    EXPECT_EQ(ReadRange(10, 10, true), out[1]);  // offsets: [10, 20) len = 10
    EXPECT_EQ(ReadRange(20, 5, false), out[2]);  // offsets: [20, 25) len = 10

    // Read request that includes only zero size range
    out.clear();
    info.getReadRanges(20, 10, &out);  // read offsets [20, 27)
    EXPECT_EQ(0, out.size());
}

// Single redaction range with zero size
TEST(RedactionInfoTest, testSingleRedactionRangesZeroSize) {
    off64_t ranges[2] = {10, 10};

    RedactionInfo info = RedactionInfo(1, ranges);
    EXPECT_EQ(0, info.size());
    EXPECT_EQ(false, info.isRedactionNeeded());

    // Normal read request, should skip range with zero size
    std::vector<ReadRange> out;
    info.getReadRanges(0, 40, &out);  // read offsets [0, 40)
    EXPECT_EQ(0, out.size());
}
