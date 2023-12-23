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

#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <iosfwd>
#include <limits>
#include <memory>
#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include "absl/base/internal/strerror.h"
#include "absl/base/macros.h"
#include "absl/flags/flag.h"
#include "absl/functional/bind_front.h"
#include "absl/log/check.h"
#include "absl/status/status.h"
#include "absl/strings/cord.h"
#include "absl/strings/match.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_split.h"

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

absl::Status LinuxFileOps::FDCloser::CloseStatus() {
    int fd = this->Release();
    //  if (fd == kCanonicalInvalidFd) {
    //    return ::util::FailedPreconditionErrorBuilder()
    //           << "file descriptor has been released";
    //  }
    return LinuxFileOps::CloseFDStatus(fd);
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

std::string LinuxFileOps::FormatSyscallError(std::string_view syscall, std::string_view filename) {
    return FormatSyscallError(syscall, filename, errno);
}

// Format a system call error as name(file): error
std::string LinuxFileOps::FormatSyscallError(std::string_view syscall, std::string_view filename,
                                             int error) {
    return absl::StrCat(syscall, "(", filename, "): ", absl::base_internal::StrError(error));
}

absl::Status LinuxFileOps::CanonicalError(std::string_view message) {
    return errno == 0 ? absl::UnknownError(message) : absl::ErrnoToStatus(errno, message);
}

}  // namespace pdfClient