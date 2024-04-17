#include "document_utils.h"

#include <android-base/file.h>
#include <android-base/logging.h>

#include <algorithm>
#include <memory>
#include <string>

#include "../document.h"
#include "../file.h"
#include "../linux_fileops.h"
#include "../page.h"

using pdfClient::LinuxFileOps;

namespace pdfClient {
namespace testing {

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string GetTempFile(std::string filename) {
    return GetTestDataDir() + "/" + filename;
}

std::string CreateTestFilePath(const std::string file_name, const std::string resources_path) {
    return GetTestDataDir() + "/" + resources_path + "/" + file_name;
}

std::unique_ptr<pdfClient::Document> LoadDocument(std::string_view path, const char* password) {
    LinuxFileOps::FDCloser in(open(path.data(), O_RDONLY));
    CHECK_GT(in.get(), 0);

    std::unique_ptr<pdfClient::Document> doc;
    CHECK_EQ(pdfClient::LOADED,
             pdfClient::Document::Load(std::make_unique<pdfClient::FileReader>(std::move(in)),
                                       password,
                                       /* closeFdOnFailure= */ true, &doc))
            << "could not load " << path << " with password " << (password ? password : "nullptr");
    return doc;
}

}  // namespace testing
}  // namespace pdfClient
