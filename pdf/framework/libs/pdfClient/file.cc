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

#include "file.h"

#include <errno.h>
#include <stddef.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <utility>

#include "cpp/fpdf_scopers.h"
#include "fpdf_dataavail.h"
#include "fpdf_save.h"
#include "fpdfview.h"
#include "linux_fileops.h"
#include "logging.h"

#define LOG_TAG "file"

namespace pdfClient {

size_t GetFileSize(int fd) {
    off_t end = lseek(fd, 0, SEEK_END);
    return std::max(end, 0L);
}

FileReader::FileReader(LinuxFileOps::FDCloser fd) : fd_(std::move(fd)) {
    complete_size_ = GetFileSize(fd_.get());
    InitImplementation();
}

FileReader::FileReader(LinuxFileOps::FDCloser fd, size_t completeSize)
    : fd_(std::move(fd)), complete_size_(completeSize) {
    InitImplementation();
}

FileReader::FileReader(FileReader&& fr)
    : fpdf_avail_(std::move(fr.fpdf_avail_)),
      fd_(std::move(fr.fd_)),
      complete_size_(fr.complete_size_) {}

FileReader::~FileReader() {}

int FileReader::ReleaseFd() {
    return fd_.Release();
}

bool FileReader::IsComplete() const {
    return GetFileSize(fd_.get()) >= complete_size_;
}

bool FileReader::CanReadBlock(size_t pos, size_t size) const {
    // Return false if pos + size overflows:
    return pos + size >= pos && pos + size <= GetFileSize(fd_.get());
}

size_t FileReader::DoReadBlock(size_t pos, void* buffer, size_t size) const {
    if (!CanReadBlock(pos, size)) {
        return 0;
    }
    if (lseek(fd_.get(), pos, SEEK_SET) == -1) {
        return 0;
    }
    return read(fd_.get(), buffer, size);
}

void FileReader::RequestBlock(size_t offset, size_t size) {
    if (!CanReadBlock(offset, size)) {
        LOGI("pdfClient requests segment: offset=%zu, size=%zu", offset, size);
    }
}

void FileReader::InitImplementation() {
    // Implements FPDF_FILEACCESS:
    FPDF_FILEACCESS::m_FileLen = complete_size_;
    FPDF_FILEACCESS::m_GetBlock = &StaticGetBlockImpl;
    FPDF_FILEACCESS::m_Param = this;

    // Implements FX_FILEAVAIL:
    FX_FILEAVAIL::IsDataAvail = &StaticIsDataAvailImpl;
    FX_FILEAVAIL::version = 1;

    // And create an FPDF_AVAIL:
    fpdf_avail_.reset(FPDFAvail_Create(this, this));

    // Implements FX_DOWNLOADHINTS:
    FX_DOWNLOADHINTS::version = 1;
    FX_DOWNLOADHINTS::AddSegment = &StaticAddSegmentImpl;
}

int FileReader::StaticIsDataAvailImpl(FX_FILEAVAIL* pThis, size_t pos, size_t size) {
    FileReader* fileReader = static_cast<FileReader*>(pThis);
    return fileReader->CanReadBlock(pos, size);
}

int FileReader::StaticGetBlockImpl(void* param, unsigned long pos,  // NOLINT
                                   unsigned char* buffer,
                                   unsigned long size) {  // NOLINT
    FileReader* fileReader = static_cast<FileReader*>(param);
    return fileReader->DoReadBlock(pos, buffer, size);
}

void FileReader::StaticAddSegmentImpl(FX_DOWNLOADHINTS* pThis, size_t pos, size_t size) {
    FileReader* fileReader = static_cast<FileReader*>(pThis);
    fileReader->RequestBlock(pos, size);
}

FileWriter::FileWriter(LinuxFileOps::FDCloser fd) : fd_(std::move(fd)) {
    // Implements FPDF_FILEWRITE:
    version = 1;
    WriteBlock = &StaticWriteBlockImpl;
}

FileWriter::~FileWriter() {
    if (fd_.get() >= 0) {
        fsync(fd_.get());
    }
}

size_t FileWriter::DoWriteBlock(const void* data, size_t size) {
    size_t written = write(fd_.get(), data, size);
    if (written != size) {
        LOGE("Error performing write to fd: %s", strerror(errno));
    }
    return written;
}

int FileWriter::StaticWriteBlockImpl(FPDF_FILEWRITE* pThis, const void* data,
                                     unsigned long size) {  // NOLINT
    FileWriter* file_write = static_cast<FileWriter*>(pThis);
    return file_write->DoWriteBlock(data, size);
}

static void LogAddSegment(FX_DOWNLOADHINTS* pThis, size_t offset, size_t size) {
    LOGI("pdfClient requests segment: offset=%zu, size=%zu", offset, size);
}

FX_DOWNLOADHINTS* LogOnlyDownloadHints() {
    static FX_DOWNLOADHINTS downloadHints;
    downloadHints.version = 1;
    downloadHints.AddSegment = &LogAddSegment;
    return &downloadHints;
}

}  // namespace pdfClient