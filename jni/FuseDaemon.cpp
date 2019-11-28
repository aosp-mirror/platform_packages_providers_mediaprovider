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

#define LOG_TAG "FuseDaemon"

#include "FuseDaemon.h"

#include <android-base/logging.h>
#include <android/log.h>
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
#include <pthread.h>
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
#include <map>
#include <queue>
#include <thread>
#include <unordered_map>
#include <vector>

#include "MediaProviderWrapper.h"
#include "libfuse_jni/ReaddirHelper.h"
#include "libfuse_jni/RedactionInfo.h"

using mediaprovider::fuse::DirectoryEntry;
using mediaprovider::fuse::RedactionInfo;
using std::string;
using std::vector;

// logging macros to avoid duplication.
#define TRACE LOG(DEBUG)
#define TRACE_FUSE(__fuse) TRACE << "[" << __fuse->path << "] "

#define FUSE_UNKNOWN_INO 0xffffffff

constexpr size_t MAX_READ_SIZE = 128 * 1024;

class handle {
  public:
    handle(const string& path) : path(path), fd(-1), ri(nullptr){};
    string path;
    int fd;
    std::unique_ptr<RedactionInfo> ri;
};

struct dirhandle {
    DIR* d;
    off_t next_off;
    // Fuse readdir() is called multiple times based on the size of the buffer and
    // number of directory entries in the given directory. 'de' holds the list
    // of directory entries for the directory handle and this list is available
    // across subsequent readdir() calls for the same directory handle.
    std::vector<std::shared_ptr<DirectoryEntry>> de;
};

struct node {
    node() : refcount(0), nid(0), gen(0), ino(0), next(0), child(0), parent(0), deleted(false) {}

    __u32 refcount;
    __u64 nid;
    __u64 gen;
    /*
     * The inode number for this FUSE node. Note that this isn't stable across
     * multiple invocations of the FUSE daemon.
     */
    __u32 ino;

    struct node* next;   /* per-dir sibling list */
    struct node* child;  /* first contained file by this dir */
    struct node* parent; /* containing directory */

    string name;

    bool deleted;
};

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

/* Single FUSE mount */
struct fuse {
    fuse() : next_generation(0), inode_ctr(0), mp(0), zero_addr(0) {}

    pthread_mutex_t lock;
    string path;

    __u64 next_generation;
    struct node root;

    /* Used to allocate unique inode numbers for fuse nodes. We use
     * a simple counter based scheme where inode numbers from deleted
     * nodes aren't reused. Note that inode allocations are not stable
     * across multiple invocation of the sdcard daemon, but that shouldn't
     * be a huge problem in practice.
     *
     * Note that we restrict inodes to 32 bit unsigned integers to prevent
     * truncation on 32 bit processes when unsigned long long stat.st_ino is
     * assigned to an unsigned long ino_t type in an LP32 process.
     *
     * Also note that fuse_attr and fuse_dirent inode values are 64 bits wide
     * on both LP32 and LP64, but the fuse kernel code doesn't squash 64 bit
     * inode numbers into 32 bit values on 64 bit kernels (see fuse_squash_ino
     * in fs/fuse/inode.c).
     *
     * Accesses must be guarded by |lock|.
     */
    __u32 inode_ctr;

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
    char* zero_addr;

    FAdviser fadviser;
};

static inline const char* safe_name(struct node* n) {
    return n ? n->name.c_str() : "?";
}

static inline __u64 ptr_to_id(void* ptr) {
    return (__u64)(uintptr_t) ptr;
}

static void acquire_node_locked(struct node* node) {
    node->refcount++;
    TRACE << "ACQUIRE " << node << " " << node->name << " rc=" << node->refcount;
}

static void remove_node_from_parent_locked(struct node* node);

static void release_node_n_locked(struct node* node, int count) {
    TRACE << "RELEASE " << node << " " << node->name << " rc=" << node->refcount;
    if (node->refcount >= count) {
        node->refcount -= count;
        if (!node->refcount) {
            TRACE << "DESTROY " << node << " (" << node->name << ")";
            remove_node_from_parent_locked(node);
            delete (node);
        }
    } else {
        LOG(ERROR) << "Zero refcnt " << node;
    }
}

