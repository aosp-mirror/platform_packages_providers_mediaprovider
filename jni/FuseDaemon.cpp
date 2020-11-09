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
#include <android-base/properties.h>
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
#include "libfuse_jni/FuseUtils.h"
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
#define TRACE_NODE(__node, __req)                                                  \
    LOG(VERBOSE) << __FUNCTION__ << " : " << #__node << " = [" << get_name(__node) \
                 << "] (uid=" << __req->ctx.uid << ") "

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

const bool IS_OS_DEBUGABLE = android::base::GetIntProperty("ro.debuggable", 0);

#define FUSE_UNKNOWN_INO 0xffffffff

// Stolen from: android_filesystem_config.h
#define AID_APP_START 10000

constexpr size_t MAX_READ_SIZE = 128 * 1024;
// Stolen from: UserHandle#getUserId
constexpr int PER_USER_RANGE = 100000;

// Stolen from: UserManagerService
constexpr int MAX_USER_ID = UINT32_MAX / PER_USER_RANGE;

// Regex copied from FileUtils.java in MediaProvider, but without media directory.
const std::regex PATTERN_OWNED_PATH(
    "^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|obb|sandbox)/([^/]+)(/?.*)?",
    std::regex_constants::icase);

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

    std::mutex mutex_;
    std::condition_variable cv_;
    std::queue<Message> queue_;
    std::thread thread_;

    typedef std::multimap<size_t, int> Sizes;
    typedef std::map<int, Sizes::iterator> Files;

    Files files_;
    Sizes sizes_;
    size_t total_size_;

    const size_t threshold_ = 64 * 1024 * 1024;
    const size_t target_ = 32 * 1024 * 1024;
};

/* Single FUSE mount */
struct fuse {
    explicit fuse(const std::string& _path)
        : path(_path),
          tracker(mediaprovider::fuse::NodeTracker(&lock)),
          root(node::CreateRoot(_path, &lock, &tracker)),
          mp(0),
          zero_addr(0),
          disable_dentry_cache(false) {}

    inline bool IsRoot(const node* node) const { return node == root; }

    inline string GetEffectiveRootPath() {
        if (path.find("/storage/emulated", 0) == 0) {
            return path + "/" + std::to_string(getuid() / PER_USER_RANGE);
        }
        return path;
    }

    // Note that these two (FromInode / ToInode) conversion wrappers are required
    // because fuse_lowlevel_ops documents that the root inode is always one
    // (see FUSE_ROOT_ID in fuse_lowlevel.h). There are no particular requirements
    // on any of the other inodes in the FS.
    inline node* FromInode(__u64 inode) {
        if (inode == FUSE_ROOT_ID) {
            return root;
        }

        return node::FromInode(inode, &tracker);
    }

    inline __u64 ToInode(node* node) const {
        if (IsRoot(node)) {
            return FUSE_ROOT_ID;
        }

        return node::ToInode(node);
    }

    std::recursive_mutex lock;
    const string path;
    // The Inode tracker associated with this FUSE instance.
    mediaprovider::fuse::NodeTracker tracker;
    node* const root;
    struct fuse_session* se;

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

    std::atomic_bool* active;
    std::atomic_bool disable_dentry_cache;
};

static inline string get_name(node* n) {
    if (n) {
        std::string name = IS_OS_DEBUGABLE ? "real_path: " + n->BuildPath() + " " : "";
        name += "node_path: " + n->BuildSafePath();
        return name;
    }
    return "?";
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

    struct flock fl{};
    fl.l_type = for_read ? F_RDLCK : F_WRLCK;
    fl.l_whence = SEEK_SET;

    int res = fcntl(fd, F_OFD_SETLK, &fl);
    if (res) {
        PLOG(WARNING) << "Failed to set lock: " << lock_str;
        return res;
    }
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
    struct flock fl{};
    fl.l_type = F_WRLCK;
    fl.l_whence = SEEK_SET;

    int res = fcntl(fd, F_OFD_GETLK, &fl);
    if (res) {
        PLOG(WARNING) << "Failed to check lock";
        // Assume worst
        return true;
    }
    bool locked = fl.l_type != F_UNLCK;
    return locked;
}

