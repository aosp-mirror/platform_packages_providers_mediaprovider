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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FILE_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FILE_H_

#include <stddef.h>

#include "cpp/fpdf_scopers.h"
#include "fpdf_dataavail.h"
#include "fpdf_save.h"
#include "fpdfview.h"
#include "linux_fileops.h"

namespace pdfClient {

// Returns the actual current size of the given file, by seeking to the end.
// Only works with seekable file descriptors.
size_t GetFileSize(int fd);

// A wrapper on a file-descriptor for reading - implements all the interfaces
// needed to open a PDF as it downloads, using fpdf_dataavail.h
// The data that is available is automatically updated as more data is written
// to the file-descriptor (see CanReadBlock).
class FileReader : public FPDF_FILEACCESS, public FX_FILEAVAIL, public FX_DOWNLOADHINTS {
  public:
    // We implement this interface too, but not by subclassing it.
    ScopedFPDFAvail fpdf_avail_;

    // Start reading a file that has already been completely written.
    explicit FileReader(LinuxFileOps::FDCloser fd);
    // Start reading a file which, when completely written, will be completeSize.
    FileReader(LinuxFileOps::FDCloser fd, size_t completeSize);

    // Move constructor.
    FileReader(FileReader&& fr);

    virtual ~FileReader();

    virtual size_t RequestedHeaderSize() const { return 0; }
    virtual size_t RequestedFooterSize() const { return 0; }

    int Fd() const { return fd_.get(); }
    int ReleaseFd();

    // How big this file will be once it is completely written - only part of this
    // complete size will be available for reading until it is completely written.
    size_t CompleteSize() const { return complete_size_; }

    virtual bool IsComplete() const;
    virtual bool CanReadBlock(size_t offset, size_t size) const;
    virtual size_t DoReadBlock(size_t offset, void* buffer, size_t size) const;
    virtual void RequestBlock(size_t offset, size_t size);

  protected:
    LinuxFileOps::FDCloser fd_;  // File-descriptor.

    // How big the file will be once completely written.
    size_t complete_size_;

    void InitImplementation();

    // Needed to implement FX_FILEAVAIL:
    static int StaticIsDataAvailImpl(struct _FX_FILEAVAIL* pThis, size_t offset, size_t size);

    // Needed to implement FPDF_FILEACCESS:
    static int StaticGetBlockImpl(void* param, unsigned long offset,  // NOLINT
                                  unsigned char* buffer,
                                  unsigned long size);  // NOLINT

    // Needed to implement FX_DOWNLOADHINTS:
    static void StaticAddSegmentImpl(FX_DOWNLOADHINTS* pThis, size_t pos, size_t size);
};

// A wrapper on a filedescriptor for writing - used to save a copy of a PDF
// with password-protection security removed (see document.h).
class FileWriter : public FPDF_FILEWRITE {
  public:
    explicit FileWriter(LinuxFileOps::FDCloser fd);
    ~FileWriter();

    int Fd() { return fd_.get(); }

    size_t DoWriteBlock(const void* data, size_t size);

  private:
    LinuxFileOps::FDCloser fd_;  // File-descriptor.

    // Needed to implement FPDF_FILEWRITE
    static int StaticWriteBlockImpl(FPDF_FILEWRITE* pThis, const void* data,
                                    unsigned long size);  // NOLINT
};

// Returns some DownloadHints that only log each data range that pdfClient requests.
// They do not cause that part of the file to be downloaded.
FX_DOWNLOADHINTS* LogOnlyDownloadHints();

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_FILE_H_