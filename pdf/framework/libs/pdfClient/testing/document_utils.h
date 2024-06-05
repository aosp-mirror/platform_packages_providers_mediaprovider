#ifndef APPS_VIEWER_ANDROID_pdfClient_TESTING_DOCUMENT_UTILS_H_
#define APPS_VIEWER_ANDROID_pdfClient_TESTING_DOCUMENT_UTILS_H_

#include <fcntl.h>

#include <memory>
#include <string>

#include "../document.h"
#include "../file.h"
#include "../page.h"
// #include "image/base/rawimage.h"

namespace pdfClient {
namespace testing {

// 0.0 value float to be used in image diff'ing.
static const float kZeroToleranceDifference = 0.0;

// Creates the full path to the file using ::testing::SrcDir().
std::string CreateTestFilePath(const std::string file_name, const std::string resources_path);

// Loads and returns a Document.
std::unique_ptr<pdfClient::Document> LoadDocument(std::string_view path,
                                                  const char* password = nullptr);

std::string GetTempFile(std::string filename);

}  // namespace testing
}  // namespace pdfClient
#endif  // APPS_VIEWER_ANDROID_pdfClient_TESTING_DOCUMENT_UTILS_H_
