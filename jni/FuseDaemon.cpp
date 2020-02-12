// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define ATRACE_TAG ATRACE_TAG_APP
#define LOG_TAG "FuseDaemon"
#define LIBFUSE_LOG_TAG "libfuse"

#include "FuseDaemon.h"

#include <android-base/logging.h>
#include <android/log.h>
#include <android/trace.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <fuse_i.h>
#include <fuse_log.h>
#include <fuse_lowlevel.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/fuse.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/param.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#include <iostream>
#include <list>
#include <map>
#include <mutex>
#include <queue>
#include <regex>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "MediaProviderWrapper.h"
#include "libfuse_jni/ReaddirHelper.h"
#include "libfuse_jni/RedactionInfo.h"
#include "node-inl.h"

using mediaprovider::fuse::DirectoryEntry;
using mediaprovider::fuse::dirhandle;
using mediaprovider::fuse::handle;
using mediaprovider::fuse::node;
using mediaprovider::fuse::RedactionInfo;
using std::list;
using std::string;
using std::vector;

// logging macros to avoid duplication.
#define TRACE LOG(DEBUG)
#define TRACE_VERBOSE LOG(VERBOSE)
#define TRACE_FUSE(__fuse) TRACE << "[" << __fuse->path << "] "
#define TRACE_FUSE_VERBOSE(__fuse) TRACE_VERBOSE << "[" << __fuse->path << "] "

#define ATRACE_NAME(name) ScopedTrace ___tracer(name)
#define ATRACE_CALL() ATRACE_NAME(__FUNCTION__)

class ScopedTrace {
  public:
    explicit inline ScopedTrace(const char *name) {
      ATrace_beginSection(name);
    }

    inline ~ScopedTrace() {
      ATrace_endSection();
    }
};

#define FUSE_UNKNOWN_INO 0xffffffff

constexpr size_t MAX_READ_SIZE = 128 * 1024;
// Stolen from: UserHandle#getUserId
constexpr int PER_USER_RANGE = 100000;
// Cache inode attributes for a 'short' time so that performance is decent and last modified time
// stamps are not too stale
constexpr double DEFAULT_ATTR_TIMEOUT_SECONDS = 10;
// Ensure the VFS does not cache dentries, if it caches, the following scenario could occur:
// 1. Process A has access to file A and does a lookup
// 2. Process B does not have access to file A and does a lookup
// (2) will succeed because the lookup request will not be sent from kernel to the FUSE daemon
// and the kernel will respond from cache. Even if this by itself is not a security risk
// because subsequent FUSE requests will fail if B does not have access to the resource.
// It does cause indeterministic behavior because whether (2) succeeds or not depends on if
// (1) occurred.
// We prevent this caching by setting the entry_timeout value to 0.
constexpr double DEFAULT_ENTRY_TIMEOUT_SECONDS = 0;

/*
 * In order to avoid double caching with fuse, call fadvise on the file handles
 * in the underlying file system. However, if this is done on every read/write,
 * the fadvises cause a very significant slowdown in tests (specifically fio
 * seq_write). So call fadvise on the file handles with the most reads/writes
 * only after a threshold is passed.
 */
class FAdviser {
  public:
    FAdviser() : thread_(MessageLoop, this), total_size_(0) {}

    ~FAdviser() {
        SendMessage(Message::quit);
        thread_.join();
    }

    void Record(int fd, size_t size) { SendMessage(Message::record, fd, size); }

    void Close(int fd) { SendMessage(Message::close, fd); }

  private:
    std::thread thread_;

    struct Message {
        enum Type { record, close, quit };
        Type type;
        int fd;
        size_t size;
    };

    void RecordImpl(int fd, size_t size) {
        total_size_ += size;

        // Find or create record in files_
        // Remove record from sizes_ if it exists, adjusting size appropriately
        auto file = files_.find(fd);
        if (file != files_.end()) {
            auto old_size = file->second;
            size += old_size->first;
            sizes_.erase(old_size);
        } else {
            file = files_.insert(Files::value_type(fd, sizes_.end())).first;
        }

        // Now (re) insert record in sizes_
        auto new_size = sizes_.insert(Sizes::value_type(size, fd));
        file->second = new_size;

        if (total_size_ < threshold_) return;

        LOG(INFO) << "Threshold exceeded - fadvising " << total_size_;
        while (!sizes_.empty() && total_size_ > target_) {
            auto size = --sizes_.end();
            total_size_ -= size->first;
            posix_fadvise(size->second, 0, 0, POSIX_FADV_DONTNEED);
            files_.erase(size->second);
            sizes_.erase(size);
        }
        LOG(INFO) << "Threshold now " << total_size_;
    }

    void CloseImpl(int fd) {
        auto file = files_.find(fd);
        if (file == files_.end()) return;

        total_size_ -= file->second->first;
        sizes_.erase(file->second);
        files_.erase(file);
    }

    void MessageLoopImpl() {
        while (1) {
            Message message;

            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return !queue_.empty(); });
                message = queue_.front();
                queue_.pop();
            }

            switch (message.type) {
                case Message::record:
                    RecordImpl(message.fd, message.size);
                    break;

                case Message::close:
                    CloseImpl(message.fd);
                    break;

                case Message::quit:
                    return;
            }
        }
    }

    static int MessageLoop(FAdviser* ptr) {
        ptr->MessageLoopImpl();
        return 0;
    }

    void SendMessage(Message::Type type, int fd = -1, size_t size = 0) {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            Message message = {type, fd, size};
            queue_.push(message);
        }
        cv_.notify_one();
    }

    std::queue<Message> queue_;
    std::mutex mutex_;
    std::condition_variable cv_;

    typedef std::multimap<size_t, int> Sizes;
    typedef std::map<int, Sizes::iterator> Files;

    Files files_;
    Sizes sizes_;
    size_t total_size_;

    const size_t threshold_ = 64 * 1024 * 1024;
    const size_t target_ = 32 * 1024 * 1024;
};

// Whether inode tracking is enabled or not. When enabled, we maintain a
// separate mapping from inode numbers to "live" nodes so we can detect when
// we receive a request to a node that has been deleted.
static constexpr bool kEnableInodeTracking = true;

/* Single FUSE mount */
struct fuse {
    explicit fuse(const std::string& _path)
        : path(_path), root(node::CreateRoot(_path, &lock)), mp(0), zero_addr(0) {}

    inline bool IsRoot(const node* node) const { return node == root; }

