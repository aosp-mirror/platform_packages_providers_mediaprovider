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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_RECT_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_RECT_H_

// Simple points and rectangles.

namespace pdfClient {

// A point with integer precision
struct Point_i {
    int x;
    int y;
};

// A point with double precision
struct Point_d {
    double x;
    double y;
};

// A rectangle with integer precision
struct Rectangle_i {
    // The x-coordinate of the left-top corner (lesser value).
    int left;
    // The y-coordinate of the left-top corner (lesser value).
    int top;
    // The x-coordinate of the right-bottom corner (greater value).
    int right;
    // The y-coordinate of the right-bottom corner (greater value).
    int bottom;

    int Width() const { return right - left; }

    int Height() const { return bottom - top; }

    Point_i Center() const { return Point_i{(left + right) / 2, (top + bottom) / 2}; }
};

// A rectangle with double precision
struct Rectangle_d {
    // The x-coordinate of the left-top corner (lesser value).
    double left;
    // The y-coordinate of the left-top corner (lesser value).
    double top;
    // The x-coordinate of the right-bottom corner (greater value).
    double right;
    // The y-coordinate of the right-bottom corner (greater value).
    double bottom;

    double Width() const { return right - left; }

    double Height() const { return bottom - top; }

    Point_d Center() const { return Point_d{(left + right) / 2, (top + bottom) / 2}; }
};

// Check if two points are equal:
inline bool operator==(const Point_i& lhs, const Point_i& rhs) {
    return lhs.x == rhs.x && lhs.y == rhs.y;
}

inline bool operator!=(const Point_i& lhs, const Point_i& rhs) {
    return !(lhs == rhs);
}

// Check if two rectangles are equal:
inline bool operator==(const Rectangle_i& lhs, const Rectangle_i& rhs) {
    return lhs.left == rhs.left && lhs.top == rhs.top && lhs.right == rhs.right &&
           lhs.bottom == rhs.bottom;
}

inline bool operator!=(const Rectangle_i& lhs, const Rectangle_i& rhs) {
    return !(lhs == rhs);
}

// Check if this rectangle has zero area.
inline bool IsEmpty(const Rectangle_i& rect) {
    return rect.Width() <= 0 || rect.Height() <= 0;
}

inline bool IsEmpty(const Rectangle_d& rect) {
    return rect.Width() <= 0 || rect.Height() <= 0;
}

// Initialize a point:
Point_i IntPoint(const int x, const int y);

// Initialize a point:
Point_d DoublePoint(const double x, const double y);

// Initialize a sorted Rectangle_i with corners at these two points.
Rectangle_i IntRect(const int x1, const int y1, const int x2, const int y2);

// Initialize a sorted Rectangle_i with corners at these two points.
Rectangle_i IntRect(const Point_i& p1, const Point_i& p2);

// Initialize a Rectangle_i based on its top left corner and size
Rectangle_i IntRectWithSize(const int left, const int top, const int width, const int height);

// Returns the smallest Rectangle_i that surrounds the given Rectangle_d.
Rectangle_i OuterIntRect(const Rectangle_d& input);

// Initialize a sorted Rectangle_d with corners at these two points.
Rectangle_d DoubleRect(const double x1, const double y1, const double x2, const double y2);

// Returns the intersection of two rectangles - but if they don't intersect
// at all, returns the rectangle (0, 0)-(0, 0).
Rectangle_i Intersect(const Rectangle_i& lhs, const Rectangle_i& rhs);
Rectangle_d Intersect(const Rectangle_d& lhs, const Rectangle_d& rhs);

// Returns the union of two Rectangles.
Rectangle_i Union(const Rectangle_i& lhs, const Rectangle_i& rhs);
Rectangle_d Union(const Rectangle_d& lhs, const Rectangle_d& rhs);

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_RECT_H_