static void release_node_locked(struct node* node) {
    TRACE << "RELEASE " << node << " " << node->name << " rc=" << node->refcount;
    if (node->refcount > 0) {
        node->refcount--;
        if (!node->refcount) {
            TRACE << "DESTROY " << node << " (" << node->name << ")";
            remove_node_from_parent_locked(node);
            delete (node);
        }
    } else {
        LOG(ERROR) << "Zero refcnt " << node;
    }
}

static void add_node_to_parent_locked(struct node* node, struct node* parent) {
    node->parent = parent;
    node->next = parent->child;
    parent->child = node;
    acquire_node_locked(parent);
}

static void remove_node_from_parent_locked(struct node* node) {
    if (node->parent) {
        if (node->parent->child == node) {
            node->parent->child = node->parent->child->next;
        } else {
            struct node* node2;
            node2 = node->parent->child;
            while (node2->next != node) node2 = node2->next;
            node2->next = node->next;
        }
        release_node_locked(node->parent);
        node->parent = NULL;
        node->next = NULL;
    }
}

static void get_node_path_locked_helper(struct node* node, string& path) {
    if (node->parent) get_node_path_locked_helper(node->parent, path);
    path += node->name + "/";
}

/*
 * Gets the absolute path to a node into the provided buffer.
 */
static string get_node_path_locked(struct node* node) {
    string path;

    path.reserve(PATH_MAX);
    if (node->parent) get_node_path_locked_helper(node->parent, path);
    path += node->name;
    return path;
}

static void get_node_relative_path_locked_helper(const struct node& node, string* path) {
    if (node.nid == FUSE_ROOT_ID) {
        return;
    } else if (node.parent && node.parent->nid == FUSE_ROOT_ID) {
        *path += "/";
    } else {
        if (node.parent) get_node_relative_path_locked_helper(*node.parent, path);
        *path += node.name + "/";
    }
}

/* Gets the relative path of the given node.
 *
 * Relative path is path of the node in terms /storage/emulated/<userid>/. An empty string is
 * returned for paths beyond /storage/emulated/<userid>/ for example /storage/emulated/ or
 * /storage/emulated/obb.
 * TODO(b/142806973): Remove this when this functionality will be handled by
 * MediaProvider.
 */
static string get_node_relative_path_locked(const struct node& node) {
    string path;

    path.reserve(PATH_MAX);
    get_node_relative_path_locked_helper(node, &path);
    if (path.size() > 1) path.erase(path.size() - 1);
    return path;
}

struct node* create_node_locked(struct fuse* fuse,
                                struct node* parent,
                                const string& name) {
    struct node* node;

    // Detect overflows in the inode counter. "4 billion nodes should be enough
    // for everybody".
    if (fuse->inode_ctr == 0) {
        LOG(ERROR) << "No more inode numbers available";
        return NULL;
    }

    node = new ::node();
    if (!node) {
        return NULL;
    }
    node->name = name;
    node->nid = ptr_to_id(node);
    node->ino = fuse->inode_ctr++;
    node->gen = fuse->next_generation++;

    node->deleted = false;

    acquire_node_locked(node);
    add_node_to_parent_locked(node, parent);
    return node;
}

static struct node* lookup_node_by_id_locked(struct fuse* fuse, __u64 nid) {
    if (nid == FUSE_ROOT_ID) {
        return &fuse->root;
    } else {
        return reinterpret_cast<struct node*>(nid);
    }
}

static struct node* lookup_child_by_name_locked(struct node* node,
                                                const string& name) {
    for (node = node->child; node; node = node->next) {
        /* use exact string comparison, nodes that differ by case
         * must be considered distinct even if they refer to the same
         * underlying file as otherwise operations such as "mv x x"
         * will not work because the source and target nodes are the same. */

        if ((name == node->name) && !node->deleted) {
            return node;
        }
    }
    return 0;
}