    // Note that these two (FromInode / ToInode) conversion wrappers are required
    // because fuse_lowlevel_ops documents that the root inode is always one
    // (see FUSE_ROOT_ID in fuse_lowlevel.h). There are no particular requirements
    // on any of the other inodes in the FS.
    inline node* FromInode(__u64 inode) {
        if (inode == FUSE_ROOT_ID) {
            return root;
        }

        node* node = node::FromInode(inode);

        if (kEnableInodeTracking) {
            std::lock_guard<std::recursive_mutex> guard(lock);
            CHECK(inode_tracker_.find(node) != inode_tracker_.end());
        }

        return node;
    }

    inline __u64 ToInode(node* node) const {
        if (IsRoot(node)) {
            return FUSE_ROOT_ID;
        }

        return node::ToInode(node);
    }

    // Notify this FUSE instance that one of its nodes has been deleted.
    void NodeDeleted(const node* node) {
        if (kEnableInodeTracking) {
            LOG(INFO) << "Node: " << node << " deleted.";

            std::lock_guard<std::recursive_mutex> guard(lock);
            inode_tracker_.erase(node);
        }
    }

    // Notify this FUSE instance that a new nodes has been created.
    void NodeCreated(const node* node) {
        if (kEnableInodeTracking) {
            LOG(INFO) << "Node: " << node << " created.";

            std::lock_guard<std::recursive_mutex> guard(lock);
            inode_tracker_.insert(node);
        }
    }

    std::recursive_mutex lock;
    const string path;
    node* const root;

    /*
     * Used to make JNI calls to MediaProvider.
     * Responsibility of freeing this object falls on corresponding
     * FuseDaemon object.
     */
    mediaprovider::fuse::MediaProviderWrapper* mp;

    /*
     * Points to a range of zeroized bytes, used by pf_read to represent redacted ranges.
     * The memory is read only and should never be modified.
     */
    /* const */ char* zero_addr;

    FAdviser fadviser;

    std::unordered_set<const node*> inode_tracker_;
};

static inline const char* safe_name(node* n) {
    return n ? n->GetName().c_str() : "?";
}

static inline __u64 ptr_to_id(void* ptr) {
    return (__u64)(uintptr_t) ptr;
}

/*
 * Set an F_RDLCK or F_WRLCKK on fd with fcntl(2).
 *
 * This is called before the MediaProvider returns fd from the lower file
 * system to an app over the ContentResolver interface. This allows us
 * check with is_file_locked if any reference to that fd is still open.
 */
static int set_file_lock(int fd, bool for_read, const std::string& path) {
    std::string lock_str = (for_read ? "read" : "write");
    TRACE_VERBOSE << "Setting " << lock_str << " lock for path " << path;

    struct flock fl{};
    fl.l_type = for_read ? F_RDLCK : F_WRLCK;
    fl.l_whence = SEEK_SET;

    int res = fcntl(fd, F_OFD_SETLK, &fl);
    if (res) {
        PLOG(ERROR) << "Failed to set " << lock_str << " lock on path " << path;
        return res;
    }
    TRACE_VERBOSE << "Successfully set " << lock_str << " lock on path " << path;
    return res;
}

/*
 * Check if an F_RDLCK or F_WRLCK is set on fd with fcntl(2).
 *
 * This is used to determine if the MediaProvider has given an fd to the lower fs to an app over
 * the ContentResolver interface. Before that happens, we always call set_file_lock on the file
 * allowing us to know if any reference to that fd is still open here.
 *
 * Returns true if fd may have a lock, false otherwise
 */
static bool is_file_locked(int fd, const std::string& path) {
    TRACE_VERBOSE << "Checking if file is locked " << path;

    struct flock fl{};
    fl.l_type = F_WRLCK;
    fl.l_whence = SEEK_SET;

    int res = fcntl(fd, F_OFD_GETLK, &fl);
    if (res) {
        PLOG(ERROR) << "Failed to check lock for file " << path;
        // Assume worst
        return true;
    }
    bool locked = fl.l_type != F_UNLCK;
    TRACE_VERBOSE << "File " << path << " is " << (locked ? "locked" : "unlocked");
    return locked;
}

static struct fuse* get_fuse(fuse_req_t req) {
    return reinterpret_cast<struct fuse*>(fuse_req_userdata(req));
}

static bool is_android_path(const string& path, const string& fuse_path, uid_t uid) {
    int user_id = uid / PER_USER_RANGE;
    const std::string android_path = fuse_path + "/" + std::to_string(user_id) + "/Android";
    return path.rfind(android_path, 0) == 0;
}

static double get_attr_timeout(const string& path, uid_t uid, struct fuse* fuse, node* parent) {
    if (fuse->IsRoot(parent) || is_android_path(path, fuse->path, uid)) {
        // The /0 and /0/Android attrs can be always cached, as they never change
        return DBL_MAX;
    } else {
        return DEFAULT_ATTR_TIMEOUT_SECONDS;
    }
}

static double get_entry_timeout(const string& path, uid_t uid, struct fuse* fuse, node* parent) {
    if (fuse->IsRoot(parent) || is_android_path(path, fuse->path, uid)) {
        // The /0 and /0/Android dentries can be always cached, as they are visible to all apps
        return DBL_MAX;
    } else {
        return DEFAULT_ENTRY_TIMEOUT_SECONDS;
    }
}

static node* make_node_entry(fuse_req_t req, node* parent, const string& name, const string& path,
                             struct fuse_entry_param* e, int* error_code) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* node;

    memset(e, 0, sizeof(*e));
    if (lstat(path.c_str(), &e->attr) < 0) {
        *error_code = errno;
        return NULL;
    }

    node = parent->LookupChildByName(name, true /* acquire */);
    if (!node) {
        node = ::node::Create(parent, name, &fuse->lock);
        fuse->NodeCreated(node);
    }

    // This FS is not being exported via NFS so just a fixed generation number
    // for now. If we do need this, we need to increment the generation ID each
    // time the fuse daemon restarts because that's what it takes for us to
    // reuse inode numbers.
    e->generation = 0;
    e->ino = fuse->ToInode(node);
    e->entry_timeout = get_entry_timeout(path, ctx->uid, fuse, parent);
    e->attr_timeout = get_attr_timeout(path, ctx->uid, fuse, parent);

    return node;
}

static inline bool is_requesting_write(int flags) {
    return flags & (O_WRONLY | O_RDWR);
}

