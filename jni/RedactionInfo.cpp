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
            if (iter->second >= start && iter->first < end) {
                if (iter < first_redaction) first_redaction = iter;
                if (iter > last_redaction) last_redaction = iter;
            }

            if (iter->first >= end) {
                break;
            }
        }

        CHECK(first_redaction <= last_redaction);
        return std::make_unique<vector<RedactionRange>>(first_redaction, last_redaction + 1);
    }
    return std::make_unique<vector<RedactionRange>>();
}

void RedactionInfo::getReadRanges(off64_t off, size_t size, std::vector<ReadRange>* out) const {
    auto rr = getOverlappingRedactionRanges(size, off);
    if (rr->size() == 0) {
        return;
    }

    // Add a sentinel redaction range to make sure we don't go out of vector
    // limits when computing the end of the last non-redacted range.
    // This ranges is invalid because its starting point is larger than it's ending point.
    rr->push_back(RedactionRange(LLONG_MAX, LLONG_MAX - 1));

    int rr_idx = 0;
    off64_t start = off;
    const off64_t read_end = static_cast<off64_t>(start + size);

    while (true) {
        const auto& current_redaction = rr->at(rr_idx);
        off64_t end;
        if (current_redaction.first <= start && start < current_redaction.second) {
            // |start| is within a redaction range, so we must serve a redacted read.
            end = std::min(read_end, current_redaction.second);
            out->push_back(ReadRange(start, (end - start), true /* is_redaction */));
            rr_idx++;
        } else {
            // |start| is either before the current redaction range, or beyond the end
            // of the last redaction range, in which case redaction.first is LLONG_MAX.
            end = std::min(read_end, current_redaction.first);
            out->push_back(ReadRange(start, (end - start), false /* is_redaction */));
        }

        start = end;
        // If we've done things correctly, start must point at |off + size| once we're
        // through computing all of our redaction ranges.
        if (start == read_end) {
            break;
        }
        // If we're continuing iteration, the start of the next range must always be within
        // the read bounds.
        CHECK(start < read_end);
    }
}

}  // namespace fuse
}  // namespace mediaprovider