static struct fuse* get_fuse(fuse_req_t req) {
    return reinterpret_cast<struct fuse*>(fuse_req_userdata(req));
}

static bool is_package_owned_path(const string& path, const string& fuse_path) {
    if (path.rfind(fuse_path, 0) != 0) {
        return false;
    }
    return std::regex_match(path, PATTERN_OWNED_PATH);
}

// See fuse_lowlevel.h fuse_lowlevel_notify_inval_entry for how to call this safetly without
// deadlocking the kernel
static void fuse_inval(fuse_session* se, fuse_ino_t parent_ino, fuse_ino_t child_ino,
                       const string& child_name, const string& path) {
    if (mediaprovider::fuse::containsMount(path, std::to_string(getuid() / PER_USER_RANGE))) {
        LOG(WARNING) << "Ignoring attempt to invalidate dentry for FUSE mounts";
        return;
    }

    if (fuse_lowlevel_notify_inval_entry(se, parent_ino, child_name.c_str(), child_name.size())) {
        // Invalidating the dentry can fail if there's no dcache entry, however, there may still
        // be cached attributes, so attempt to invalidate those by invalidating the inode
        fuse_lowlevel_notify_inval_inode(se, child_ino, 0, 0);
    }
}

static double get_timeout(struct fuse* fuse, const string& path, bool should_inval) {
    string media_path = fuse->GetEffectiveRootPath() + "/Android/media";
    if (fuse->disable_dentry_cache || should_inval || path.find(media_path, 0) == 0 || is_package_owned_path(path, fuse->path)) {
        // We set dentry timeout to 0 for the following reasons:
        // 1. Case-insensitive lookups need to invalidate other case-insensitive dentry matches
        // 2. Installd might delete Android/media/<package> dirs when app data is cleared.
        // This can leave a stale entry in the kernel dcache, and break subsequent creation of the
        // dir via FUSE.
        // 3. With app data isolation enabled, app A should not guess existence of app B from the
        // Android/{data,obb}/<package> paths, hence we prevent the kernel from caching that
        // information.
        return 0;
    }
    return std::numeric_limits<double>::max();
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

    bool should_inval = false;
    node = parent->LookupChildByName(name, true /* acquire */);
    if (!node) {
        node = ::node::Create(parent, name, &fuse->lock, &fuse->tracker);
    } else if (!mediaprovider::fuse::containsMount(path, std::to_string(getuid() / PER_USER_RANGE))) {
        should_inval = node->HasCaseInsensitiveMatch();
        // Only invalidate a path if it does not contain mount.
        // Invalidate both names to ensure there's no dentry left in the kernel after the following
        // operations:
        // 1) touch foo, touch FOO, unlink *foo*
        // 2) touch foo, touch FOO, unlink *FOO*
        // Invalidating lookup_name fixes (1) and invalidating node_name fixes (2)
        // |should_inval| invalidates lookup_name by using 0 timeout below and we explicitly
        // invalidate node_name if different case
        // Note that we invalidate async otherwise we will deadlock the kernel
        if (name != node->GetName()) {
            should_inval = true;
            // Record that we have made a case insensitive lookup, this allows us invalidate nodes
            // correctly on subsequent lookups for the case of |node|
            node->SetCaseInsensitiveMatch();

            // Make copies of the node name and path so we're not attempting to acquire
            // any node locks from the invalidation thread. Depending on timing, we may end
            // up invalidating the wrong inode but that shouldn't result in correctness issues.
            const fuse_ino_t parent_ino = fuse->ToInode(parent);
            const fuse_ino_t child_ino = fuse->ToInode(node);
            const std::string& node_name = node->GetName();
            std::thread t([=]() { fuse_inval(fuse->se, parent_ino, child_ino, node_name, path); });
            t.detach();
        }
    }
    TRACE_NODE(node, req);

    // This FS is not being exported via NFS so just a fixed generation number
    // for now. If we do need this, we need to increment the generation ID each
    // time the fuse daemon restarts because that's what it takes for us to
    // reuse inode numbers.
    e->generation = 0;
    e->ino = fuse->ToInode(node);
    e->entry_timeout = get_timeout(fuse, path, should_inval);
    e->attr_timeout = is_package_owned_path(path, fuse->path) || should_inval
                              ? 0
                              : std::numeric_limits<double>::max();

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
    conn->want &= ~FUSE_CAP_AUTO_INVAL_DATA & ~FUSE_CAP_READDIRPLUS_AUTO;
    unsigned mask = (FUSE_CAP_SPLICE_WRITE | FUSE_CAP_SPLICE_MOVE | FUSE_CAP_SPLICE_READ |
                     FUSE_CAP_ASYNC_READ | FUSE_CAP_ATOMIC_O_TRUNC | FUSE_CAP_WRITEBACK_CACHE |
                     FUSE_CAP_EXPORT_SUPPORT | FUSE_CAP_FLOCK_LOCKS);
    conn->want |= conn->capable & mask;
    conn->max_read = MAX_READ_SIZE;

    struct fuse* fuse = reinterpret_cast<struct fuse*>(userdata);
    fuse->active->store(true, std::memory_order_release);
}

