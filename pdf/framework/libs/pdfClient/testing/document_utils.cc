#include "document_utils.h"

#include <android-base/file.h>
#include <android-base/logging.h>

#include <algorithm>
#include <memory>
#include <string>

#include "../document.h"
#include "../extractors.h"
#include "../file.h"
#include "../page.h"
// #include "file/base/path.h"
#include "../linux_fileops.h"
// #include "image/base/rawimage.h"

using pdfClient::LinuxFileOps;

namespace pdfClient {
namespace testing {

std::string GetTestDataDir() {
    return android::base::GetExecutableDirectory();
}

std::string CreateTestFilePath(const std::string file_name, const std::string resources_path) {
    return GetTestDataDir() + "/" + resources_path + "/" + file_name;
}

// std::unique_ptr<image_base::RawImage> RenderPage(const Page& page) {
//   std::unique_ptr<image_base::RawImage> dest =
//       std::make_unique<image_base::RawImage>();
//
//   // Render to a standard max dimension.
//   static constexpr int kMaxDimension = 1024;
//   QCHECK_GT(page.Width(), 0) << "0 page width";
//   QCHECK_GT(page.Height(), 0) << "0 page height";
//   const float scale =
//       static_cast<float>(kMaxDimension) / std::max(page.Width(), page.Height());
//   size_t width = static_cast<size_t>(page.Width() * scale);
//   size_t height = static_cast<size_t>(page.Height() * scale);
//
//   QCHECK(dest->Resize(width, height, image_base::SimpleImage::Colorspace::RGBA))
//       << "could not resize dest image to " << width << "x" << height;
//
//   pdfClient::BufferWriter pix_writer(dest->mutable_pixel_data());
//   QCHECK(page.RenderPage(width, height, false, &pix_writer))
//       << "could not render page at " << width << "x" << height;
//
//   return dest;
// }

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