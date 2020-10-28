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
    if (ranges.size() == 0) return;
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
 * Removes any range with zero size.
 *
 * If ranges are modified, it will be guaranteed to be sorted.
 */
static void removeZeroSizeRedactionRanges(vector<RedactionRange>& ranges) {
    int newRangesSize = ranges.size();
    for (int i = 0; i < ranges.size(); ++i) {
        if (ranges[i].first == ranges[i].second) {
            // This redaction range is of length zero, hence we don't have anything
            // to redact in this range, so remove it from the redaction_ranges_.
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
 * This function assumes redaction_ranges_ within RedactionInfo is sorted.
 */
bool RedactionInfo::hasOverlapWithReadRequest(size_t size, off64_t off) const {
    if (!isRedactionNeeded() || off >= redaction_ranges_.back().second ||
        off + size <= redaction_ranges_.front().first) {
        return false;
    }
    return true;
}

/**
 * Sets the redaction ranges in RedactionInfo, sort the ranges and merge
 * overlapping ranges.
 */
void RedactionInfo::processRedactionRanges(int redaction_ranges_num,
                                           const off64_t* redaction_ranges) {
    redaction_ranges_.resize(redaction_ranges_num);
    for (int i = 0; i < redaction_ranges_num; ++i) {
        redaction_ranges_[i].first = static_cast<off64_t>(redaction_ranges[2 * i]);
        redaction_ranges_[i].second = static_cast<off64_t>(redaction_ranges[2 * i + 1]);
    }
    std::sort(redaction_ranges_.begin(), redaction_ranges_.end());
    removeZeroSizeRedactionRanges(redaction_ranges_);
    mergeOverlappingRedactionRanges(redaction_ranges_);
}

int RedactionInfo::size() const {
    return redaction_ranges_.size();
}

bool RedactionInfo::isRedactionNeeded() const {
    return size() > 0;
}

RedactionInfo::RedactionInfo(int redaction_ranges_num, const off64_t* redaction_ranges) {
    if (redaction_ranges == 0) return;
    processRedactionRanges(redaction_ranges_num, redaction_ranges);
}

unique_ptr<vector<RedactionRange>> RedactionInfo::getOverlappingRedactionRanges(size_t size,
                                                                                off64_t off) const {
    if (hasOverlapWithReadRequest(size, off)) {
        const off64_t start = off;
        const off64_t end = static_cast<off64_t>(off + size);

        auto first_redaction = redaction_ranges_.end();
        auto last_redaction = redaction_ranges_.begin();
        for (auto iter = redaction_ranges_.begin(); iter != redaction_ranges_.end(); ++iter) {
            if (iter->second > start && iter->first < end) {
                if (iter < first_redaction) first_redaction = iter;
                if (iter > last_redaction) last_redaction = iter;
            }

            if (iter->first >= end) {
                break;
            }
        }

        if (first_redaction != redaction_ranges_.end()) {
            CHECK(first_redaction <= last_redaction);
            return std::make_unique<vector<RedactionRange>>(first_redaction, last_redaction + 1);
        }
    }
    return std::make_unique<vector<RedactionRange>>();
}

void RedactionInfo::getReadRanges(off64_t off, size_t size, std::vector<ReadRange>* out) const {
    const auto rr = getOverlappingRedactionRanges(size, off);
    const size_t num_ranges = rr->size();
    if (num_ranges == 0) {
        return;
    }

    const off64_t read_start = off;
    const off64_t read_end = static_cast<off64_t>(read_start + size);

    // The algorithm for computing redaction ranges is very simple.
    // Given a set of overlapping redaction ranges [s1, e1) [s2, e2) .. [sN, eN) for a read
    // [s, e)
    //
    // We can construct a series of indices that we know will be the starts of every read range
    // that we intend to return. Then, it's relatively simple to compute the lengths of the ranges.
    // Also note that the read ranges we return always alternate in whether they're redacting or
    // not. i.e, we will never return two consecutive redacting ranges or non redacting ranges.
    std::vector<off64_t> sorted_indices;

    // Compute the list of indices -- this list will always contain { e1, s2, e2... sN }
    // In addition, it may contain s or both (s and s1), depending on the start index.
    // In addition, it may contain e or both (e and eN), depending on the end index.
    //
    // For a concrete example, consider ranges [10, 20) and [30, 40)
    // For a read [0, 60) : sorted_indices will be { 0, 10, 20, 30, 40, 60 } is_first = false
    // For a read [15, 60) : sorted_indices will be { 15, 20, 30, 40, 60 } is_first = true
    // For a read [0, 35) : sorted_indices will be { 0, 10, 20, 30, 35 } is_first = false
    // For a read [15, 35) : sorted_indices will be { 15, 20, 30, 35 } is_first = true
    for (int i = 0; i < num_ranges; ++i) {
        sorted_indices.push_back(rr->at(i).first);
        sorted_indices.push_back(rr->at(i).second);
    }

    // Find the right position for read_start in sorted_indices
    // Either insert at the beginning or replace s1 with read_start
    bool is_first_range_redaction = true;
    if (read_start < rr->at(0).first) {
        is_first_range_redaction = false;
        sorted_indices.insert(sorted_indices.begin(), read_start);
    } else {
        sorted_indices.front() = read_start;
    }

    // Find the right position for read_end in sorted_indices
    // Either insert at the end or replace eN with read_end
    if (read_end > rr->at(num_ranges - 1).second) {
        sorted_indices.push_back(read_end);
    } else {
        sorted_indices.back() = read_end;
    }

    bool is_redaction = is_first_range_redaction;
    for (int i = 0; i < (sorted_indices.size() - 1); ++i) {
        const off64_t read_size = sorted_indices[i + 1] - sorted_indices[i];
        CHECK(read_size > 0);
        out->push_back(ReadRange(sorted_indices[i], read_size, is_redaction));
        is_redaction = !is_redaction;
    }
}

}  // namespace fuse
}  // namespace mediaprovider