namespace mediaprovider {
namespace fuse {

/**
 * Function implementations
 *
 * These implement the various functions in fuse_lowlevel_ops
 *
 */

static void pf_init(void* userdata, struct fuse_conn_info* conn) {
    // We don't want a getattr request with every read request
    conn->want &= ~FUSE_CAP_AUTO_INVAL_DATA;
    unsigned mask = (FUSE_CAP_SPLICE_WRITE | FUSE_CAP_SPLICE_MOVE | FUSE_CAP_SPLICE_READ |
                     FUSE_CAP_ASYNC_READ | FUSE_CAP_ATOMIC_O_TRUNC | FUSE_CAP_WRITEBACK_CACHE |
                     FUSE_CAP_EXPORT_SUPPORT | FUSE_CAP_FLOCK_LOCKS);
    conn->want |= conn->capable & mask;
    conn->max_read = MAX_READ_SIZE;
}

static void pf_destroy(void* userdata) {
    struct fuse* fuse = reinterpret_cast<struct fuse*>(userdata);
    LOG(INFO) << "DESTROY " << fuse->path;

    node::DeleteTree(fuse->root);
}

static std::regex storage_emulated_regex("^\\/storage\\/emulated\\/([0-9]+)");
static node* do_lookup(fuse_req_t req, fuse_ino_t parent, const char* name,
                       struct fuse_entry_param* e, int* error_code) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    string parent_path = parent_node->BuildPath();

    TRACE_FUSE_VERBOSE(fuse) << "LOOKUP " << name << " @ " << parent << " ("
                             << safe_name(parent_node) << ")";

    string child_path = parent_path + "/" + name;

    std::smatch match;
    std::regex_search(child_path, match, storage_emulated_regex);
    if (match.size() == 2 && std::to_string(getuid() / PER_USER_RANGE) != match[1].str()) {
        // Ensure the FuseDaemon user id matches the user id in requested path
        *error_code = EPERM;
        return nullptr;
    }
    return make_node_entry(req, parent_node, name, child_path, e, error_code);
}

static void pf_lookup(fuse_req_t req, fuse_ino_t parent, const char* name) {
    ATRACE_CALL();
    struct fuse_entry_param e;

    int error_code = 0;
    if (do_lookup(req, parent, name, &e, &error_code)) {
        fuse_reply_entry(req, &e);
    } else {
        CHECK(error_code != 0);
        fuse_reply_err(req, error_code);
    }
}

static void do_forget(struct fuse* fuse, fuse_ino_t ino, uint64_t nlookup) {
    node* node = fuse->FromInode(ino);
    TRACE_FUSE(fuse) << "FORGET #" << nlookup << " @ " << ino << " (" << safe_name(node) << ")";
    if (node) {
        // This is a narrowing conversion from an unsigned 64bit to a 32bit value. For
        // some reason we only keep 32 bit refcounts but the kernel issues
        // forget requests with a 64 bit counter.
        if (node->Release(static_cast<uint32_t>(nlookup))) {
            fuse->NodeDeleted(node);
        }
    }
}

static void pf_forget(fuse_req_t req, fuse_ino_t ino, uint64_t nlookup) {
    ATRACE_CALL();
    node* node;
    struct fuse* fuse = get_fuse(req);

    do_forget(fuse, ino, nlookup);
    fuse_reply_none(req);
}

static void pf_forget_multi(fuse_req_t req,
                            size_t count,
                            struct fuse_forget_data* forgets) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);

    for (int i = 0; i < count; i++) {
        do_forget(fuse, forgets[i].ino, forgets[i].nlookup);
    }
    fuse_reply_none(req);
}

static void pf_getattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* node = fuse->FromInode(ino);
    string path = node->BuildPath();
    TRACE_FUSE(fuse) << "GETATTR @ " << ino << " (" << safe_name(node) << ")";

    if (!node) fuse_reply_err(req, ENOENT);

    struct stat s;
    memset(&s, 0, sizeof(s));
    if (lstat(path.c_str(), &s) < 0) {
        fuse_reply_err(req, errno);
    } else {
        fuse_reply_attr(req, &s, get_attr_timeout(path, ctx->uid, fuse, nullptr));
    }
}

static void pf_setattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct stat* attr,
                       int to_set,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* node = fuse->FromInode(ino);
    string path = node->BuildPath();
    struct timespec times[2];

    TRACE_FUSE(fuse) << "SETATTR valid=" << to_set << " @ " << ino << "(" << safe_name(node) << ")";

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    /* XXX: incomplete implementation on purpose.
     * chmod/chown should NEVER be implemented.*/

    if ((to_set & FUSE_SET_ATTR_SIZE)
            && truncate64(path.c_str(), attr->st_size) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    /* Handle changing atime and mtime.  If FATTR_ATIME_and FATTR_ATIME_NOW
     * are both set, then set it to the current time.  Else, set it to the
     * time specified in the request.  Same goes for mtime.  Use utimensat(2)
     * as it allows ATIME and MTIME to be changed independently, and has
     * nanosecond resolution which fuse also has.
     */
    if (to_set & (FATTR_ATIME | FATTR_MTIME)) {
        times[0].tv_nsec = UTIME_OMIT;
        times[1].tv_nsec = UTIME_OMIT;
        if (to_set & FATTR_ATIME) {
            if (to_set & FATTR_ATIME_NOW) {
                times[0].tv_nsec = UTIME_NOW;
            } else {
                times[0].tv_sec = attr->st_atime;
                // times[0].tv_nsec = attr->st_atime.tv_nsec;
            }
        }
        if (to_set & FATTR_MTIME) {
            if (to_set & FATTR_MTIME_NOW) {
                times[1].tv_nsec = UTIME_NOW;
            } else {
                times[1].tv_sec = attr->st_mtime;
                // times[1].tv_nsec = attr->st_mtime.tv_nsec;
            }
        }
        TRACE_FUSE(fuse) << "Calling utimensat on " << path << " with atime " << times[0].tv_sec
                         << " mtime=" << times[1].tv_sec;
        if (utimensat(-1, path.c_str(), times, 0) < 0) {
            fuse_reply_err(req, errno);
            return;
        }
    }

    lstat(path.c_str(), attr);
    fuse_reply_attr(req, attr, get_attr_timeout(path, ctx->uid, fuse, nullptr));
}

static void pf_canonical_path(fuse_req_t req, fuse_ino_t ino)
{
    node* node = get_fuse(req)->FromInode(ino);

    if (node) {
        // TODO(b/147482155): Check that uid has access to |path| and its contents
        fuse_reply_canonical_path(req, node->BuildPath().c_str());
        return;
    }
    fuse_reply_err(req, ENOENT);
}

