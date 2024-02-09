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

#include "linux_fileops.h"

#include <unistd.h>

namespace pdfClient {

namespace {

// The maximum number of symlinks to try to dereference before giving up.
const int kMaxSymlinkDereferences = 1000;
#ifdef O_LARGEFILE
constexpr int kOLargefileIfAvailable = O_LARGEFILE;
#else
constexpr int kOLargefileIfAvailable = 0;
#endif

}  // namespace

LinuxFileOps::FDCloser::FDCloser(int fd) : fd_(fd) {}

LinuxFileOps::FDCloser::FDCloser(LinuxFileOps::FDCloser&& orig) : fd_(orig.Release()) {}

LinuxFileOps::FDCloser& LinuxFileOps::FDCloser::operator=(LinuxFileOps::FDCloser&& orig) {
    if (this != &orig) {
        Close();
        fd_ = orig.Release();
    }
    return *this;
}

LinuxFileOps::FDCloser::~FDCloser() {
    Close();
}

int LinuxFileOps::FDCloser::get() const {
    return fd_;
}

bool LinuxFileOps::FDCloser::Close() {
    int fd = Release();
    if (fd == kCanonicalInvalidFd) {
        return false;
    }
    return LinuxFileOps::CloseFD(fd);
}

int LinuxFileOps::FDCloser::Release() {
    int ret = fd_;
    fd_ = kCanonicalInvalidFd;
    return ret;
}

void LinuxFileOps::FDCloser::Swap(FDCloser* other) {
    std::swap(fd_, other->fd_);
}

bool LinuxFileOps::CloseFD(int fd) {
    int ret = ::close(fd);
    if (ret != 0) {
        if (errno == EINTR) {
            // Calling close again on EINTR is a bad thing. See
            // http://lkml.org/lkml/2005/9/11/49 for more details.
            return true;  // COV_NF_LINE
        } else {
            return false;
        }
    }

    return true;
}

}  // namespace pdfClient