static struct node* acquire_or_create_child_locked(struct fuse* fuse,
                                                   struct node* parent,
                                                   const string& name) {
    struct node* child = lookup_child_by_name_locked(parent, name);
    if (child) {
        acquire_node_locked(child);
    } else {
        child = create_node_locked(fuse, parent, name);
    }
    return child;
}

static struct fuse* get_fuse(fuse_req_t req) {
    return reinterpret_cast<struct fuse*>(fuse_req_userdata(req));
}

static struct node* make_node_entry(fuse_req_t req,
                                    struct node* parent,
                                    const string& name,
                                    const string& path,
                                    struct fuse_entry_param* e) {
    struct fuse* fuse = get_fuse(req);
    struct node* node;

    memset(e, 0, sizeof(*e));
    if (lstat(path.c_str(), &e->attr) < 0) {
        return NULL;
    }

    pthread_mutex_lock(&fuse->lock);
    node = acquire_or_create_child_locked(fuse, parent, name);
    if (!node) {
        pthread_mutex_unlock(&fuse->lock);
        errno = ENOMEM;
        return NULL;
    }

    // Manipulate attr here if needed

    e->attr_timeout = 10;
    e->entry_timeout = 10;
    e->ino = node->nid;
    e->generation = node->gen;
    pthread_mutex_unlock(&fuse->lock);

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

/*
static void pf_destroy(void* userdata)
{
    cout << "TODO:" << __func__;
}
*/

static struct node* do_lookup(fuse_req_t req,
                              fuse_ino_t parent,
                              const char* name,
                              struct fuse_entry_param* e) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* parent_node;
    string parent_path;
    string child_path;

    errno = 0;
    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "LOOKUP " << name << " @ " << parent << " (" << safe_name(parent_node)
                     << ")";
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    return make_node_entry(req, parent_node, name, child_path, e);
}

static void pf_lookup(fuse_req_t req, fuse_ino_t parent, const char* name) {
    struct fuse_entry_param e;

    if (do_lookup(req, parent, name, &e))
        fuse_reply_entry(req, &e);
    else
        fuse_reply_err(req, errno);
}

static void do_forget(struct fuse* fuse, fuse_ino_t ino, uint64_t nlookup) {
    struct node* node = lookup_node_by_id_locked(fuse, ino);
    TRACE_FUSE(fuse) << "FORGET #" << nlookup << " @ " << ino << " (" << safe_name(node) << ")";
    if (node) {
        __u64 n = nlookup;
        release_node_n_locked(node, n);
    }
}

static void pf_forget(fuse_req_t req, fuse_ino_t ino, uint64_t nlookup) {
    struct node* node;
    struct fuse* fuse = get_fuse(req);

    pthread_mutex_lock(&fuse->lock);
    do_forget(fuse, ino, nlookup);
    pthread_mutex_unlock(&fuse->lock);
    fuse_reply_none(req);
}

static void pf_forget_multi(fuse_req_t req,
                            size_t count,
                            struct fuse_forget_data* forgets) {
    struct fuse* fuse = get_fuse(req);

    pthread_mutex_lock(&fuse->lock);
    for (int i = 0; i < count; i++)
        do_forget(fuse,
                  forgets[i].ino,
                  forgets[i].nlookup);
    pthread_mutex_unlock(&fuse->lock);
    fuse_reply_none(req);
}

static void pf_getattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* node;
    string path;
    struct stat s;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);
    TRACE_FUSE(fuse) << "GETATTR @ " << ino << " (" << safe_name(node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    if (!node) fuse_reply_err(req, ENOENT);

    memset(&s, 0, sizeof(s));
    if (lstat(path.c_str(), &s) < 0) {
        fuse_reply_err(req, errno);
    } else {
        fuse_reply_attr(req, &s, 10);
    }
}

