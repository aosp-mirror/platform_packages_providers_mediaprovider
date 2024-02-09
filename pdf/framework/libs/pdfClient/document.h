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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_DOCUMENT_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_DOCUMENT_H_

#define APPNAME "PdfViewerPdfClientLayer"

#include <memory>
#include <unordered_map>
#include <utility>

#include "cpp/fpdf_scopers.h"
#include "file.h"
#include "form_filler.h"
#include "fpdf_formfill.h"
#include "fpdfview.h"
#include "linux_fileops.h"
#include "page.h"
#include "rect.h"

namespace pdfClient {

// Status of an attempt to load a document. See Java's PdfStatus.
enum Status { NONE, REQUIRES_PASSWORD, LOADED, PDF_ERROR, FILE_ERROR, NEED_MORE_DATA };

// Should be called once before using any other part of pdfClient.
void InitLibrary();

// One PDF Document, mostly a wrapper around FPDF_Document. Automatically
// closes the FPDF_DOCUMENT when it is destroyed.
class Document {
  public:
    // Load the document from the given reader using the given password.
    // If the returned status is LOADED, then a new Document is returned which
    // now has ownership of the given FileReader.
    // Any other status will not return a new Document and will not take
    // ownership of the given FileReader or the underlying file.
    // If the returned status is NEED_MORE_DATA, then the data that is needed
    // will be indicated by a call to fileReader->RequestBlock.
    static Status Load(std::unique_ptr<FileReader> fileReader, const char* password,
                       bool closeFdOnFailure, std::unique_ptr<Document>* result,
                       int* requestedHeaderSize = nullptr, int* requestedFooterSize = nullptr);

    // Wrap a FPDF_DOCUMENT in this Document, auto-close when this is destroyed.
    Document(ScopedFPDFDocument document, bool is_password_protected)
        : Document(std::move(document), is_password_protected, nullptr, false) {}

    int NumPages() const { return FPDF_GetPageCount(document_.get()); }

    int GetFormType() const { return FPDF_GetFormType(document_.get()); }

    /*
     * Method to obtain a Page of the document.
     *
     * retain - Some operations will require the page be retained in memory.
     * This is relevant to form filling where pages must be held by document in
     * order to receive invalidated rectangles.
     */
    std::shared_ptr<Page> GetPage(int pageNum, bool retain = false);

    // @TODO(b/312222305): This call is only used for analytics, might go away when we
    // implement quicker loading of linearized PDFs.
    bool IsLinearized() const { return is_linearized_; }

    bool IsPasswordProtected() const { return is_password_protected_; }

    // FPDF_DOCUMENT is automatically closed when destroyed.
    virtual ~Document();

    // Clone this document without security into the given file descriptor.
    bool CloneDocumentWithoutSecurity(LinuxFileOps::FDCloser fd);

    // Save this Document to the given file descriptor, presumably opened for
    // write or append. Return true on success.
    bool SaveAs(LinuxFileOps::FDCloser fd);

    // Informs the document that the |rect| of the page bitmap has been
    // invalidated for the given |page|. This takes place following form filling
    // operations. |Rect| must be in page coordinates.
    void NotifyInvalidRect(FPDF_PAGE page, Rectangle_i rect);

    // Removes the page from |pages_| and |fpdf_page_index_lookup_|, if retained,
    // else no-op.
    void ReleaseRetainedPage(int pageNum);

  private:
    // Wrap a FPDF_DOCUMENT in this Document, auto-close when this is destroyed.
    Document(ScopedFPDFDocument document, bool is_password_protected,
             std::unique_ptr<FileReader> file_reader, bool is_linearized)
        : file_reader_(std::move(file_reader)),
          document_(std::move(document)),
          form_filler_(this, document_.get()),
          is_password_protected_(is_password_protected),
          is_linearized_(is_linearized) {}

    // Disable copy constructor because of the cleanup we do in ~Document.
    Document(const Document&);

    // Returns true if the page is available. Always returns true if the document
    // is not being loaded progressively. Should always be called before rendering
    // or accessing the page - see http://b/21314248
    bool IsPageAvailable(int pageNum) const;

    // Clone the document by simply copying the source file to the dest file.
    bool CloneRawFile(int source, int dest);

    // Saves the loaded document back to a file (with security removed).
    bool SaveAsCopyWithoutSecurity(LinuxFileOps::FDCloser dest);

    // If not null, this will also be deleted when this document is destroyed.
    std::unique_ptr<FileReader> file_reader_;

    // document_, form_filler_ and pages_ must be initialized and torn down
    // in this order for required resources to be available
    ScopedFPDFDocument document_;
    FormFiller form_filler_;
    std::unordered_map<int, std::shared_ptr<Page>> pages_;

    // Map relating FPDF_PAGE to Page index for lookup.
    // FPDF_PAGEs are not owned.
    std::unordered_map<void*, int> fpdf_page_index_lookup_;

    // Whether the PDF is password protected.
    bool is_password_protected_ = false;

    // Whether the PDF is linearized.
    bool is_linearized_ = false;
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_DOCUMENT_H_