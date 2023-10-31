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

#ifndef MEDIAPROVIDER_PDF_JNI_EXTERNAL_FILE_UTIL_LINUX_FILEOPS_H__
#define MEDIAPROVIDER_PDF_JNI_EXTERNAL_FILE_UTIL_LINUX_FILEOPS_H__

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <functional>
#include <iosfwd>
#include <limits>
#include <memory>
#include <ostream>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

#include "absl/base/attributes.h"
#include "absl/base/macros.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/cord.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/string_view.h"

#ifndef O_NOATIME
#define O_NOATIME 01000000
#endif

namespace pdfClient {
// Whether to resolve symbolic links.
enum class Resolve {
    // Do not resolve symbolic links.
    kNone,

    // Fully resolve symbolic links.
    kFull,
};

// File types returned by GetFileType.  These are compatible with the d_type
// field in struct dirent.
enum class FileType : unsigned char {
    kUnknown = DT_UNKNOWN,
    kPipe = DT_FIFO,
    kCharacterDevice = DT_CHR,
    kDirectory = DT_DIR,
    kBlockDevice = DT_BLK,
    kRegular = DT_REG,
    kSymbolicLink = DT_LNK,
    kSocket = DT_SOCK,
};

std::ostream& operator<<(std::ostream& stream, FileType type);

class LinuxFileOps {
  public:
    // Helper class for scoping closes for file descriptors,
    // like a FileCloser for FD's.
    class FDCloser {
      public:
        explicit FDCloser(int fd = kCanonicalInvalidFd);
        // Not copyable.
        FDCloser(const FDCloser&) = delete;
        FDCloser& operator=(const FDCloser&) = delete;
        // Move-only. Following the move, the moved-from object 'orig' is guaranteed
        // to be in the disengaged state, where get() returns an invalid (< 0) file
        // descriptor.
        FDCloser(FDCloser&& orig);
        FDCloser& operator=(FDCloser&& orig);
        ~FDCloser();

        // Get the file descriptor which the FDCloser is scoping.
        int get() const;
        // Close the file descriptor.
        bool Close();
        absl::Status CloseStatus();

        // Release ownership of the file descriptor and return it.
        int Release();

        // Swap the internal state with another FDCloser.
        void Swap(FDCloser* other);

      private:
        // Constant used to represent the disengaged state. The current logic treats
        // all fd_ values as valid except for -1. However all values < 0 are
        // invalid per POSIX. See
        // http://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap03.html#tag_03_166
        static constexpr int kCanonicalInvalidFd = -1;

        int fd_;
    };

    static bool CloseFD(int fd);
    static absl::Status CloseFDStatus(int fd) {
        if (!CloseFD(fd)) {
            return CanonicalError(FormatSyscallError("close", absl::StrCat(fd)));
        }
        return absl::OkStatus();
    }

    static std::string FormatSyscallError(absl::string_view syscall, absl::string_view filename);

    static std::string FormatSyscallError(absl::string_view syscall, absl::string_view filename,
                                          int error);

    static absl::Status CanonicalError(absl::string_view message);
};

}  // namespace pdfClient

#endif  // MEDIAPROVIDER_PDF_JNI_EXTERNAL_FILE_UTIL_LINUX_FILEOPS_H__