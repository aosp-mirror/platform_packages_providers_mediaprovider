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

#define LOG_TAG "RedactionInfo"

#include "include/libfuse_jni/RedactionInfo.h"

#include <android-base/logging.h>

using std::unique_ptr;
using std::vector;

namespace mediaprovider {
namespace fuse {

/**
 * Merges any overlapping ranges into 1 range.
 *
 * Given ranges should be sorted, and they remain sorted.
 */
static void mergeOverlappingRedactionRanges(vector<RedactionRange>& ranges) {
    int newRangesSize = ranges.size();
    for (int i = 0; i < ranges.size() - 1; ++i) {
        if (ranges[i].second >= ranges[i + 1].first) {
            ranges[i + 1].first = ranges[i].first;
            ranges[i + 1].second = std::max(ranges[i].second, ranges[i + 1].second);
            // Invalidate the redundant range
            ranges[i].first = LONG_MAX;
            ranges[i].second = LONG_MAX;
            newRangesSize--;
        }
    }
    if (newRangesSize < ranges.size()) {
        // Move invalid ranges to end of array
        std::sort(ranges.begin(), ranges.end());
        ranges.resize(newRangesSize);
    }
}

/**
 * Determine whether the read request overlaps with the redaction ranges
 * defined by the given RedactionInfo.
 *
 * This function assumes redactionRanges within RedactionInfo is sorted.
 */
bool RedactionInfo::hasOverlapWithReadRequest(size_t size, off64_t off) {
    if (!isRedactionNeeded() || off > redactionRanges.back().second ||
            off + size < redactionRanges.front().first) {
        return false;
    }
    return true;
}

/**
 * Sets the redaction ranges in RedactionInfo, sort the ranges and merge
 * overlapping ranges.
 */
void RedactionInfo::processRedactionRanges(int redaction_ranges_num, off64_t* redaction_ranges) {
    redactionRanges.resize(redaction_ranges_num);
    for (int i = 0; i < redaction_ranges_num; ++i) {
        redactionRanges[i].first = static_cast<off64_t>(redaction_ranges[2 * i]);
        redactionRanges[i].second = static_cast<off64_t>(redaction_ranges[2 * i + 1]);
    }
    std::sort(redactionRanges.begin(), redactionRanges.end());
    mergeOverlappingRedactionRanges(redactionRanges);
}

int RedactionInfo::size() {
    return redactionRanges.size();
}

bool RedactionInfo::isRedactionNeeded() {
    return size() > 0;
}

RedactionInfo::RedactionInfo(int redaction_ranges_num, off64_t* redaction_ranges) {
    if (redaction_ranges == 0) return;
    processRedactionRanges(redaction_ranges_num, redaction_ranges);
}

unique_ptr<vector<RedactionRange>> RedactionInfo::getOverlappingRedactionRanges(size_t size,
                                                                                off64_t off) {
    LOG(DEBUG) << "Computing redaction ranges for request: sz = " << size << " off = " << off;
    if (hasOverlapWithReadRequest(size, off)) {
        auto first_redaction = redactionRanges.end();
        auto last_redaction = redactionRanges.end();
        for (auto iter = redactionRanges.begin(); iter != redactionRanges.end(); ++iter) {
            RedactionRange& rr = *iter;
            // Look for the first range that overlaps with the read request
            if (first_redaction == redactionRanges.end() && off <= rr.second &&
                    off + size >= rr.first) {
                first_redaction = iter;
            } else if (first_redaction != redactionRanges.end() && off + size < rr.first) {
                // Once we're in the read request range, we start checking if
                // we're out of it so we can return the result to the caller
                break;
            }
            last_redaction = iter;
        }
        if (first_redaction != redactionRanges.end()) {
            LOG(DEBUG) << "Returning " << (int)(last_redaction - first_redaction + 1)
                       << " redaction ranges!";
            return std::make_unique<vector<RedactionRange>>(first_redaction, last_redaction + 1);
        }
    }
    LOG(DEBUG) << "No relevant redaction ranges!";
    return std::make_unique<vector<RedactionRange>>();
}
}  // namespace fuse
}  // namespace mediaprovider