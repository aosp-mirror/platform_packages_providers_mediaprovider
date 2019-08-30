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

#ifndef MEDIA_PROVIDER_FUSE_REDACTIONINFO_H_
#define MEDIA_PROVIDER_FUSE_REDACTIONINFO_H_

#include <vector>

namespace mediaprovider {
namespace fuse {

/**
 * Type that represents a single redaction range within a file.
 * first is the offset of the first byte in the redaction range within the file
 * second is the offset of the last byte in the redaction range within the file
 * Ranges are inclusive.
 */
typedef std::pair<off64_t, off64_t> RedactionRange;

class RedactionInfo {
  public:
    /**
     * Constructs a new instance of RedactionInfo based on the given redaction
     * ranges.
     *
     * @param redaction_ranges_num number of redaction ranges, essentially HALF
     * the size of the array redaction_ranges. If there are no redaction ranges,
     * this value must be set to 0.
     *
     * @param redaction_ranges array that defines the redaction ranges in
     * the following way:
     * redaction ranges n = [ redaction_ranges[2n], redaction_ranges[2n+1]]
     * This means that this array's length should be TWICE the value of
     * redaction_ranges_num
     */
    RedactionInfo(int redaction_ranges_num, off64_t* redaction_ranges);
    /**
     * Constructs a new instance of RedactionInfo with no redaction ranges.
     */
    RedactionInfo() = default;
    /**
     * Calls d'tor for redactionRanges (vector).
     */
    ~RedactionInfo() = default;
    /**
     * Calculates the redaction ranges that overlap with a given read request.
     * The read request is defined by its size and the offset of its first byte.
     *
     * <p>The returned ranges are guaranteed to be:
     *     * Non-overlapping (with each other)
     *     * Sorted in an ascending order of offset
     *
     * @param size size of the read request
     * @param off offset of the first byte of the read request
     * @return unique_ptr to a vector of RedactionRanges. If there are no
     * relevant redaction ranges, the vector will be empty.
     */
    std::unique_ptr<std::vector<RedactionRange>> getOverlappingRedactionRanges(size_t size,
                                                                               off64_t off);
    /**
     * Returns whether any ranges need to be redacted.
     */
    bool isRedactionNeeded();
    /**
     * Returns number of redaction ranges.
     */
    int size();

  private:
    std::vector<RedactionRange> redactionRanges;
    void processRedactionRanges(int redaction_ranges_num, off64_t* redaction_ranges);
    bool hasOverlapWithReadRequest(size_t size, off64_t off);
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIA_PROVIDER_FUSE_REDACTIONINFO_H_