static void pf_setattr(fuse_req_t req,
                       fuse_ino_t ino,
                       struct stat* attr,
                       int to_set,
                       struct fuse_file_info* fi) {
    struct node* node;
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    string path;
    struct timespec times[2];
    struct stat* newattr;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);
    TRACE_FUSE(fuse) << "SETATTR valid=" << to_set << " @ " << ino << "(" << safe_name(node) << ")";
    pthread_mutex_unlock(&fuse->lock);

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
    fuse_reply_attr(req, attr, 10);
}
/*
static void pf_readlink(fuse_req_t req, fuse_ino_t ino)
{
    cout << "TODO:" << __func__;
}
*/
static void pf_mknod(fuse_req_t req,
                     fuse_ino_t parent,
                     const char* name,
                     mode_t mode,
                     dev_t rdev) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* parent_node;
    string parent_path;
    string child_path;
    struct fuse_entry_param e;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "MKNOD " << name << " 0" << std::oct << mode << " @ " << parent << " ("
                     << safe_name(parent_node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    if (!parent_node) {
        fuse_reply_err(req, ENOENT);
        return;
    }
    child_path = parent_path + "/" + name;

    mode = (mode & (~0777)) | 0664;
    if (mknod(child_path.c_str(), mode, rdev) < 0) {
        fuse_reply_err(req, errno);
        return;
    }
    if (make_node_entry(req, parent_node, name, child_path, &e))
        fuse_reply_entry(req, &e);
    else
        fuse_reply_err(req, errno);
}

static void pf_mkdir(fuse_req_t req,
                     fuse_ino_t parent,
                     const char* name,
                     mode_t mode) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* parent_node;
    string parent_path;
    string child_path;
    struct fuse_entry_param e;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "MKDIR " << name << " 0" << std::oct << mode << " @ " << parent << " ("
                     << safe_name(parent_node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    errno = -fuse->mp->IsCreatingDirAllowed(child_path, ctx->uid);
    mode = (mode & (~0777)) | 0775;
    if (errno || mkdir(child_path.c_str(), mode) < 0) {
        fuse_reply_err(req, errno);
        return;
    }

    if (make_node_entry(req, parent_node, name, child_path, &e))
        fuse_reply_entry(req, &e);
    else
        fuse_reply_err(req, errno);
}

static void pf_unlink(fuse_req_t req, fuse_ino_t parent, const char* name) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* parent_node;
    struct node* child_node;
    string parent_path;
    string child_path;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "UNLINK " << name << " @ " << parent << "(" << safe_name(parent_node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    errno = -fuse->mp->DeleteFile(child_path, ctx->uid);
    if (errno) {
        fuse_reply_err(req, errno);
        return;
    }
    pthread_mutex_lock(&fuse->lock);
    child_node = lookup_child_by_name_locked(parent_node, name);
    if (child_node) {
        child_node->deleted = true;
    }
    pthread_mutex_unlock(&fuse->lock);

    fuse_reply_err(req, 0);
}

static void pf_rmdir(fuse_req_t req, fuse_ino_t parent, const char* name) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* child_node;
    struct node* parent_node;
    string parent_path;
    string child_path;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "RMDIR " << name << " @ " << parent << "(" << safe_name(parent_node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    errno = -fuse->mp->IsDeletingDirAllowed(child_path, ctx->uid);
    if (errno || rmdir(child_path.c_str()) < 0) {
        fuse_reply_err(req, errno);
        return;
    }
    pthread_mutex_lock(&fuse->lock);
    child_node = lookup_child_by_name_locked(parent_node, name);
    if (child_node) {
        child_node->deleted = true;
    }
    pthread_mutex_unlock(&fuse->lock);

    fuse_reply_err(req, 0);
}
/*
static void pf_symlink(fuse_req_t req, const char* link, fuse_ino_t parent,
                         const char* name)
{
    cout << "TODO:" << __func__;
}
*/
static void pf_rename(fuse_req_t req,
                      fuse_ino_t parent,
                      const char* name,
                      fuse_ino_t newparent,
                      const char* newname,
                      unsigned int flags) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* old_parent_node;
    struct node* new_parent_node;
    struct node* child_node;
    string old_parent_path;
    string new_parent_path;
    string old_child_path;
    string new_child_path;
    int res, search;

    pthread_mutex_lock(&fuse->lock);
    old_parent_node = lookup_node_by_id_locked(fuse, parent);
    old_parent_path = get_node_path_locked(old_parent_node);
    new_parent_node = lookup_node_by_id_locked(fuse, newparent);
    new_parent_path = get_node_path_locked(new_parent_node);
    TRACE_FUSE(fuse) << "RENAME " << name << " -> " << newname << " @ " << parent << " ("
                     << safe_name(old_parent_node) << ") -> " << newparent << " ("
                     << safe_name(new_parent_node) << ")";
    if (!old_parent_node || !new_parent_node) {
        res = ENOENT;
        goto lookup_error;
    }

    child_node = lookup_child_by_name_locked(old_parent_node, name);
    old_child_path = get_node_path_locked(child_node);
    acquire_node_locked(child_node);
    pthread_mutex_unlock(&fuse->lock);

    new_child_path = new_parent_path + "/" + newname;

    TRACE_FUSE(fuse) << "RENAME " << old_child_path << " -> " << new_child_path;
    res = rename(old_child_path.c_str(), new_child_path.c_str());
    if (res < 0) {
        res = errno;
        goto io_error;
    }

    pthread_mutex_lock(&fuse->lock);
    child_node->name = newname;
    if (parent != newparent) {
        remove_node_from_parent_locked(child_node);
        // do any location based fixups here
        add_node_to_parent_locked(child_node, new_parent_node);
    }
    goto done;

    io_error:
    pthread_mutex_lock(&fuse->lock);
    done:
    release_node_locked(child_node);
    lookup_error:
    pthread_mutex_unlock(&fuse->lock);
    fuse_reply_err(req, res);
}
/*
static void pf_link(fuse_req_t req, fuse_ino_t ino, fuse_ino_t newparent,
                      const char* newname)
{
    cout << "TODO:" << __func__;
}
*/