static void pf_destroy(void* userdata) {
    struct fuse* fuse = reinterpret_cast<struct fuse*>(userdata);
    LOG(INFO) << "DESTROY " << fuse->path;

    node::DeleteTree(fuse->root);
}

// Return true if the path is accessible for that uid.
static bool is_app_accessible_path(MediaProviderWrapper* mp, const string& path, uid_t uid) {
    if (uid < AID_APP_START) {
        return true;
    }

    if (path == "/storage/emulated") {
        // Apps should never refer to /storage/emulated - they should be using the user-spcific
        // subdirs, eg /storage/emulated/0
        return false;
    }

    std::smatch match;
    if (std::regex_match(path, match, PATTERN_OWNED_PATH)) {
        const std::string& pkg = match[1];
        // .nomedia is not a valid package. .nomedia always exists in /Android/data directory,
        // and it's not an external file/directory of any package
        if (pkg == ".nomedia") {
            return true;
        }
        if (!mp->IsUidForPackage(pkg, uid)) {
            PLOG(WARNING) << "Invalid other package file access from " << pkg << "(: " << path;
            return false;
        }
    }
    return true;
}

static std::regex storage_emulated_regex("^\\/storage\\/emulated\\/([0-9]+)");
static node* do_lookup(fuse_req_t req, fuse_ino_t parent, const char* name,
                       struct fuse_entry_param* e, int* error_code) {
    struct fuse* fuse = get_fuse(req);
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        *error_code = ENOENT;
        return nullptr;
    }
    string parent_path = parent_node->BuildPath();
    // We should always allow lookups on the root, because failing them could cause
    // bind mounts to be invalidated.
    if (!fuse->IsRoot(parent_node) && !is_app_accessible_path(fuse->mp, parent_path, req->ctx.uid)) {
        *error_code = ENOENT;
        return nullptr;
    }

    string child_path = parent_path + "/" + name;

    TRACE_NODE(parent_node, req);

    std::smatch match;
    std::regex_search(child_path, match, storage_emulated_regex);

    // Ensure the FuseDaemon user id matches the user id or cross-user lookups are allowed in
    // requested path
    if (match.size() == 2 && std::to_string(getuid() / PER_USER_RANGE) != match[1].str()) {
        // If user id mismatch, check cross-user lookups
        long userId = strtol(match[1].str().c_str(), nullptr, 10);
        if (userId < 0 || userId > MAX_USER_ID ||
            !fuse->mp->ShouldAllowLookup(req->ctx.uid, userId)) {
            *error_code = EACCES;
            return nullptr;
        }
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

static void do_forget(fuse_req_t req, struct fuse* fuse, fuse_ino_t ino, uint64_t nlookup) {
    node* node = fuse->FromInode(ino);
    TRACE_NODE(node, req);
    if (node) {
        // This is a narrowing conversion from an unsigned 64bit to a 32bit value. For
        // some reason we only keep 32 bit refcounts but the kernel issues
        // forget requests with a 64 bit counter.
        node->Release(static_cast<uint32_t>(nlookup));
    }
}

static void pf_forget(fuse_req_t req, fuse_ino_t ino, uint64_t nlookup) {
    // Always allow to forget so no need to check is_app_accessible_path()
    ATRACE_CALL();
    node* node;
    struct fuse* fuse = get_fuse(req);

    do_forget(req, fuse, ino, nlookup);
    fuse_reply_none(req);
}

static void pf_forget_multi(fuse_req_t req,
                            size_t count,
                            struct fuse_forget_data* forgets) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);

    for (int i = 0; i < count; i++) {
        do_forget(req, fuse, forgets[i].ino, forgets[i].nlookup);
    }
    fuse_reply_none(req);
}