static void pf_mknod(fuse_req_t req,
                     fuse_ino_t parent,
                     const char* name,
                     mode_t mode,
                     dev_t rdev) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    string parent_path = parent_node->BuildPath();

    TRACE_FUSE(fuse) << "MKNOD " << name << " 0" << std::oct << mode << " @ " << parent << " ("
                     << safe_name(parent_node) << ")";

    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const string child_path = parent_path + "/" + name;

    mode = (mode & (~0777)) | 0664;
    if (mknod(child_path.c_str(), mode, rdev) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    int error_code = 0;
    struct fuse_entry_param e;
    if (make_node_entry(req, parent_node, name, child_path, &e, &error_code)) {
        fuse_reply_entry(req, &e);
    } else {
        CHECK(error_code != 0);
        fuse_reply_err(req, error_code);
    }
}

static void pf_mkdir(fuse_req_t req,
                     fuse_ino_t parent,
                     const char* name,
                     mode_t mode) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    const string parent_path = parent_node->BuildPath();

    TRACE_FUSE(fuse) << "MKDIR " << name << " 0" << std::oct << mode << " @ " << parent << " ("
                     << safe_name(parent_node) << ")";

    const string child_path = parent_path + "/" + name;

    int status = fuse->mp->IsCreatingDirAllowed(child_path, ctx->uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    mode = (mode & (~0777)) | 0775;
    if (mkdir(child_path.c_str(), mode) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    int error_code = 0;
    struct fuse_entry_param e;
    if (make_node_entry(req, parent_node, name, child_path, &e, &error_code)) {
        fuse_reply_entry(req, &e);
    } else {
        CHECK(error_code != 0);
        fuse_reply_err(req, error_code);
    }
}

static void pf_unlink(fuse_req_t req, fuse_ino_t parent, const char* name) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    const string parent_path = parent_node->BuildPath();

    TRACE_FUSE(fuse) << "UNLINK " << name << " @ " << parent << "(" << safe_name(parent_node) << ")";

    const string child_path = parent_path + "/" + name;

    int status = fuse->mp->DeleteFile(child_path, ctx->uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    node* child_node = parent_node->LookupChildByName(name, false /* acquire */);
    if (child_node) {
        child_node->SetDeleted();
    }

    fuse_reply_err(req, 0);
}

static void pf_rmdir(fuse_req_t req, fuse_ino_t parent, const char* name) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    const string parent_path = parent_node->BuildPath();

    TRACE_FUSE(fuse) << "RMDIR " << name << " @ " << parent << "(" << safe_name(parent_node) << ")";

    const string child_path = parent_path + "/" + name;

    int status = fuse->mp->IsDeletingDirAllowed(child_path, ctx->uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    if (rmdir(child_path.c_str()) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    node* child_node = parent_node->LookupChildByName(name, false /* acquire */);
    if (child_node) {
        child_node->SetDeleted();
    }

    fuse_reply_err(req, 0);
}
/*
static void pf_symlink(fuse_req_t req, const char* link, fuse_ino_t parent,
                         const char* name)
{
    cout << "TODO:" << __func__;
}
*/
static int do_rename(fuse_req_t req, fuse_ino_t parent, const char* name, fuse_ino_t new_parent,
                     const char* new_name, unsigned int flags) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);

    if (flags != 0) {
        LOG(ERROR) << "One or more rename flags not supported";
        return EINVAL;
    }

    node* old_parent_node = fuse->FromInode(parent);
    const string old_parent_path = old_parent_node->BuildPath();
    node* new_parent_node = fuse->FromInode(new_parent);
    const string new_parent_path = new_parent_node->BuildPath();

    if (!old_parent_node || !new_parent_node) {
        return ENOENT;
    } else if (parent == new_parent && name == new_name) {
        // No rename required.
        return 0;
    }

    TRACE_FUSE(fuse) << "RENAME " << name << " -> " << new_name << " @ " << parent << " ("
                     << safe_name(old_parent_node) << ") -> " << new_parent << " ("
                     << safe_name(new_parent_node) << ")";

    node* child_node = old_parent_node->LookupChildByName(name, true /* acquire */);

    const string old_child_path = child_node->BuildPath();
    const string new_child_path = new_parent_path + "/" + new_name;

    // TODO(b/147408834): Check ENOTEMPTY & EEXIST error conditions before JNI call.
    const int res = fuse->mp->Rename(old_child_path, new_child_path, req->ctx.uid);
    // TODO(b/145663158): Lookups can go out of sync if file/directory is actually moved but
    // EFAULT/EIO is reported due to JNI exception.
    if (res == 0) {
        child_node->Rename(new_name, new_parent_node);
    }

    child_node->Release(1);
    return res;
}

static void pf_rename(fuse_req_t req, fuse_ino_t parent, const char* name, fuse_ino_t new_parent,
                      const char* new_name, unsigned int flags) {
    int res = do_rename(req, parent, name, new_parent, new_name, flags);
    fuse_reply_err(req, res);
}

/*
static void pf_link(fuse_req_t req, fuse_ino_t ino, fuse_ino_t new_parent,
                      const char* new_name)
{
    cout << "TODO:" << __func__;
}
*/

