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

#include "document.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <stddef.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <utility>

#include "extractors.h"
#include "file.h"
#include "logging.h"
#include "page.h"
// #include "testing/looks_like.h"
// #include "testing/undeclared_outputs.h"
//  #include "file/base/path.h"
#include "linux_fileops.h"
// #include "image/base/rawimage.h"
// #include "image/codec/pngencoder.h"
#include "fpdfview.h"

using pdfClient::Document;
using pdfClient::FileReader;
using pdfClient::LinuxFileOps;
using pdfClient::Page;
using std::string_view;
// using image_base::RawImage;

namespace {

#define LOG_TAG "pdf/apk/jni/pdfClient/document_test.cc"

const std::string kTestdata = "testdata";
const std::string kSekretNoPassword = "sekret_no_password.pdf";
const std::string kSecretWithPassword = "sekret_password_banana.pdf";
const std::string kPassword = "banana";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

// std::string GetTempFile(string_view filename) {
//     return JoinPath(::testing::TempDir(), filename);
// }

// void SaveAsPngTestOutput(const RawImage& img, string_view name, string_view description) {
//     image_codec::PngEncoder encoder;
//     encoder.set_compression_level(6);
//     std::string result;
//     QCHECK(encoder.EncodeImage(&img, &result));
//     pdfClient::testing::SaveUndeclaredOutput(name, description, "image/png", result);
// }

// std::unique_ptr<RawImage> RenderPage(const std::shared_ptr<Page> page) {
//     std::unique_ptr<RawImage> dest(new RawImage());
//
//     // Render to a standard max dimension.
//     static constexpr int kMaxDimension = 1024;
//     QCHECK_GT(page->Width(), 0) << "0 page width";
//     QCHECK_GT(page->Height(), 0) << "0 page height";
//     const float scale = static_cast<float>(kMaxDimension) / std::max(page->Width(), page->Height());
//     size_t width = static_cast<size_t>(page->Width() * scale);
//     size_t height = static_cast<size_t>(page->Height() * scale);
//
//     QCHECK(dest->Resize(width, height, image_base::SimpleImage::Colorspace::RGBA))
//             << "could not resize dest image to " << width << "x" << height;
//
//     pdfClient::BufferWriter pix_writer(dest->mutable_pixel_data());
//     QCHECK(page->RenderPage(width, height, false, &pix_writer))
//             << "could not render page at " << width << "x" << height;
//
//     return dest;
// }

std::unique_ptr<Document> LoadDocument(string_view path, const char* password = nullptr) {
    LinuxFileOps::FDCloser fd(open(path.data(), O_RDONLY));
    CHECK_GT(fd.get(), 0);
    std::unique_ptr<Document> document;
    CHECK_EQ(pdfClient::LOADED, Document::Load(std::make_unique<FileReader>(std::move(fd)), password,
                                               /* closeFdOnFailure= */ true, &document))
            << "could not load " << path << " with password " << (password ? password : "nullptr");
    return document;
}

// TEST(Test, CloneWithoutEncryption) {
//     std::unique_ptr<Document> doc = LoadDocument(GetTestFile(kSecretWithPassword), kPassword);
//     std::unique_ptr<RawImage> encrypted_page_image = RenderPage(doc->GetPage(0));
//     SaveAsPngTestOutput(*encrypted_page_image, "expected.png", "Expected");
//     std::string cloned_path = GetTempFile("cloned.pdf");
//     LinuxFileOps::FDCloser out(
//             open(cloned_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
//     ASSERT_GT(out.get(), 0);
//     ASSERT_TRUE(doc->CloneDocumentWithoutSecurity(std::move(out)));
//     std::unique_ptr<Document> cloned = LoadDocument(cloned_path);
//     std::unique_ptr<RawImage> cloned_page_image = RenderPage(cloned->GetPage(0));
//     SaveAsPngTestOutput(*cloned_page_image, "actual.png", "Actual");
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*encrypted_page_image, *cloned_page_image));
//     std::unique_ptr<Document> no_password = LoadDocument(GetTestFile(kSekretNoPassword));
//     std::unique_ptr<RawImage> no_password_page_image = RenderPage(no_password->GetPage(0));
//     SaveAsPngTestOutput(*cloned_page_image, "reference.png", "Reference");
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*no_password_page_image, *cloned_page_image));
// }

// TEST(Test, SaveAs) {
//     std::unique_ptr<Document> doc = LoadDocument(GetTestFile(kSecretWithPassword), kPassword);
//     std::unique_ptr<RawImage> encrypted_page_image = RenderPage(doc->GetPage(0));
//     SaveAsPngTestOutput(*encrypted_page_image, "expected.png", "Expected");
//     std::string copied_path = GetTempFile("copied.pdf");
//     LinuxFileOps::FDCloser out(
//             open(copied_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
//     ASSERT_GT(out.get(), 0);
//     ASSERT_TRUE(doc->SaveAs(std::move(out)));
//
//     // Expect to fail for lack of password.
//     LinuxFileOps::FDCloser in(open(copied_path.c_str(), O_RDONLY));
//     ASSERT_GT(in.get(), 0);
//     std::unique_ptr<Document> should_fail;
//     auto fr = std::make_unique<FileReader>(std::move(in));
//     QCHECK_EQ(pdfClient::REQUIRES_PASSWORD,
//               Document::Load(std::move(fr), nullptr, /* closeFdOnFailure= */ true, &should_fail))
//             << "should not have been able to load copy of " << kSecretWithPassword
//             << " without password";
//
//     // Should load with same password.
//     std::unique_ptr<Document> copied = LoadDocument(copied_path, kPassword);
//     std::unique_ptr<RawImage> copied_page_image = RenderPage(copied->GetPage(0));
//     SaveAsPngTestOutput(*copied_page_image, "actual.png", "Actual");
//     EXPECT_TRUE(pdfClient::testing::LooksLike(*encrypted_page_image, *copied_page_image));
// }

/*
 * Tests the retention of std::shared_ptr<Page> as requested.
 */
TEST(Test, GetPageTest) {
    std::unique_ptr<Document> doc = LoadDocument(GetTestFile(kSekretNoPassword), nullptr);

    // retain == false so should be a new copy each time
    std::shared_ptr<Page> page_zero_copy_one = doc->GetPage(0);
    std::shared_ptr<Page> page_zero_copy_two = doc->GetPage(0);
    EXPECT_NE(page_zero_copy_one, page_zero_copy_two);

    // retain == true so should get the same ptr
    std::shared_ptr<Page> page_zero_copy_three = doc->GetPage(0, true);
    std::shared_ptr<Page> page_zero_copy_four = doc->GetPage(0, true);
    EXPECT_EQ(page_zero_copy_three, page_zero_copy_four);

    // since it's already retained, shouldn't matter if we request with
    // retain == false, should still get same one
    std::shared_ptr<Page> page_zero_copy_five = doc->GetPage(0);
    EXPECT_EQ(page_zero_copy_four, page_zero_copy_five);
}

}  // namespace

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    FPDF_InitLibrary();
    int status = RUN_ALL_TESTS();
    // Destroy the library to keep the memory leak checker happy.
    FPDF_DestroyLibrary();
    return status;
}