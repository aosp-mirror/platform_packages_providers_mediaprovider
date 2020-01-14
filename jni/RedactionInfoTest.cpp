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
#include <vector>

#include "libfuse_jni/RedactionInfo.h"

using namespace mediaprovider::fuse;

using std::unique_ptr;
using std::vector;

unique_ptr<vector<RedactionRange>> createRedactionRangeVector(int num_rr, off64_t* rr) {
    auto res = std::make_unique<vector<RedactionRange>>();
    for (int i = 0; i < num_rr; ++i) {
        res->push_back(RedactionRange(rr[2 * i], rr[2 * i + 1]));
    }
    return res;
}

/**
 * Test the case where there are no redaction ranges.
 */
TEST(RedactionInfoTest, testNoRedactionRanges) {
    RedactionInfo info(0, nullptr);
    EXPECT_EQ(0, info.size());
    EXPECT_EQ(false, info.isRedactionNeeded());

    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1000, /*off*/ 1000);
    EXPECT_EQ(0, overlapping_rr->size());
}

/**
 * Test the case where there is 1 redaction range.
 */
TEST(RedactionInfoTest, testSingleRedactionRange) {
    off64_t ranges[2] = {
            1,
            10,
    };
    RedactionInfo info(1, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());
    // Overlapping ranges
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1000, /*off*/ 0);
    EXPECT_EQ(*(createRedactionRangeVector(1, ranges)), *overlapping_rr);

    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 5, /*off*/ 0);
    EXPECT_EQ(*(createRedactionRangeVector(1, ranges)), *overlapping_rr);

    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 5, /*off*/ 5);
    EXPECT_EQ(*(createRedactionRangeVector(1, ranges)), *overlapping_rr);

    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 10, /*off*/ 1);
    EXPECT_EQ(*(createRedactionRangeVector(1, ranges)), *overlapping_rr);

    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1, /*off*/ 1);
    EXPECT_EQ(*(createRedactionRangeVector(1, ranges)), *overlapping_rr);

    // Non-overlapping range
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 100, /*off*/ 11);
    EXPECT_EQ(*(createRedactionRangeVector(0, nullptr)), *overlapping_rr);

    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1, /*off*/ 11);
    EXPECT_EQ(*(createRedactionRangeVector(0, nullptr)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges don't require sorting or merging
 */
TEST(RedactionInfoTest, testSortedAndNonOverlappingRedactionRanges) {
    off64_t ranges[6] = {
            1, 10, 15, 21, 32, 40,
    };

    RedactionInfo info = RedactionInfo(3, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request strictly contains all ranges: [0, 49]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 50, /*off*/ 0);
    off64_t expected1[] = {
            1, 10, 15, 21, 32, 40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(3, expected1)), *overlapping_rr);

    // Read request strictly contains a subset of the ranges: [15, 40]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 26, /*off*/ 15);
    off64_t expected2[] = {
            15,
            21,
            32,
            40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);

    // Read request intersects with a subset of the ranges" [16, 32]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 17, /*off*/ 16);
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges require sorting
 */
TEST(RedactionInfoTest, testSortRedactionRanges) {
    off64_t ranges[6] = {
            1, 10, 32, 40, 15, 21,
    };

    RedactionInfo info = RedactionInfo(3, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request strictly contains all ranges: [0, 49]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 50, /*off*/ 0);
    off64_t expected1[] = {
            1, 10, 15, 21, 32, 40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(3, expected1)), *overlapping_rr);

    // Read request strictly contains a subset of the ranges: [15, 40]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 26, /*off*/ 15);
    off64_t expected2[] = {
            15,
            21,
            32,
            40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);

    // Read request intersects with a subset of the ranges" [16, 32]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 17, /*off*/ 16);
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges require sorting or merging
 */
TEST(RedactionInfoTest, testSortAndMergeRedactionRanges) {
    off64_t ranges[8] = {
            35, 40, 1, 10, 32, 35, 15, 21,
    };

    RedactionInfo info = RedactionInfo(4, ranges);
    EXPECT_EQ(3, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request strictly contains all ranges: [0, 49]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 50, /*off*/ 0);
    off64_t expected1[] = {
            1, 10, 15, 21, 32, 40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(3, expected1)), *overlapping_rr);

    // Read request strictly contains a subset of the ranges: [15, 40]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 26, /*off*/ 15);
    off64_t expected2[] = {
            15,
            21,
            32,
            40,
    };
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);

    // Read request intersects with a subset of the ranges" [16, 32]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 17, /*off*/ 16);
    EXPECT_EQ(*(createRedactionRangeVector(2, expected2)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges all merge into the first range
 */
TEST(RedactionInfoTest, testMergeAllRangesIntoTheFirstRange) {
    off64_t ranges[10] = {
            1, 100, 2, 99, 3, 98, 4, 97, 3, 15,
    };

    RedactionInfo info = RedactionInfo(5, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request equals the range: [1, 100]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 100, /*off*/ 1);
    off64_t expected[] = {1, 100};
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request is contained in the range: [15, 40]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 26, /*off*/ 15);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request that strictly contains all of the redaction ranges
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1000, /*off*/ 0);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges all merge into the last range
 */
TEST(RedactionInfoTest, testMergeAllRangesIntoTheLastRange) {
    off64_t ranges[10] = {
            4, 96, 3, 97, 2, 98, 1, 99, 0, 100,
    };

    RedactionInfo info = RedactionInfo(5, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request equals the range: [0, 100]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 100, /*off*/ 0);
    off64_t expected[] = {0, 100};
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request is contained in the range: [15, 40]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 26, /*off*/ 15);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request that strictly contains all of the redaction ranges
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 1000, /*off*/ 0);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);
}

/**
 * Test the case where the redaction ranges progressively merge
 */
TEST(RedactionInfoTest, testMergeAllRangesProgressively) {
    off64_t ranges[10] = {
            1, 11, 2, 12, 3, 13, 4, 14, 5, 15,
    };

    RedactionInfo info = RedactionInfo(5, ranges);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request equals the range: [1, 15]
    auto overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 15, /*off*/ 1);
    off64_t expected[] = {1, 15};
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request is contained in the range: [2, 12]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 10, /*off*/ 2);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    // Read request that strictly contains all of the redaction ranges
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 100, /*off*/ 0);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);

    off64_t reverse_rr[10] = {
            5, 15, 4, 14, 3, 13, 2, 12, 1, 11,
    };

    RedactionInfo reverse_info = RedactionInfo(5, reverse_rr);
    EXPECT_EQ(1, info.size());
    EXPECT_EQ(true, info.isRedactionNeeded());

    // Read request equals the range: [1, 15]
    overlapping_rr = info.getOverlappingRedactionRanges(/*size*/ 15, /*off*/ 1);
    EXPECT_EQ(*(createRedactionRangeVector(1, expected)), *overlapping_rr);
}