static void pf_open(fuse_req_t req, fuse_ino_t ino, struct fuse_file_info* fi) {
    struct node* node;
    string path;
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct fuse_open_out out;
    handle* h;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);
    TRACE_FUSE(fuse) << "OPEN 0" << std::oct << fi->flags << " @ " << ino << " (" << safe_name(node)
                     << ")";
    pthread_mutex_unlock(&fuse->lock);

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    h = new handle(path);
    if (!h) {
        fuse_reply_err(req, ENOMEM);
        return;
    }

    if (fi->flags & O_DIRECT) {
        fi->flags &= ~O_DIRECT;
        fi->direct_io = true;
    }

    TRACE_FUSE(fuse) << "OPEN " << path;
    errno = -fuse->mp->IsOpenAllowed(h->path, ctx->uid, is_requesting_write(fi->flags));
    if (errno || (h->fd = open(path.c_str(), fi->flags)) < 0) {
        delete h;
        fuse_reply_err(req, errno);
        return;
    }

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
    handle* h = reinterpret_cast<handle*>(fi->fh);
    struct fuse* fuse = get_fuse(req);
    TRACE_FUSE(fuse) << "READ";
    if (!h->ri) {
        h->ri = fuse->mp->GetRedactionInfo(h->path, req->ctx.uid);

        if (!h->ri) {
            errno = EIO;
            fuse_reply_err(req, errno);
            return;
        }
    }

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
    struct fuse* fuse = get_fuse(req);
    TRACE_FUSE(fuse) << "FLUSH is a noop";
    fuse_reply_err(req, 0);
}

static void pf_release(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    handle* h = reinterpret_cast<handle*>(fi->fh);

    TRACE_FUSE(fuse) << "RELEASE "
                     << "0" << std::oct << fi->flags << " " << h << "(" << h->fd << ")";

    fuse->fadviser.Close(h->fd);
    close(h->fd);
    if (is_requesting_write(fi->flags)) {
        fuse->mp->ScanFile(h->path);
    }
    delete h;
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
    handle* h = reinterpret_cast<handle*>(fi->fh);
    int err = do_sync_common(h->fd, datasync);

    fuse_reply_err(req, err);
}

static void pf_fsyncdir(fuse_req_t req,
                        fuse_ino_t ino,
                        int datasync,
                        struct fuse_file_info* fi) {
    struct dirhandle* h = reinterpret_cast<struct dirhandle*>(fi->fh);
    int err = do_sync_common(dirfd(h->d), datasync);

    fuse_reply_err(req, err);
}

