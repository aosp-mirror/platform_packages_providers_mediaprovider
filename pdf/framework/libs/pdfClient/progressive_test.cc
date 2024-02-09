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

#include <android-base/file.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <stddef.h>
#include <unistd.h>

#include <memory>
#include <string>
#include <utility>

// Goes first due to conflicts.
#include "document.h"
#include "file.h"
// #include "file/base/helpers.h"
// #include "file/base/options.h"
// #include "file/base/path.h"
// #include "absl/log/check.h"
#include "cpp/fpdf_scopers.h"
#include "fpdf_dataavail.h"
#include "fpdfview.h"
#include "linux_fileops.h"

using pdfClient::Document;
using pdfClient::FileReader;
using pdfClient::LinuxFileOps;

namespace {

static const std::string kTestdata = "testdata";
static const std::string kAcroJsFile = "AcroJS.pdf";
static const std::string kLinearizedFile = "linearized.pdf";
static const std::string kNonLinearizedFile = "spanner.pdf";

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTestFile(std::string filename) {
    return GetTestDataDir() + "/" + kTestdata + "/" + filename;
}

// std::string GetTempFile(const char* filename) {
//     return JoinPath(::testing::TempDir(), filename);
// }

bool IsDocAvail(const FileReader& fileReader) {
    return FPDFAvail_IsDocAvail(fileReader.fpdf_avail_.get(), pdfClient::LogOnlyDownloadHints()) !=
           0;
}

bool IsPageAvail(const FileReader& fileReader, int page) {
    return FPDFAvail_IsPageAvail(fileReader.fpdf_avail_.get(), page,
                                 pdfClient::LogOnlyDownloadHints()) != 0;
}

TEST(Test, IsLinearized) {
    LinuxFileOps::FDCloser fd(open(GetTestFile(kLinearizedFile).c_str(), O_RDONLY));
    std::unique_ptr<Document> doc;
    EXPECT_EQ(pdfClient::LOADED,
              Document::Load(std::make_unique<FileReader>(std::move(fd)), "", true, &doc));
    EXPECT_TRUE(doc->IsLinearized());

    LinuxFileOps::FDCloser fd2(open(GetTestFile(kNonLinearizedFile).c_str(), O_RDONLY));
    EXPECT_EQ(pdfClient::LOADED,
              Document::Load(std::make_unique<FileReader>(std::move(fd2)), "", true, &doc));
    EXPECT_FALSE(doc->IsLinearized());
}

// TEST(Test, ReadProgressive_singleEnded) {
//     std::string original;
//     CHECK_OK(file::GetContents(GetTestFile(kLinearizedFile), &original, file::Defaults()));
//     size_t completeSize = original.length();
//
//     LinuxFileOps::FDCloser stream(
//             open(GetTempFile("stream").c_str(), O_RDWR | O_CREAT | O_APPEND, 0600));
//     EXPECT_GT(stream.get(), 0);
//
//     FileReader fileReader(std::move(stream), completeSize);
//     EXPECT_EQ(0, pdfClient::GetFileSize(fileReader.Fd()));
//     EXPECT_FALSE(IsDocAvail(fileReader));
//
//     size_t written = write(fileReader.Fd(), original.data(), completeSize / 2);
//     EXPECT_EQ(completeSize / 2, written);
//     EXPECT_EQ(completeSize / 2, pdfClient::GetFileSize(fileReader.Fd()));
//     // As of http://crrev.com/2483633002, the doc is available before the xref
//     // table is loaded.
//     EXPECT_TRUE(IsDocAvail(fileReader));
//
//     written += write(fileReader.Fd(), original.data(), completeSize - written);
//     EXPECT_EQ(completeSize, written);
//     EXPECT_EQ(completeSize, pdfClient::GetFileSize(fileReader.Fd()));
//     EXPECT_TRUE(IsDocAvail(fileReader));
// }

// Ensure that http://b/21314248 stays fixed.
TEST(Test, AcroJs) {
    LinuxFileOps::FDCloser fd(open(GetTestFile(kAcroJsFile).c_str(), O_RDONLY));
    std::unique_ptr<Document> doc;
    EXPECT_EQ(pdfClient::LOADED, Document::Load(std::make_unique<FileReader>(std::move(fd)), "",
                                                /* closeFdOnFailure= */ true, &doc));
    EXPECT_EQ(594, doc->GetPage(0)->Width());
    EXPECT_EQ(594, doc->GetPage(1)->Width());
}

// Ensure that http://b/22254113 stays fixed.
TEST(Test, StatusFive) {
    LinuxFileOps::FDCloser fd(open(GetTestFile("status5.pdf").c_str(), O_RDONLY));
    std::unique_ptr<Document> doc;
    EXPECT_EQ(pdfClient::LOADED, Document::Load(std::make_unique<FileReader>(std::move(fd)), "",
                                                /* closeFdOnFailure= */ true, &doc));
}

}  // namespace