static void pf_getattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    node* node = fuse->FromInode(ino);
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    string path = node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    TRACE_NODE(node, req);

    struct stat s;
    memset(&s, 0, sizeof(s));
    if (lstat(path.c_str(), &s) < 0) {
        fuse_reply_err(req, errno);
    } else {
        fuse_reply_attr(req, &s, is_package_owned_path(path, fuse->path) ?
                0 : std::numeric_limits<double>::max());
    }
}

static void pf_setattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct stat* attr,
                       int to_set,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    node* node = fuse->FromInode(ino);
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    string path = node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    int fd = -1;
    if (fi) {
        // If we have a file_info, setattr was called with an fd so use the fd instead of path
        handle* h = reinterpret_cast<handle*>(fi->fh);
        fd = h->fd;
    } else {
        const struct fuse_ctx* ctx = fuse_req_ctx(req);
        int status = fuse->mp->IsOpenAllowed(path, ctx->uid, true);
        if (status) {
            fuse_reply_err(req, EACCES);
            return;
        }
    }
    struct timespec times[2];
    TRACE_NODE(node, req);

    /* XXX: incomplete implementation on purpose.
     * chmod/chown should NEVER be implemented.*/

    if ((to_set & FUSE_SET_ATTR_SIZE)) {
        int res = 0;
        if (fd == -1) {
            res = truncate64(path.c_str(), attr->st_size);
        } else {
            res = ftruncate64(fd, attr->st_size);
        }

        if (res < 0) {
            fuse_reply_err(req, errno);
            return;
        }
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
                times[0] = attr->st_atim;
            }
        }

        if (to_set & FATTR_MTIME) {
            if (to_set & FATTR_MTIME_NOW) {
                times[1].tv_nsec = UTIME_NOW;
            } else {
                times[1] = attr->st_mtim;
            }
        }

        TRACE_NODE(node, req);
        int res = 0;
        if (fd == -1) {
            res = utimensat(-1, path.c_str(), times, 0);
        } else {
            res = futimens(fd, times);
        }

        if (res < 0) {
            fuse_reply_err(req, errno);
            return;
        }
    }

    lstat(path.c_str(), attr);
    fuse_reply_attr(req, attr, is_package_owned_path(path, fuse->path) ?
            0 : std::numeric_limits<double>::max());
}