static void pf_open(fuse_req_t req, fuse_ino_t ino, struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* node = fuse->FromInode(ino);
    const string path = node->BuildPath();

    TRACE_FUSE(fuse) << "OPEN 0" << std::oct << fi->flags << " @ " << ino << " (" << safe_name(node)
                     << ")";

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    if (fi->flags & O_DIRECT) {
        fi->flags &= ~O_DIRECT;
        fi->direct_io = true;
    }

    TRACE_FUSE(fuse) << "OPEN " << path;
    int status = fuse->mp->IsOpenAllowed(path, ctx->uid, is_requesting_write(fi->flags));
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    const int fd = open(path.c_str(), fi->flags);
    if (fd < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    // We don't redact if the caller was granted write permission for this file
    std::unique_ptr<RedactionInfo> ri;
    if (is_requesting_write(fi->flags)) {
        ri = std::make_unique<RedactionInfo>();
    } else {
        ri = fuse->mp->GetRedactionInfo(path, req->ctx.uid);
    }

    if (!ri) {
        close(fd);
        fuse_reply_err(req, EFAULT);
        return;
    }

    if (ri->isRedactionNeeded() || is_file_locked(fd, path)) {
        // We don't want to use the FUSE VFS cache in two cases:
        // 1. When redaction is needed because app A with EXIF access might access
        // a region that should have been redacted for app B without EXIF access, but app B on
        // a subsequent read, will be able to see the EXIF data because the read request for that
        // region will be served from cache and not get to the FUSE daemon
        // 2. When the file has a read or write lock on it. This means that the MediaProvider has
        // given an fd to the lower file system to an app. There are two cases where using the cache
        // in this case can be a problem:
        // a. Writing to a FUSE fd with caching enabled will use the write-back cache and a
        // subsequent read from the lower fs fd will not see the write.
        // b. Reading from a FUSE fd with caching enabled may not see the latest writes using the
        // lower fs fd because those writes did not go through the FUSE layer and reads from FUSE
        // after that write may be served from cache
        if (ri->isRedactionNeeded()) {
            TRACE_FUSE(fuse) << "Using direct io for " << path << " because redaction is needed.";
        } else {
            TRACE_FUSE(fuse) << "Using direct io for " << path << " because the file is locked.";
        }
        fi->direct_io = true;
    } else {
        TRACE_FUSE(fuse) << "Using cache for " << path;
    }

    handle* h = new handle(path, fd, ri.release(), !fi->direct_io);
    node->AddHandle(h);

    fi->fh = ptr_to_id(h);
    fi->keep_cache = 1;
    fuse_reply_open(req, fi);
}

static void do_read(fuse_req_t req, size_t size, off_t off, struct fuse_file_info* fi) {
    handle* h = reinterpret_cast<handle*>(fi->fh);
    struct fuse_bufvec buf = FUSE_BUFVEC_INIT(size);

    buf.buf[0].fd = h->fd;
    buf.buf[0].pos = off;
    buf.buf[0].flags =
            (enum fuse_buf_flags) (FUSE_BUF_IS_FD | FUSE_BUF_FD_SEEK);

    fuse_reply_data(req, &buf, (enum fuse_buf_copy_flags) 0);
}

static bool range_contains(const RedactionRange& rr, off_t off) {
    return rr.first <= off && off <= rr.second;
}

/**
 * Sets the parameters for a fuse_buf that reads from memory, including flags.
 * Makes buf->mem point to an already mapped region of zeroized memory.
 * This memory is read only.
 */
static void create_mem_fuse_buf(size_t size, fuse_buf* buf, struct fuse* fuse) {
    buf->size = size;
    buf->mem = fuse->zero_addr;
    buf->flags = static_cast<fuse_buf_flags>(0 /*read from fuse_buf.mem*/);
    buf->pos = -1;
    buf->fd = -1;
}

/**
 * Sets the parameters for a fuse_buf that reads from file, including flags.
 */
static void create_file_fuse_buf(size_t size, off_t pos, int fd, fuse_buf* buf) {
    buf->size = size;
    buf->fd = fd;
    buf->pos = pos;
    buf->flags = static_cast<fuse_buf_flags>(FUSE_BUF_IS_FD | FUSE_BUF_FD_SEEK);
    buf->mem = nullptr;
}

static void do_read_with_redaction(fuse_req_t req, size_t size, off_t off, fuse_file_info* fi) {
    handle* h = reinterpret_cast<handle*>(fi->fh);
    auto overlapping_rr = h->ri->getOverlappingRedactionRanges(size, off);

    if (overlapping_rr->size() <= 0) {
        // no relevant redaction ranges for this request
        do_read(req, size, off, fi);
        return;
    }
    // the number of buffers we need, if the read doesn't start or end with
    //  a redaction range.
    int num_bufs = overlapping_rr->size() * 2 + 1;
    if (overlapping_rr->front().first <= off) {
        // the beginning of the read request is redacted
        num_bufs--;
    }
    if (overlapping_rr->back().second >= off + size) {
        // the end of the read request is redacted
        num_bufs--;
    }
    auto bufvec_ptr = std::unique_ptr<fuse_bufvec, decltype(free)*>{
            reinterpret_cast<fuse_bufvec*>(
                    malloc(sizeof(fuse_bufvec) + (num_bufs - 1) * sizeof(fuse_buf))),
            free};
    fuse_bufvec& bufvec = *bufvec_ptr;

    // initialize bufvec
    bufvec.count = num_bufs;
    bufvec.idx = 0;
    bufvec.off = 0;

    int rr_idx = 0;
    off_t start = off;
    // Add a dummy redaction range to make sure we don't go out of vector
    // limits when computing the end of the last non-redacted range.
    // This ranges is invalid because its starting point is larger than it's ending point.
    overlapping_rr->push_back(RedactionRange(LLONG_MAX, LLONG_MAX - 1));

    for (int i = 0; i < num_bufs; ++i) {
        off_t end;
        if (range_contains(overlapping_rr->at(rr_idx), start)) {
            // Handle a redacted range
            // end should be the end of the redacted range, but can't be out of
            // the read request bounds
            end = std::min(static_cast<off_t>(off + size - 1), overlapping_rr->at(rr_idx).second);
            create_mem_fuse_buf(/*size*/ end - start + 1, &(bufvec.buf[i]), get_fuse(req));
            ++rr_idx;
        } else {
            // Handle a non-redacted range
            // end should be right before the next redaction range starts or
            // the end of the read request
            end = std::min(static_cast<off_t>(off + size - 1),
                    overlapping_rr->at(rr_idx).first - 1);
            create_file_fuse_buf(/*size*/ end - start + 1, start, h->fd, &(bufvec.buf[i]));
        }
        start = end + 1;
    }

    fuse_reply_data(req, &bufvec, static_cast<fuse_buf_copy_flags>(0));
}

static void pf_read(fuse_req_t req, fuse_ino_t ino, size_t size, off_t off,
                    struct fuse_file_info* fi) {
    ATRACE_CALL();
    handle* h = reinterpret_cast<handle*>(fi->fh);
    struct fuse* fuse = get_fuse(req);

    fuse->fadviser.Record(h->fd, size);

    if (h->ri->isRedactionNeeded()) {
        do_read_with_redaction(req, size, off, fi);
    } else {
        do_read(req, size, off, fi);
    }
}

/*
static void pf_write(fuse_req_t req, fuse_ino_t ino, const char* buf,
                       size_t size, off_t off, struct fuse_file_info* fi)
{
    cout << "TODO:" << __func__;
}
*/

static void pf_write_buf(fuse_req_t req,
                         fuse_ino_t ino,
                         struct fuse_bufvec* bufv,
                         off_t off,
                         struct fuse_file_info* fi) {
    ATRACE_CALL();
    handle* h = reinterpret_cast<handle*>(fi->fh);
    struct fuse_bufvec buf = FUSE_BUFVEC_INIT(fuse_buf_size(bufv));
    ssize_t size;
    struct fuse* fuse = get_fuse(req);

    buf.buf[0].fd = h->fd;
    buf.buf[0].pos = off;
    buf.buf[0].flags =
            (enum fuse_buf_flags) (FUSE_BUF_IS_FD | FUSE_BUF_FD_SEEK);
    size = fuse_buf_copy(&buf, bufv, (enum fuse_buf_copy_flags) 0);

    if (size < 0)
        fuse_reply_err(req, -size);
    else {
        fuse_reply_write(req, size);
        fuse->fadviser.Record(h->fd, size);
    }
}
// Haven't tested this one. Not sure what calls it.
#if 0
static void pf_copy_file_range(fuse_req_t req, fuse_ino_t ino_in,
                                 off_t off_in, struct fuse_file_info* fi_in,
                                 fuse_ino_t ino_out, off_t off_out,
                                 struct fuse_file_info* fi_out, size_t len,
                                 int flags)
{
    handle* h_in = reinterpret_cast<handle *>(fi_in->fh);
    handle* h_out = reinterpret_cast<handle *>(fi_out->fh);
    struct fuse_bufvec buf_in = FUSE_BUFVEC_INIT(len);
    struct fuse_bufvec buf_out = FUSE_BUFVEC_INIT(len);
    ssize_t size;

    buf_in.buf[0].fd = h_in->fd;
    buf_in.buf[0].pos = off_in;
    buf_in.buf[0].flags = (enum fuse_buf_flags)(FUSE_BUF_IS_FD|FUSE_BUF_FD_SEEK);

    buf_out.buf[0].fd = h_out->fd;
    buf_out.buf[0].pos = off_out;
    buf_out.buf[0].flags = (enum fuse_buf_flags)(FUSE_BUF_IS_FD|FUSE_BUF_FD_SEEK);
    size = fuse_buf_copy(&buf_out, &buf_in, (enum fuse_buf_copy_flags) 0);

    if (size < 0) {
        fuse_reply_err(req, -size);
    }

    fuse_reply_write(req, size);
}
#endif
static void pf_flush(fuse_req_t req,
                     fuse_ino_t ino,
                     struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    TRACE_FUSE(fuse) << "FLUSH is a noop";
    fuse_reply_err(req, 0);
}

static void pf_release(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);

    node* node = fuse->FromInode(ino);
    handle* h = reinterpret_cast<handle*>(fi->fh);
    TRACE_FUSE(fuse) << "RELEASE "
                     << "0" << std::oct << fi->flags << " " << h << "(" << h->fd << ")";

    fuse->fadviser.Close(h->fd);
    // TODO(b/145737191): Figure out if we need to scan files on close, and how to do it properly
    if (node) {
        node->DestroyHandle(h);
    }

    fuse_reply_err(req, 0);
}