static void pf_opendir(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* node;
    string path;
    struct dirhandle* h;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);

    TRACE_FUSE(fuse) << "OPENDIR @ " << ino << " (" << safe_name(node) << ")" << path;
    pthread_mutex_unlock(&fuse->lock);

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    h = new dirhandle();
    if (!h) {
        fuse_reply_err(req, ENOMEM);
        return;
    }

    errno = -fuse->mp->IsOpendirAllowed(path, ctx->uid);
    if (errno || !(h->d = opendir(path.c_str()))) {
        delete h;
        fuse_reply_err(req, errno);
        return;
    }
    h->next_off = 0;
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
    struct dirhandle* h = reinterpret_cast<struct dirhandle*>(fi->fh);
    size_t len = std::min<size_t>(size, READDIR_BUF);
    char buf[READDIR_BUF];
    size_t used = 0;
    std::shared_ptr<DirectoryEntry> de;

    struct fuse_entry_param e;
    size_t entry_size = 0;

    pthread_mutex_lock(&fuse->lock);
    struct node* node = lookup_node_by_id_locked(fuse, ino);
    string path = get_node_path_locked(node);
    pthread_mutex_unlock(&fuse->lock);
    TRACE_FUSE(fuse) << "READDIR @" << ino << " " << path << " at offset " << off;
    // Get all directory entries from MediaProvider on first readdir() call of
    // directory handle. h->next_off = 0 indicates that current readdir() call
    // is first readdir() call for the directory handle, Avoid multiple JNI calls
    // for single directory handle.
    if (h->next_off == 0) {
        pthread_mutex_lock(&fuse->lock);
        node = lookup_node_by_id_locked(fuse, ino);
        string relative_path = get_node_relative_path_locked(*node);
        pthread_mutex_unlock(&fuse->lock);
        // TODO(b/142806973): Move this check to MediaProvider.
        if (!IsDirectoryEntryFilteringNeeded(relative_path))
            h->de = getDirectoryEntriesFromLowerFs(h->d);
        else
            h->de = fuse->mp->GetDirectoryEntries(req->ctx.uid, relative_path, h->d);
    }
    // If the last entry in the previous readdir() call was rejected due to
    // buffer capacity constraints, update directory offset to start from
    // previously rejected entry. Directory offset can also change if there was
    // a seekdir on the given directory handle.
    if (off != h->next_off) {
        h->next_off = off;
    }
    const int num_directory_entries = h->de.size();

    while (h->next_off < num_directory_entries) {
        de = h->de[h->next_off];
        errno = 0;
        entry_size = 0;
        h->next_off++;
        if (plus) {
            if (do_lookup(req, ino, de->d_name.c_str(), &e))
                entry_size = fuse_add_direntry_plus(req, buf + used, len - used, de->d_name.c_str(),
                                                    &e, h->next_off);
            else {
                fuse_reply_err(req, errno);
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
        if (used + entry_size > len) break;
        used += entry_size;
    }

    if (errno)
        fuse_reply_err(req, errno);
    else
        fuse_reply_buf(req, buf, used);
}

static void pf_readdir(fuse_req_t req, fuse_ino_t ino, size_t size, off_t off,
                       struct fuse_file_info* fi) {
    do_readdir_common(req, ino, size, off, fi, false);
}

static void pf_readdirplus(fuse_req_t req,
                           fuse_ino_t ino,
                           size_t size,
                           off_t off,
                           struct fuse_file_info* fi) {
    do_readdir_common(req, ino, size, off, fi, true);
}

static void pf_releasedir(fuse_req_t req,
                          fuse_ino_t ino,
                          struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    struct dirhandle* h = reinterpret_cast<struct dirhandle*>(fi->fh);

    TRACE_FUSE(fuse) << "RELEASEDIR " << h;
    closedir(h->d);
    delete h;
    fuse_reply_err(req, 0);
}

static void pf_statfs(fuse_req_t req, fuse_ino_t ino) {
    struct statvfs st;
    struct fuse* fuse = get_fuse(req);

    if (statvfs(fuse->root.name.c_str(), &st))
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
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);

    pthread_mutex_lock(&fuse->lock);
    struct node* node = lookup_node_by_id_locked(fuse, ino);
    string path = get_node_path_locked(node);
    TRACE_FUSE(fuse) << "ACCESS " << path;
    pthread_mutex_unlock(&fuse->lock);

    int res = access(path.c_str(), F_OK);
    fuse_reply_err(req, res ? errno : 0);
}

