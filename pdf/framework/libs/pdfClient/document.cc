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

#include <stdio.h>
#include <unistd.h>

#include <memory>
#include <utility>

#include "cpp/fpdf_scopers.h"
#include "file.h"
#include "fpdf_dataavail.h"
#include "fpdf_save.h"
#include "fpdfview.h"
#include "linux_fileops.h"
#include "logging.h"
#include "page.h"
#include "rect.h"

#define LOG_TAG "document"

extern int GetLastError();

namespace pdfClient {

void InitLibrary() {
    FPDF_InitLibrary();
}

Status Document::Load(std::unique_ptr<FileReader> fileReader, const char* password,
                      bool closeFdOnFailure, std::unique_ptr<Document>* result,
                      int* requestedHeaderSize, int* requestedFooterSize) {
    *result = nullptr;
    if (!fileReader->IsComplete() && FPDFAvail_IsDocAvail(fileReader->fpdf_avail_.get(),
                                                          fileReader.get()) == PDF_DATA_NOTAVAIL) {
        if (!closeFdOnFailure) {
            fileReader->ReleaseFd();
        }
        if (requestedHeaderSize) {
            *requestedHeaderSize = fileReader->RequestedHeaderSize();
        }
        if (requestedFooterSize) {
            *requestedFooterSize = fileReader->RequestedFooterSize();
        }
        return NEED_MORE_DATA;
    }

    ScopedFPDFDocument fpdf_doc;

    bool is_linearized = false;
    if ((is_linearized = FPDFAvail_IsLinearized(fileReader->fpdf_avail_.get())) == PDF_LINEARIZED) {
        fpdf_doc.reset(FPDFAvail_GetDocument(fileReader->fpdf_avail_.get(), password));
    } else {
        fpdf_doc.reset(FPDF_LoadCustomDocument(fileReader.get(), password));
    }
    FPDF_BOOL should_scale_for_print = FPDF_VIEWERREF_GetPrintScaling(fpdf_doc.get());
    if (fpdf_doc) {
        // Use WrapUnique instead of MakeUnique since this Document constructor is
        // private.
        *result = std::unique_ptr<Document>(
                new Document(std::move(fpdf_doc), (password && password[0] != '\0'),
                             std::move(fileReader), is_linearized, should_scale_for_print));
        return LOADED;
    }

    if (!closeFdOnFailure) {
        fileReader->ReleaseFd();
    }

    if (requestedHeaderSize) {
        *requestedHeaderSize = fileReader->RequestedHeaderSize();
    }
    if (requestedFooterSize) {
        *requestedFooterSize = fileReader->RequestedFooterSize();
    }

    // Error - failed to load document.
    int error = FPDF_GetLastError();
    if (error == FPDF_ERR_PASSWORD) {
        return REQUIRES_PASSWORD;
    } else {
        LOGE("Parse Document failed (err=%d).\n", error);
        return PDF_ERROR;
    }
}

Document::~Document() {
    // Allow pages to do any internal cleanup before deletion.
    for (const auto& entry : pages_) {
        entry.second->TerminateFormFilling();
    }
}

bool Document::SaveAs(LinuxFileOps::FDCloser fd) {
    FileWriter fw(std::move(fd));
    constexpr int flags = 0;
    if (!FPDF_SaveAsCopy(document_.get(), &fw, flags)) {
        LOGW("Failed to save-as to fd %d.", fw.Fd());
        return false;
    }
    size_t destSize = lseek(fw.Fd(), 0, SEEK_END);
    LOGV("Save-as to fd %d [%zd bytes], flags=%d.", fw.Fd(), destSize, flags);
    return true;
}

std::shared_ptr<Page> Document::GetPage(int pageNum, bool retain) {
    if (pages_.find(pageNum) != pages_.end()) {
        return pages_.at(pageNum);
    }

    IsPageAvailable(pageNum);
    auto page = std::make_shared<Page>(document_.get(), pageNum, &form_filler_);

    if (retain) {
        page->InitializeFormFilling();
        pages_.try_emplace(pageNum, page);
        fpdf_page_index_lookup_.try_emplace(page->page(), pageNum);
    }

    return page;
}

void Document::NotifyInvalidRect(FPDF_PAGE page, Rectangle_i rect) {
    // invalid rects are only relevant to pages that are being retained
    // since pages save them until a caller asks for them
    if (fpdf_page_index_lookup_.find(page) != fpdf_page_index_lookup_.end()) {
        int retained_page_index = fpdf_page_index_lookup_.at(page);
        pages_.at(retained_page_index)->NotifyInvalidRect(rect);
    }
}

void Document::ReleaseRetainedPage(int pageNum) {
    if (pages_.find(pageNum) != pages_.end()) {
        std::shared_ptr<pdfClient::Page> page = pages_.at(pageNum);
        page->TerminateFormFilling();
        pages_.erase(pageNum);
        fpdf_page_index_lookup_.erase(page->page());
    }
}

bool Document::IsPageAvailable(int pageNum) const {
    // This call should be made before attempting to render or otherwise access
    // the given page, even if the results are ignored
    if (file_reader_) {
        return FPDFAvail_IsPageAvail(file_reader_->fpdf_avail_.get(), pageNum,
                                     file_reader_.get()) != 0;
    }
    return true;
}

bool Document::CloneDocumentWithoutSecurity(LinuxFileOps::FDCloser fd) {
    if (file_reader_ && !IsPasswordProtected()) {
        // Document has no security - just clone the raw file.
        return CloneRawFile(file_reader_->Fd(), fd.Release());
    } else {
        // Document has security or we couldn't just copy the file. Use SaveAsCopy:
        return SaveAsCopyWithoutSecurity(std::move(fd));
    }
}

bool Document::CloneRawFile(int source, int dest) {
    lseek(source, 0, SEEK_SET);
    char buf[4096];
    size_t bytesRead;
    while ((bytesRead = read(source, buf, 4096)) > 0) {
        write(dest, buf, bytesRead);
    }

    size_t sourceSize = lseek(source, 0, SEEK_END);
    size_t destSize = lseek(dest, 0, SEEK_END);
    bool success = (destSize == sourceSize);
    if (success) {
        LOGV("Copied raw file to fd %d [%zd bytes].", dest, destSize);
    } else {
        LOGV("Failed to copy raw file to fd %d (wrote %zd out of %zd).",
                dest, destSize, sourceSize);
    }
    // We own the FD and have to make sure to close it.
    LinuxFileOps::CloseFD(dest);
    return success;
}

bool Document::SaveAsCopyWithoutSecurity(LinuxFileOps::FDCloser dest) {
    FileWriter fw(std::move(dest));
    int flags = IsPasswordProtected() ? FPDF_REMOVE_SECURITY : 0;
    bool success = FPDF_SaveAsCopy(document_.get(), &fw, flags);

    size_t destSize = lseek(fw.Fd(), 0, SEEK_END);
    if (success) {
        LOGV("Save-as to fd %d [%zd bytes], flags=%d.", fw.Fd(), destSize, flags);
    } else {
        LOGV("Failed to save-as to fd %d, flags=%d.", fw.Fd(), flags);
    }
    // No need to close the FD as lower level code already does that.
    return success;
}

}  // namespace pdfClient