static void pf_canonical_path(fuse_req_t req, fuse_ino_t ino)
{
    struct fuse* fuse = get_fuse(req);
    node* node = fuse->FromInode(ino);
    string path = node ? node->BuildPath() : "";

    if (node && is_app_accessible_path(fuse->mp, path, req->ctx.uid)) {
        // TODO(b/147482155): Check that uid has access to |path| and its contents
        fuse_reply_canonical_path(req, path.c_str());
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
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    string parent_path = parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, parent_path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(parent_node, req);

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
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    const string parent_path = parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, parent_path, ctx->uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(parent_node, req);

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
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    const string parent_path = parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, parent_path, ctx->uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(parent_node, req);

    const string child_path = parent_path + "/" + name;

    int status = fuse->mp->DeleteFile(child_path, ctx->uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    node* child_node = parent_node->LookupChildByName(name, false /* acquire */);
    TRACE_NODE(child_node, req);
    if (child_node) {
        child_node->SetDeleted();
    }

    fuse_reply_err(req, 0);
}

static void pf_rmdir(fuse_req_t req, fuse_ino_t parent, const char* name) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const string parent_path = parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, parent_path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    TRACE_NODE(parent_node, req);

    const string child_path = parent_path + "/" + name;

    int status = fuse->mp->IsDeletingDirAllowed(child_path, req->ctx.uid);
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    if (rmdir(child_path.c_str()) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    node* child_node = parent_node->LookupChildByName(name, false /* acquire */);
    TRACE_NODE(child_node, req);
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

    if (flags != 0) {
        return EINVAL;
    }

    node* old_parent_node = fuse->FromInode(parent);
    if (!old_parent_node) return ENOENT;
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    const string old_parent_path = old_parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, old_parent_path, ctx->uid)) {
        return ENOENT;
    }

    node* new_parent_node = fuse->FromInode(new_parent);
    if (!new_parent_node) return ENOENT;
    const string new_parent_path = new_parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, new_parent_path, ctx->uid)) {
        return ENOENT;
    }

    if (!old_parent_node || !new_parent_node) {
        return ENOENT;
    } else if (parent == new_parent && name == new_name) {
        // No rename required.
        return 0;
    }

    TRACE_NODE(old_parent_node, req);
    TRACE_NODE(new_parent_node, req);

    node* child_node = old_parent_node->LookupChildByName(name, true /* acquire */);
    TRACE_NODE(child_node, req) << "old_child";

    const string old_child_path = child_node->BuildPath();
    const string new_child_path = new_parent_path + "/" + new_name;

    // TODO(b/147408834): Check ENOTEMPTY & EEXIST error conditions before JNI call.
    const int res = fuse->mp->Rename(old_child_path, new_child_path, req->ctx.uid);
    // TODO(b/145663158): Lookups can go out of sync if file/directory is actually moved but
    // EFAULT/EIO is reported due to JNI exception.
    if (res == 0) {
        child_node->Rename(new_name, new_parent_node);
    }
    TRACE_NODE(child_node, req) << "new_child";

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

static handle* create_handle_for_node(struct fuse* fuse, const string& path, int fd, node* node,
                                      const RedactionInfo* ri, int* keep_cache) {
    std::lock_guard<std::recursive_mutex> guard(fuse->lock);
    // We don't want to use the FUSE VFS cache in two cases:
    // 1. When redaction is needed because app A with EXIF access might access
    // a region that should have been redacted for app B without EXIF access, but app B on
    // a subsequent read, will be able to see the EXIF data because the read request for
    // that region will be served from cache and not get to the FUSE daemon
    // 2. When the file has a read or write lock on it. This means that the MediaProvider
    // has given an fd to the lower file system to an app. There are two cases where using
    // the cache in this case can be a problem:
    // a. Writing to a FUSE fd with caching enabled will use the write-back cache and a
    // subsequent read from the lower fs fd will not see the write.
    // b. Reading from a FUSE fd with caching enabled may not see the latest writes using
    // the lower fs fd because those writes did not go through the FUSE layer and reads from
    // FUSE after that write may be served from cache
    bool has_redacted = node->HasRedactedCache();
    bool redaction_needed = ri->isRedactionNeeded();
    bool is_redaction_change =
            (redaction_needed && !has_redacted) || (!redaction_needed && has_redacted);
    bool is_cached_file_open = node->HasCachedHandle();

    if (!is_cached_file_open && is_redaction_change) {
        node->SetRedactedCache(redaction_needed);
        // Purges stale page cache before open
        *keep_cache = 0;
    } else {
        *keep_cache = 1;
    }

    bool direct_io = (is_cached_file_open && is_redaction_change) || is_file_locked(fd, path);
    handle* h = new handle(fd, ri, !direct_io);
    node->AddHandle(h);
    return h;
}