static int do_sync_common(int fd, bool datasync) {
    int res = datasync ? fdatasync(fd) : fsync(fd);

    if (res == -1) return errno;
    return 0;
}

static void pf_fsync(fuse_req_t req,
                     fuse_ino_t ino,
                     int datasync,
                     struct fuse_file_info* fi) {
    ATRACE_CALL();
    handle* h = reinterpret_cast<handle*>(fi->fh);
    int err = do_sync_common(h->fd, datasync);

    fuse_reply_err(req, err);
}

static void pf_fsyncdir(fuse_req_t req,
                        fuse_ino_t ino,
                        int datasync,
                        struct fuse_file_info* fi) {
    dirhandle* h = reinterpret_cast<dirhandle*>(fi->fh);
    int err = do_sync_common(dirfd(h->d), datasync);

    fuse_reply_err(req, err);
}

static void pf_opendir(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* node = fuse->FromInode(ino);
    const string path = node->BuildPath();

    TRACE_FUSE(fuse) << "OPENDIR @ " << ino << " (" << safe_name(node) << ")" << path;

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    int status = fuse->mp->IsOpendirAllowed(path, ctx->uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    DIR* dir = opendir(path.c_str());
    if (!dir) {
        fuse_reply_err(req, errno);
        return;
    }

    dirhandle* h = new dirhandle(dir);
    node->AddDirHandle(h);

    fi->fh = ptr_to_id(h);
    fuse_reply_open(req, fi);
}

#define READDIR_BUF 8192LU

static void do_readdir_common(fuse_req_t req,
                              fuse_ino_t ino,
                              size_t size,
                              off_t off,
                              struct fuse_file_info* fi,
                              bool plus) {
    struct fuse* fuse = get_fuse(req);
    dirhandle* h = reinterpret_cast<dirhandle*>(fi->fh);
    size_t len = std::min<size_t>(size, READDIR_BUF);
    char buf[READDIR_BUF];
    size_t used = 0;
    std::shared_ptr<DirectoryEntry> de;

    struct fuse_entry_param e;
    size_t entry_size = 0;

    node* node = fuse->FromInode(ino);
    const string path = node->BuildPath();

    TRACE_FUSE(fuse) << "READDIR @" << ino << " " << path << " at offset " << off;
    // Get all directory entries from MediaProvider on first readdir() call of
    // directory handle. h->next_off = 0 indicates that current readdir() call
    // is first readdir() call for the directory handle, Avoid multiple JNI calls
    // for single directory handle.
    if (h->next_off == 0) {
        h->de = fuse->mp->GetDirectoryEntries(req->ctx.uid, path, h->d);
    }
    // If the last entry in the previous readdir() call was rejected due to
    // buffer capacity constraints, update directory offset to start from
    // previously rejected entry. Directory offset can also change if there was
    // a seekdir() on the given directory handle.
    if (off != h->next_off) {
        h->next_off = off;
    }
    const int num_directory_entries = h->de.size();
    // Check for errors. Any error/exception occurred while obtaining directory
    // entries will be indicated by marking first directory entry name as empty
    // string. In the erroneous case corresponding d_type will hold error number.
    if (num_directory_entries && h->de[0]->d_name.empty()) {
        fuse_reply_err(req, h->de[0]->d_type);
        return;
    }

    while (h->next_off < num_directory_entries) {
        de = h->de[h->next_off];
        entry_size = 0;
        h->next_off++;
        if (plus) {
            int error_code = 0;
            if (do_lookup(req, ino, de->d_name.c_str(), &e, &error_code)) {
                entry_size = fuse_add_direntry_plus(req, buf + used, len - used, de->d_name.c_str(),
                                                    &e, h->next_off);
            } else {
                // Ignore lookup errors on
                // 1. non-existing files returned from MediaProvider database.
                // 2. path that doesn't match FuseDaemon UID and calling uid.
                if (error_code == ENOENT || error_code == EPERM) continue;
                fuse_reply_err(req, error_code);
                return;
            }
        } else {
            e.attr.st_ino = FUSE_UNKNOWN_INO;
            e.attr.st_mode = de->d_type;
            entry_size = fuse_add_direntry(req, buf + used, len - used, de->d_name.c_str(), &e.attr,
                                           h->next_off);
        }
        // If buffer in fuse_add_direntry[_plus] is not large enough then
        // the entry is not added to buffer but the size of the entry is still
        // returned. Check available buffer size + returned entry size is less
        // than actual buffer size to confirm entry is added to buffer.
        if (used + entry_size > len) {
            // When an entry is rejected, lookup called by readdir_plus will not be tracked by
            // kernel. Call forget on the rejected node to decrement the reference count.
            if (plus) {
                do_forget(fuse, e.ino, 1);
            }
            break;
        }
        used += entry_size;
    }
    fuse_reply_buf(req, buf, used);
}

static void pf_readdir(fuse_req_t req, fuse_ino_t ino, size_t size, off_t off,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    do_readdir_common(req, ino, size, off, fi, false);
}

static void pf_readdirplus(fuse_req_t req,
                           fuse_ino_t ino,
                           size_t size,
                           off_t off,
                           struct fuse_file_info* fi) {
    ATRACE_CALL();
    do_readdir_common(req, ino, size, off, fi, true);
}

static void pf_releasedir(fuse_req_t req,
                          fuse_ino_t ino,
                          struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);

    node* node = fuse->FromInode(ino);
    dirhandle* h = reinterpret_cast<dirhandle*>(fi->fh);
    TRACE_FUSE(fuse) << "RELEASEDIR " << h;
    if (node) {
        node->DestroyDirHandle(h);
    }

    fuse_reply_err(req, 0);
}