static void pf_create(fuse_req_t req,
                      fuse_ino_t parent,
                      const char* name,
                      mode_t mode,
                      struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* parent_node;
    string parent_path;
    string child_path;
    struct fuse_entry_param e;
    handle* h;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE_FUSE(fuse) << "CREATE " << name << " 0" << std::oct << fi->flags << " @ " << parent
                     << " (" << safe_name(parent_node) << ")";
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    h = new handle(child_path);
    if (!h) {
        fuse_reply_err(req, ENOMEM);
        return;
    }
    mode = (mode & (~0777)) | 0664;
    int mp_return_code = fuse->mp->InsertFile(child_path.c_str(), ctx->uid);
    if (mp_return_code || ((h->fd = open(child_path.c_str(), fi->flags, mode)) < 0)) {
        if (mp_return_code) {
            errno = -mp_return_code;
            // In this case, we know open was not called.
        } else {
            // In this case, we know that open has failed, so we want to undo the file insertion.
            fuse->mp->DeleteFile(child_path.c_str(), ctx->uid);
        }
        delete h;
        PLOG(DEBUG) << "Could not create file: " << child_path;
        fuse_reply_err(req, errno);
        return;
    }

    fi->fh = ptr_to_id(h);
    fi->keep_cache = 1;
    if (make_node_entry(req, parent_node, name, child_path, &e)) {
        fuse_reply_create(req, &e, fi);
    } else {
        fuse_reply_err(req, errno);
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
    /*.destroy = pf_destroy,*/
    .lookup = pf_lookup, .forget = pf_forget, .getattr = pf_getattr,
    .setattr = pf_setattr,
    /*.readlink = pf_readlink,*/
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
        .clone_fd = 0,
        .max_idle_threads = 10,
};

static std::unordered_map<enum fuse_log_level, enum android_LogPriority> fuse_to_android_loglevel;

static void fuse_logger(enum fuse_log_level level, const char* fmt, va_list ap) {
    __android_log_vprint(fuse_to_android_loglevel.at(level), LOG_TAG, fmt, ap);
}

FuseDaemon::FuseDaemon(JNIEnv* env, jobject mediaProvider) : mp(env, mediaProvider) {}

void FuseDaemon::Start(const int fd, const std::string& path) {
    struct fuse_args args;
    struct fuse_cmdline_opts opts;

    SetMinimumLogSeverity(android::base::DEBUG);

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

    struct fuse fuse_default;
    pthread_mutex_init(&fuse_default.lock, NULL);
    fuse_default.next_generation = 0;
    fuse_default.inode_ctr = 1;
    fuse_default.root.nid = FUSE_ROOT_ID; /* 1 */
    fuse_default.root.refcount = 2;
    fuse_default.root.name = path;
    fuse_default.path = path;
    fuse_default.mp = &mp;

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
    fuse_to_android_loglevel.insert({FUSE_LOG_EMERG, ANDROID_LOG_FATAL});
    fuse_to_android_loglevel.insert({FUSE_LOG_ALERT, ANDROID_LOG_ERROR});
    fuse_to_android_loglevel.insert({FUSE_LOG_CRIT, ANDROID_LOG_ERROR});
    fuse_to_android_loglevel.insert({FUSE_LOG_WARNING, ANDROID_LOG_WARN});
    fuse_to_android_loglevel.insert({FUSE_LOG_NOTICE, ANDROID_LOG_INFO});
    fuse_to_android_loglevel.insert({FUSE_LOG_INFO, ANDROID_LOG_DEBUG});
    fuse_to_android_loglevel.insert({FUSE_LOG_DEBUG, ANDROID_LOG_VERBOSE});
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
    fuse_session_loop_mt(se, &config);
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