static void pf_open(fuse_req_t req, fuse_ino_t ino, struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    node* node = fuse->FromInode(ino);
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    const string path = node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, path, ctx->uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(node, req) << (is_requesting_write(fi->flags) ? "write" : "read");

    if (fi->flags & O_DIRECT) {
        fi->flags &= ~O_DIRECT;
        fi->direct_io = true;
    }

    int status = fuse->mp->IsOpenAllowed(path, ctx->uid, is_requesting_write(fi->flags));
    if (status) {
        fuse_reply_err(req, status);
        return;
    }

    // With the writeback cache enabled, FUSE may generate READ requests even for files that
    // were opened O_WRONLY; so make sure we open it O_RDWR instead.
    int open_flags = fi->flags;
    if (open_flags & O_WRONLY) {
        open_flags &= ~O_WRONLY;
        open_flags |= O_RDWR;
    }

    if (open_flags & O_APPEND) {
        open_flags &= ~O_APPEND;
    }

    const int fd = open(path.c_str(), open_flags);
    if (fd < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    // We don't redact if the caller was granted write permission for this file
    std::unique_ptr<RedactionInfo> ri;
    if (is_requesting_write(fi->flags)) {
        ri = std::make_unique<RedactionInfo>();
    } else {
        ri = fuse->mp->GetRedactionInfo(path, req->ctx.uid, req->ctx.pid);
    }

    if (!ri) {
        close(fd);
        fuse_reply_err(req, EFAULT);
        return;
    }

    int keep_cache = 1;
    handle* h = create_handle_for_node(fuse, path, fd, node, ri.release(), &keep_cache);
    fi->fh = ptr_to_id(h);
    fi->keep_cache = keep_cache;
    fi->direct_io = !h->cached;
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

    std::vector<ReadRange> ranges;
    h->ri->getReadRanges(off, size, &ranges);

    // As an optimization, return early if there are no ranges to redact.
    if (ranges.size() == 0) {
        do_read(req, size, off, fi);
        return;
    }

    const size_t num_bufs = ranges.size();
    auto bufvec_ptr = std::unique_ptr<fuse_bufvec, decltype(free)*>{
            reinterpret_cast<fuse_bufvec*>(
                    malloc(sizeof(fuse_bufvec) + (num_bufs - 1) * sizeof(fuse_buf))),
            free};
    fuse_bufvec& bufvec = *bufvec_ptr;

    // initialize bufvec
    bufvec.count = num_bufs;
    bufvec.idx = 0;
    bufvec.off = 0;

    for (int i = 0; i < num_bufs; ++i) {
        const ReadRange& range = ranges[i];
        if (range.is_redaction) {
            create_mem_fuse_buf(range.size, &(bufvec.buf[i]), get_fuse(req));
        } else {
            create_file_fuse_buf(range.size, range.start, h->fd, &(bufvec.buf[i]));
        }
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
    TRACE_NODE(nullptr, req) << "noop";
    fuse_reply_err(req, 0);
}

static void pf_release(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);

    node* node = fuse->FromInode(ino);
    handle* h = reinterpret_cast<handle*>(fi->fh);
    TRACE_NODE(node, req);

    fuse->fadviser.Close(h->fd);
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
    node* node = fuse->FromInode(ino);
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    const string path = node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, path, ctx->uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(node, req);

    int status = fuse->mp->IsOpendirAllowed(path, ctx->uid, /* forWrite */ false);
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
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const string path = node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(node, req);
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
                if (error_code == ENOENT || error_code == EPERM || error_code == EACCES) continue;
                fuse_reply_err(req, error_code);
                return;
            }
        } else {
            // This should never happen because we have readdir_plus enabled without adaptive
            // readdir_plus, FUSE_CAP_READDIRPLUS_AUTO
            LOG(WARNING) << "Handling plain readdir for " << de->d_name << ". Invalid d_ino";
            e.attr.st_ino = FUSE_UNKNOWN_INO;
            e.attr.st_mode = de->d_type << 12;
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
                do_forget(req, fuse, e.ino, 1);
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
    TRACE_NODE(node, req);
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

    node* node = fuse->FromInode(ino);
    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const string path = node->BuildPath();
    if (path != "/storage/emulated" && !is_app_accessible_path(fuse->mp, path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    TRACE_NODE(node, req);

    // exists() checks are always allowed.
    if (mask == F_OK) {
        int res = access(path.c_str(), F_OK);
        fuse_reply_err(req, res ? errno : 0);
        return;
    }
    struct stat stat;
    if (lstat(path.c_str(), &stat)) {
        // File doesn't exist
        fuse_reply_err(req, ENOENT);
        return;
    }

    // For read and write permission checks we go to MediaProvider.
    int status = 0;
    bool for_write = mask & W_OK;
    bool is_directory = S_ISDIR(stat.st_mode);
    if (is_directory) {
        if (path == "/storage/emulated" && mask == X_OK) {
            // Special case for this path: apps should be allowed to enter it,
            // but not list directory contents (which would be user numbers).
            int res = access(path.c_str(), X_OK);
            fuse_reply_err(req, res ? errno : 0);
            return;
        }
        status = fuse->mp->IsOpendirAllowed(path, req->ctx.uid, for_write);
    } else {
        if (mask & X_OK) {
            // Fuse is mounted with MS_NOEXEC.
            fuse_reply_err(req, EACCES);
            return;
        }

        status = fuse->mp->IsOpenAllowed(path, req->ctx.uid, for_write);
    }

    fuse_reply_err(req, status);
}

static void pf_create(fuse_req_t req,
                      fuse_ino_t parent,
                      const char* name,
                      mode_t mode,
                      struct fuse_file_info* fi) {
    ATRACE_CALL();
    struct fuse* fuse = get_fuse(req);
    node* parent_node = fuse->FromInode(parent);
    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    const string parent_path = parent_node->BuildPath();
    if (!is_app_accessible_path(fuse->mp, parent_path, req->ctx.uid)) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    TRACE_NODE(parent_node, req);

    const string child_path = parent_path + "/" + name;

    int mp_return_code = fuse->mp->InsertFile(child_path.c_str(), req->ctx.uid);
    if (mp_return_code) {
        fuse_reply_err(req, mp_return_code);
        return;
    }

    // With the writeback cache enabled, FUSE may generate READ requests even for files that
    // were opened O_WRONLY; so make sure we open it O_RDWR instead.
    int open_flags = fi->flags;
    if (open_flags & O_WRONLY) {
        open_flags &= ~O_WRONLY;
        open_flags |= O_RDWR;
    }

    if (open_flags & O_APPEND) {
        open_flags &= ~O_APPEND;
    }

    mode = (mode & (~0777)) | 0664;
    int fd = open(child_path.c_str(), open_flags, mode);
    if (fd < 0) {
        int error_code = errno;
        // We've already inserted the file into the MP database before the
        // failed open(), so that needs to be rolled back here.
        fuse->mp->DeleteFile(child_path.c_str(), req->ctx.uid);
        fuse_reply_err(req, error_code);
        return;
    }

    int error_code = 0;
    struct fuse_entry_param e;
    node* node = make_node_entry(req, parent_node, name, child_path, &e, &error_code);
    TRACE_NODE(node, req);
    if (!node) {
        CHECK(error_code != 0);
        fuse_reply_err(req, error_code);
        return;
    }

    // Let MediaProvider know we've created a new file
    fuse->mp->OnFileCreated(child_path);

    // TODO(b/147274248): Assume there will be no EXIF to redact.
    // This prevents crashing during reads but can be a security hole if a malicious app opens an fd
    // to the file before all the EXIF content is written. We could special case reads before the
    // first close after a file has just been created.
    int keep_cache = 1;
    handle* h = create_handle_for_node(fuse, child_path, fd, node, new RedactionInfo(), &keep_cache);
    fi->fh = ptr_to_id(h);
    fi->keep_cache = keep_cache;
    fi->direct_io = !h->cached;
    fuse_reply_create(req, &e, fi);
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
    .init = pf_init, .destroy = pf_destroy, .lookup = pf_lookup, .forget = pf_forget,
    .getattr = pf_getattr, .setattr = pf_setattr, .canonical_path = pf_canonical_path,
    .mknod = pf_mknod, .mkdir = pf_mkdir, .unlink = pf_unlink, .rmdir = pf_rmdir,
    /*.symlink = pf_symlink,*/
    .rename = pf_rename,
    /*.link = pf_link,*/
    .open = pf_open, .read = pf_read,
    /*.write = pf_write,*/
    .flush = pf_flush,
    .release = pf_release, .fsync = pf_fsync, .opendir = pf_opendir, .readdir = pf_readdir,
    .releasedir = pf_releasedir, .fsyncdir = pf_fsyncdir, .statfs = pf_statfs,
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
    bool use_fuse = false;

    if (active.load(std::memory_order_acquire)) {
        std::lock_guard<std::recursive_mutex> guard(fuse->lock);
        const node* node = node::LookupAbsolutePath(fuse->root, path);
        if (node && node->HasCachedHandle()) {
            use_fuse = true;
        } else {
            // If we are unable to set a lock, we should use fuse since we can't track
            // when all fd references (including dups) are closed. This can happen when
            // we try to set a write lock twice on the same file
            use_fuse = set_file_lock(fd, for_read, path);
        }
    } else {
        LOG(WARNING) << "FUSE daemon is inactive. Cannot open file with FUSE";
    }

    return use_fuse;
}

void FuseDaemon::InvalidateFuseDentryCache(const std::string& path) {
    LOG(VERBOSE) << "Invalidating FUSE dentry cache";
    if (active.load(std::memory_order_acquire)) {
        string name;
        fuse_ino_t parent;
        fuse_ino_t child;
        {
            std::lock_guard<std::recursive_mutex> guard(fuse->lock);
            const node* node = node::LookupAbsolutePath(fuse->root, path);
            if (node) {
                name = node->GetName();
                child = fuse->ToInode(const_cast<class node*>(node));
                parent = fuse->ToInode(node->GetParent());
            }
        }

        if (!name.empty()) {
            fuse_inval(fuse->se, parent, child, name, path);
        }
    } else {
        LOG(WARNING) << "FUSE daemon is inactive. Cannot invalidate dentry";
    }
}

FuseDaemon::FuseDaemon(JNIEnv* env, jobject mediaProvider) : mp(env, mediaProvider),
                                                             active(false), fuse(nullptr) {}

bool FuseDaemon::IsStarted() const {
    return active.load(std::memory_order_acquire);
}

void FuseDaemon::Start(android::base::unique_fd fd, const std::string& path) {
    android::base::SetDefaultTag(LOG_TAG);

    struct fuse_args args;
    struct fuse_cmdline_opts opts;

    struct stat stat;

    if (lstat(path.c_str(), &stat)) {
        PLOG(ERROR) << "ERROR: failed to stat source " << path;
        return;
    }

    if (!S_ISDIR(stat.st_mode)) {
        PLOG(ERROR) << "ERROR: source is not a directory";
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

    // Custom logging for libfuse
    if (android::base::GetBoolProperty("persist.sys.fuse.log", false)) {
        fuse_set_log_func(fuse_logger);
    }

    uid_t userId = getuid() / PER_USER_RANGE;
    if (userId != 0 && mp.IsAppCloneUser(userId)) {
        // Disable dentry caching for the app clone user
        fuse->disable_dentry_cache = true;
    }

    struct fuse_session
            * se = fuse_session_new(&args, &ops, sizeof(ops), &fuse_default);
    if (!se) {
        PLOG(ERROR) << "Failed to create session ";
        return;
    }
    fuse_default.se = se;
    fuse_default.active = &active;
    se->fd = fd.release();  // libfuse owns the FD now
    se->mountpoint = strdup(path.c_str());

    // Single thread. Useful for debugging
    // fuse_session_loop(se);
    // Multi-threaded
    LOG(INFO) << "Starting fuse...";
    fuse_session_loop_mt(se, &config);
    fuse->active->store(false, std::memory_order_release);
    LOG(INFO) << "Ending fuse...";

    if (munmap(fuse_default.zero_addr, MAX_READ_SIZE)) {
        PLOG(ERROR) << "munmap failed!";
    }

    fuse_opt_free_args(&args);
    fuse_session_destroy(se);
    LOG(INFO) << "Ended fuse";
    return;
}
} //namespace fuse
}  // namespace mediaprovider
