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
#include "fpdfview.h"
#include "linux_fileops.h"
#include "logging.h"
#include "page.h"

using pdfClient::Document;
using pdfClient::FileReader;
using pdfClient::LinuxFileOps;
using pdfClient::Page;
using std::string_view;

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

std::string GetTempFile(std::string filename) {
    return GetTestDataDir() + "/" + filename;
}

std::unique_ptr<Document> LoadDocument(string_view path, const char* password = nullptr) {
    LinuxFileOps::FDCloser fd(open(path.data(), O_RDONLY));
    CHECK_GT(fd.get(), 0);
    std::unique_ptr<Document> document;
    CHECK_EQ(pdfClient::LOADED, Document::Load(std::make_unique<FileReader>(std::move(fd)), password,
                                               /* closeFdOnFailure= */ true, &document))
            << "could not load " << path << " with password " << (password ? password : "nullptr");
    return document;
}

void compareDocuments(const std::shared_ptr<Page> page_orig,
                      const std::shared_ptr<Page> page_copied) {
    static constexpr int kMaxDimension = 1024;
    CHECK_GT(page_orig->Width(), 0) << "0 page width";
    CHECK_GT(page_orig->Height(), 0) << "0 page height";
    const float scale_orig =
            static_cast<float>(kMaxDimension) / std::max(page_orig->Width(), page_orig->Height());
    size_t width_orig = static_cast<size_t>(page_orig->Width() * scale_orig);
    size_t height_orig = static_cast<size_t>(page_orig->Height() * scale_orig);

    CHECK_GT(page_copied->Width(), 0) << "0 page width";
    CHECK_GT(page_copied->Height(), 0) << "0 page height";
    const float scale_copied = static_cast<float>(kMaxDimension) /
                               std::max(page_copied->Width(), page_copied->Height());
    size_t width_copied = static_cast<size_t>(page_copied->Width() * scale_copied);
    size_t height_copied = static_cast<size_t>(page_copied->Height() * scale_copied);

    ASSERT_EQ(width_orig, width_copied);
    ASSERT_EQ(height_orig, height_copied);
    ASSERT_EQ(scale_orig, scale_copied);
}

void loadDocumentWithoutPassword(std::string fpath) {
    // Expect to fail for lack of password.
    LinuxFileOps::FDCloser in(open(fpath.c_str(), O_RDONLY));
    ASSERT_GT(in.get(), 0);
    std::unique_ptr<Document> should_fail;
    auto fr = std::make_unique<FileReader>(std::move(in));
    CHECK_EQ(pdfClient::REQUIRES_PASSWORD,
             Document::Load(std::move(fr), nullptr, /* closeFdOnFailure= */ true, &should_fail))
            << "should not have been able to load copy of " << kSecretWithPassword
            << " without password";
}

TEST(Test, CloneWithoutEncryption) {
    std::unique_ptr<Document> doc =
            LoadDocument(GetTestFile(kSecretWithPassword), kPassword.c_str());
    std::string cloned_path = GetTempFile("cloned.pdf");
    LinuxFileOps::FDCloser out(open(cloned_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
    ASSERT_GT(out.get(), 0);
    ASSERT_TRUE(doc->CloneDocumentWithoutSecurity(std::move(out)));
    std::unique_ptr<Document> cloned = LoadDocument(cloned_path);
    compareDocuments(doc->GetPage(0), cloned->GetPage(0));
}

TEST(Test, SaveAs) {
    std::unique_ptr<Document> doc_orig =
            LoadDocument(GetTestFile(kSecretWithPassword), kPassword.c_str());
    std::string copied_path = GetTempFile("copied.pdf");
    LinuxFileOps::FDCloser out(open(copied_path.c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
    ASSERT_GT(out.get(), 0);
    ASSERT_TRUE(doc_orig->SaveAs(std::move(out)));
    loadDocumentWithoutPassword(copied_path);
    // Should load with same password.
    std::unique_ptr<Document> copied = LoadDocument(copied_path, kPassword.c_str());
    compareDocuments(doc_orig->GetPage(0), copied->GetPage(0));
}

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
