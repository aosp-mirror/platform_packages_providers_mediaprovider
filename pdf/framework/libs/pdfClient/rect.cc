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

#include "rect.h"

#include <algorithm>
#include <cmath>

namespace pdfClient {

Point_i IntPoint(const int x, const int y) {
    return Point_i{x, y};
}

Point_d DoublePoint(const double x, const double y) {
    return Point_d{x, y};
}

Rectangle_i IntRect(const int x1, const int y1, const int x2, const int y2) {
    Rectangle_i output;
    output.left = std::min(x1, x2);
    output.top = std::min(y1, y2);
    output.right = std::max(x1, x2);
    output.bottom = std::max(y1, y2);
    return output;
}

Rectangle_i IntRect(const Point_i& p1, const Point_i& p2) {
    return IntRect(p1.x, p1.y, p2.x, p2.y);
}

Rectangle_i IntRectWithSize(const int left, const int top, const int width, const int height) {
    return Rectangle_i{left, top, left + width, top + height};
}

Rectangle_i OuterIntRect(const Rectangle_d& input) {
    return IntRect(floor(input.left), floor(input.top), ceil(input.right), ceil(input.bottom));
}

Rectangle_d DoubleRect(const double x1, const double y1, const double x2, const double y2) {
    Rectangle_d output;
    output.left = std::min(x1, x2);
    output.top = std::min(y1, y2);
    output.right = std::max(x1, x2);
    output.bottom = std::max(y1, y2);
    return output;
}

Rectangle_i Intersect(const Rectangle_i& lhs, const Rectangle_i& rhs) {
    Rectangle_i output;
    output.left = std::max(lhs.left, rhs.left);
    output.top = std::max(lhs.top, rhs.top);
    output.right = std::min(lhs.right, rhs.right);
    output.bottom = std::min(lhs.bottom, rhs.bottom);
    if (output.Width() < 0 || output.Height() < 0) {
        return IntRect(0, 0, 0, 0);
    }
    return output;
}

Rectangle_d Intersect(const Rectangle_d& lhs, const Rectangle_d& rhs) {
    Rectangle_d output;
    output.left = std::max(lhs.left, rhs.left);
    output.top = std::max(lhs.top, rhs.top);
    output.right = std::min(lhs.right, rhs.right);
    output.bottom = std::min(lhs.bottom, rhs.bottom);
    if (output.Width() < 0 || output.Height() < 0) {
        return DoubleRect(0, 0, 0, 0);
    }
    return output;
}

Rectangle_i Union(const Rectangle_i& lhs, const Rectangle_i& rhs) {
    Rectangle_i output;
    output.left = std::min(lhs.left, rhs.left);
    output.top = std::min(lhs.top, rhs.top);
    output.right = std::max(lhs.right, rhs.right);
    output.bottom = std::max(lhs.bottom, rhs.bottom);
    return output;
}

Rectangle_d Union(const Rectangle_d& lhs, const Rectangle_d& rhs) {
    Rectangle_d output;
    output.left = std::min(lhs.left, rhs.left);
    output.top = std::min(lhs.top, rhs.top);
    output.right = std::max(lhs.right, rhs.right);
    output.bottom = std::max(lhs.bottom, rhs.bottom);
    return output;
}

}  // namespace pdfClient