static void pf_statfs(fuse_req_t req, fuse_ino_t ino) {
    ATRACE_CALL();
    struct statvfs st;
    struct fuse* fuse = get_fuse(req);

    if (statvfs(fuse->root->GetName().c_str(), &st))
        fuse_reply_err(req, errno);
    else
        fuse_reply_statfs(req, &st);
}
/*
static void pf_setxattr(fuse_req_t req, fuse_ino_t ino, const char* name,
                          const char* value, size_t size, int flags)
{
    cout << "TODO:" << __func__;
}

static void pf_getxattr(fuse_req_t req, fuse_ino_t ino, const char* name,
                          size_t size)
{
    cout << "TODO:" << __func__;
}

static void pf_listxattr(fuse_req_t req, fuse_ino_t ino, size_t size)
{
    cout << "TODO:" << __func__;
}

static void pf_removexattr(fuse_req_t req, fuse_ino_t ino, const char* name)
{
    cout << "TODO:" << __func__;
}*/

static void pf_access(fuse_req_t req, fuse_ino_t ino, int mask) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);

    node* node = fuse->FromInode(ino);
    const string path = node->BuildPath();
    TRACE_FUSE_VERBOSE(fuse) << "ACCESS " << path;

    int res = access(path.c_str(), F_OK);
    fuse_reply_err(req, res ? errno : 0);
}

static void pf_create(fuse_req_t req,
                      fuse_ino_t parent,
                      const char* name,
                      mode_t mode,
                      struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    node* parent_node = fuse->FromInode(parent);
    const string parent_path = parent_node->BuildPath();

    TRACE_FUSE(fuse) << "CREATE " << name << " 0" << std::oct << fi->flags << " @ " << parent
                     << " (" << safe_name(parent_node) << ")";

    const string child_path = parent_path + "/" + name;

    int mp_return_code = fuse->mp->InsertFile(child_path.c_str(), ctx->uid);
    if (mp_return_code) {
        PLOG(DEBUG) << "Could not create file: " << child_path;
        fuse_reply_err(req, mp_return_code);
        return;
    }

    mode = (mode & (~0777)) | 0664;
    int fd = open(child_path.c_str(), fi->flags, mode);
    if (fd < 0) {
        int error_code = errno;
        // We've already inserted the file into the MP database before the
        // failed open(), so that needs to be rolled back here.
        fuse->mp->DeleteFile(child_path.c_str(), ctx->uid);
        fuse_reply_err(req, error_code);
        return;
    }

    // TODO(b/147274248): Assume there will be no EXIF to redact.
    // This prevents crashing during reads but can be a security hole if a malicious app opens an fd
    // to the file before all the EXIF content is written. We could special case reads before the
    // first close after a file has just been created.
    handle* h = new handle(child_path, fd, new RedactionInfo(), true /* cached */);
    fi->fh = ptr_to_id(h);
    fi->keep_cache = 1;

    int error_code = 0;
    struct fuse_entry_param e;
    node* node = make_node_entry(req, parent_node, name, child_path, &e, &error_code);
    if (node) {
        node->AddHandle(h);
        fuse_reply_create(req, &e, fi);
    } else {
        CHECK(error_code != 0);
        fuse_reply_err(req, error_code);
    }
}
/*
static void pf_getlk(fuse_req_t req, fuse_ino_t ino,
                       struct fuse_file_info* fi, struct flock* lock)
{
    cout << "TODO:" << __func__;
}

static void pf_setlk(fuse_req_t req, fuse_ino_t ino,
                       struct fuse_file_info* fi,
                       struct flock* lock, int sleep)
{
    cout << "TODO:" << __func__;
}

static void pf_bmap(fuse_req_t req, fuse_ino_t ino, size_t blocksize,
                      uint64_t idx)
{
    cout << "TODO:" << __func__;
}

static void pf_ioctl(fuse_req_t req, fuse_ino_t ino, unsigned int cmd,
                       void* arg, struct fuse_file_info* fi, unsigned flags,
                       const void* in_buf, size_t in_bufsz, size_t out_bufsz)
{
    cout << "TODO:" << __func__;
}

static void pf_poll(fuse_req_t req, fuse_ino_t ino, struct fuse_file_info* fi,
                      struct fuse_pollhandle* ph)
{
    cout << "TODO:" << __func__;
}

static void pf_retrieve_reply(fuse_req_t req, void* cookie, fuse_ino_t ino,
                                off_t offset, struct fuse_bufvec* bufv)
{
    cout << "TODO:" << __func__;
}

static void pf_flock(fuse_req_t req, fuse_ino_t ino,
                       struct fuse_file_info* fi, int op)
{
    cout << "TODO:" << __func__;
}

static void pf_fallocate(fuse_req_t req, fuse_ino_t ino, int mode,
                       off_t offset, off_t length, struct fuse_file_info* fi)
{
    cout << "TODO:" << __func__;
}
*/

static struct fuse_lowlevel_ops ops{
    .init = pf_init,
    .destroy = pf_destroy,
    .lookup = pf_lookup, .forget = pf_forget, .getattr = pf_getattr,
    .setattr = pf_setattr,
    .canonical_path = pf_canonical_path,
    .mknod = pf_mknod, .mkdir = pf_mkdir, .unlink = pf_unlink,
    .rmdir = pf_rmdir,
    /*.symlink = pf_symlink,*/
    .rename = pf_rename,
    /*.link = pf_link,*/
    .open = pf_open, .read = pf_read,
    /*.write = pf_write,*/
    .flush = pf_flush, .release = pf_release, .fsync = pf_fsync,
    .opendir = pf_opendir, .readdir = pf_readdir, .releasedir = pf_releasedir,
    .fsyncdir = pf_fsyncdir, .statfs = pf_statfs,
    /*.setxattr = pf_setxattr,
    .getxattr = pf_getxattr,
    .listxattr = pf_listxattr,
    .removexattr = pf_removexattr,*/
    .access = pf_access, .create = pf_create,
    /*.getlk = pf_getlk,
    .setlk = pf_setlk,
    .bmap = pf_bmap,
    .ioctl = pf_ioctl,
    .poll = pf_poll,*/
    .write_buf = pf_write_buf,
    /*.retrieve_reply = pf_retrieve_reply,*/
    .forget_multi = pf_forget_multi,
    /*.flock = pf_flock,
    .fallocate = pf_fallocate,*/
    .readdirplus = pf_readdirplus,
    /*.copy_file_range = pf_copy_file_range,*/
};

static struct fuse_loop_config config = {
        .clone_fd = 1,
        .max_idle_threads = 10,
};

static std::unordered_map<enum fuse_log_level, enum android_LogPriority> fuse_to_android_loglevel({
    {FUSE_LOG_EMERG, ANDROID_LOG_FATAL},
    {FUSE_LOG_ALERT, ANDROID_LOG_ERROR},
    {FUSE_LOG_CRIT, ANDROID_LOG_ERROR},
    {FUSE_LOG_ERR, ANDROID_LOG_ERROR},
    {FUSE_LOG_WARNING, ANDROID_LOG_WARN},
    {FUSE_LOG_NOTICE, ANDROID_LOG_INFO},
    {FUSE_LOG_INFO, ANDROID_LOG_DEBUG},
    {FUSE_LOG_DEBUG, ANDROID_LOG_VERBOSE},
    });

static void fuse_logger(enum fuse_log_level level, const char* fmt, va_list ap) {
    __android_log_vprint(fuse_to_android_loglevel.at(level), LIBFUSE_LOG_TAG, fmt, ap);
}

bool FuseDaemon::ShouldOpenWithFuse(int fd, bool for_read, const std::string& path) {
    TRACE_VERBOSE << "Checking if file should be opened with FUSE " << path;
    bool use_fuse = false;

    if (active.load(std::memory_order_acquire)) {
        const node* node = node::LookupAbsolutePath(fuse->root, path);
        if (node && node->HasCachedHandle()) {
            TRACE << "Should open " << path << " with FUSE. Reason: cache";
            use_fuse = true;
        } else {
            // If we are unable to set a lock, we should use fuse since we can't track
            // when all fd references (including dups) are closed. This can happen when
            // we try to set a write lock twice on the same file
            use_fuse = set_file_lock(fd, for_read, path);
            TRACE << "Should open " << path << (use_fuse ? " with" : " without")
                  << " FUSE. Reason: lock";
        }
    } else {
        TRACE << "FUSE daemon is inactive. Should not open " << path << " with FUSE";
    }

    return use_fuse;
}

FuseDaemon::FuseDaemon(JNIEnv* env, jobject mediaProvider) : mp(env, mediaProvider),
                                                             active(false), fuse(nullptr) {}

void FuseDaemon::Start(const int fd, const std::string& path) {
    struct fuse_args args;
    struct fuse_cmdline_opts opts;

    SetMinimumLogSeverity(android::base::VERBOSE);

    struct stat stat;

    if (lstat(path.c_str(), &stat)) {
        LOG(ERROR) << "ERROR: failed to stat source " << path;
        return;
    }

    if (!S_ISDIR(stat.st_mode)) {
        LOG(ERROR) << "ERROR: source is not a directory";
        return;
    }

    args = FUSE_ARGS_INIT(0, nullptr);
    if (fuse_opt_add_arg(&args, path.c_str()) || fuse_opt_add_arg(&args, "-odebug") ||
        fuse_opt_add_arg(&args, ("-omax_read=" + std::to_string(MAX_READ_SIZE)).c_str())) {
        LOG(ERROR) << "ERROR: failed to set options";
        return;
    }

    struct fuse fuse_default(path);
    fuse_default.mp = &mp;
    // fuse_default is stack allocated, but it's safe to save it as an instance variable because
    // this method blocks and FuseDaemon#active tells if we are currently blocking
    fuse = &fuse_default;

    // Used by pf_read: redacted ranges are represented by zeroized ranges of bytes,
    // so we mmap the maximum length of redacted ranges in the beginning and save memory allocations
    // on each read.
    fuse_default.zero_addr = static_cast<char*>(mmap(
            NULL, MAX_READ_SIZE, PROT_READ, MAP_ANONYMOUS | MAP_PRIVATE, /*fd*/ -1, /*off*/ 0));
    if (fuse_default.zero_addr == MAP_FAILED) {
        LOG(FATAL) << "mmap failed - could not start fuse! errno = " << errno;
    }

    umask(0);

    // Custom logging for libfuse
    fuse_set_log_func(fuse_logger);

    struct fuse_session
            * se = fuse_session_new(&args, &ops, sizeof(ops), &fuse_default);
    if (!se) {
        PLOG(ERROR) << "Failed to create session ";
        return;
    }
    se->fd = fd;
    se->mountpoint = strdup(path.c_str());

    // Single thread. Useful for debugging
    // fuse_session_loop(se);
    // Multi-threaded
    LOG(INFO) << "Starting fuse...";
    active.store(true, std::memory_order_release);
    fuse_session_loop_mt(se, &config);
    LOG(INFO) << "Ending fuse...";

    if (munmap(fuse_default.zero_addr, MAX_READ_SIZE)) {
        PLOG(ERROR) << "munmap failed!";
    }

    fuse_opt_free_args(&args);
    fuse_session_destroy(se);
    active.store(false, std::memory_order_relaxed);
    LOG(INFO) << "Ended fuse";
    return;
}
} //namespace fuse
}  // namespace mediaprovider
