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

#include <fuse_i.h>
#include <fuse_lowlevel.h>

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/fuse.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
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

#include <cutils/fs.h>
#include <cutils/log.h>
#include <cutils/multiuser.h>

#include <android-base/logging.h>
#include <private/android_filesystem_config.h>

#include <android-base/logging.h>

using namespace std;

#define FUSE_TRACE 1

#if FUSE_TRACE
#define TRACE(x...) ALOGD(x)
#else
#define TRACE(x...) \
    do {            \
    } while (0)
#endif

#define ERROR(x...) ALOGE(x)

#define FUSE_UNKNOWN_INO 0xffffffff

struct handle {
    int fd;
};

struct dirhandle {
    DIR* d;
    off_t next_off;
};

struct node {
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

/* Single FUSE mount */
struct fuse {
    pthread_mutex_t lock;
    string source_path;
    string dest_path;

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
};

static inline __u64 ptr_to_id(void* ptr) {
    return (__u64)(uintptr_t) ptr;
}

static void acquire_node_locked(struct node* node) {
    node->refcount++;
    TRACE("ACQUIRE %p (%s) rc=%d\n", node, node->name.c_str(), node->refcount);
}

static void remove_node_from_parent_locked(struct node* node);

static void release_node_n_locked(struct node* node, int count) {
    TRACE("RELEASE %p (%s) rc=%d\n", node, node->name.c_str(), node->refcount);
    if (node->refcount >= count) {
        node->refcount -= count;
        if (!node->refcount) {
            TRACE("DESTROY %p (%s)\n", node, node->name.c_str());
            remove_node_from_parent_locked(node);

            /* TODO: remove debugging - poison memory */
            memset(node, 0xfc, sizeof(*node));
            free(node);
        }
    } else {
        ERROR("Zero refcnt %p\n", node);
    }
}

static void release_node_locked(struct node* node) {
    TRACE("RELEASE %p (%s) rc=%d\n", node, node->name.c_str(), node->refcount);
    if (node->refcount > 0) {
        node->refcount--;
        if (!node->refcount) {
            TRACE("DESTROY %p (%s)\n", node, node->name.c_str());
            remove_node_from_parent_locked(node);

            /* TODO: remove debugging - poison memory */
            memset(node, 0xfc, sizeof(*node));
            free(node);
        }
    } else {
        ERROR("Zero refcnt %p\n", node);
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

/* Gets the absolute path to a node into the provided buffer.
 *
 * Populates 'buf' with the path and returns the length of the path on success,
 * or returns -1 if the path is too long for the provided buffer.
 */
static string get_node_path_locked(struct node* node) {
    string path;

    path.reserve(PATH_MAX);
    if (node->parent) get_node_path_locked_helper(node->parent, path);
    path += node->name;
    return path;
}

struct node* create_node_locked(struct fuse* fuse,
                                struct node* parent,
                                const string& name) {
    struct node* node;

    // Detect overflows in the inode counter. "4 billion nodes should be enough
    // for everybody".
    if (fuse->inode_ctr == 0) {
        ERROR("No more inode numbers available");
        return NULL;
    }

    node = (struct node*) calloc(1, sizeof(struct node));
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

    memset(e, 0, sizeof(e));
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

namespace mediaprovider {
namespace fuse {

/**
 * Function implementations
 *
 * These implement the various functions in fuse_lowlevel_ops
 *
 */

static void pf_init(void* userdata, struct fuse_conn_info* conn) {
    unsigned mask =
            (FUSE_CAP_SPLICE_WRITE | FUSE_CAP_SPLICE_MOVE | FUSE_CAP_SPLICE_READ
                    |
                            FUSE_CAP_ASYNC_READ | FUSE_CAP_ATOMIC_O_TRUNC
                    | FUSE_CAP_WRITEBACK_CACHE |
                    FUSE_CAP_EXPORT_SUPPORT | FUSE_CAP_FLOCK_LOCKS);
    conn->want |= conn->capable & mask;
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
    TRACE("[%s] LOOKUP %s @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, parent,
          parent_node ? parent_node->name.c_str() : "?");
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
    TRACE("[%s] FORGET #%"
                  PRIu64
                  " @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), nlookup, ino,
          node ? node->name.c_str() : "?");
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
    TRACE("[%s] GETATTR @ %"
                  PRIu64
                  " (%s)\n", fuse->dest_path.c_str(), ino,
          node ? node->name.c_str() : "?");
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
    TRACE("[%s] SETATTR  valid=%x @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), to_set, ino,
          node ? node->name.c_str() : "?");
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
        TRACE("[%s] Calling utimensat on %s with atime %ld, mtime=%ld\n",
              fuse->dest_path.c_str(),
              path.c_str(),
              times[0].tv_sec,
              times[1].tv_sec);
        if (utimensat(-1, path.c_str(), times, 0) < 0) {
            fuse_reply_err(req, errno);
            return;
        }
    }

    // attr_from_stat ??

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
    TRACE("[%s] MKNOD %s 0%o @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, mode, parent,
          parent_node ? parent_node->name.c_str() : "?");
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
    TRACE("[%s] MKDIR %s 0%o @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, mode, parent,
          parent_node ? parent_node->name.c_str() : "?");
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    mode = (mode & (~0777)) | 0775;
    if (mkdir(child_path.c_str(), mode) < 0) {
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
    TRACE("[%s] UNLINK %s @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, parent,
          parent_node ? parent_node->name.c_str() : "?");
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    if (unlink(child_path.c_str()) < 0) {
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
    TRACE("[%s] RMDIR %s @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, parent,
          parent_node ? parent_node->name.c_str() : "?");
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    if (rmdir(child_path.c_str()) < 0) {
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
    TRACE("[%s] RENAME %s->%s @ %"
                  PRIx64
                  " (%s) -> %"
                  PRIx64
                  " (%s)\n",
          fuse->dest_path.c_str(),
          name,
          newname,
          parent,
          old_parent_node ? old_parent_node->name.c_str() : "?",
          newparent,
          new_parent_node ? new_parent_node->name.c_str() : "?");
    if (!old_parent_node || !new_parent_node) {
        res = ENOENT;
        goto lookup_error;
    }

    child_node = lookup_child_by_name_locked(old_parent_node, name);
    old_child_path = get_node_path_locked(child_node);
    acquire_node_locked(child_node);
    pthread_mutex_unlock(&fuse->lock);

    new_child_path = new_parent_path + "/" + newname;

    TRACE("[%s] RENAME %s->%s\n",
          old_child_path.c_str(),
          fuse->dest_path.c_str(),
          new_child_path.c_str());
    res = rename(old_child_path.c_str(), new_child_path.c_str());
    if (res < 0) {
        res = errno;
        goto io_error;
    }

    pthread_mutex_lock(&fuse->lock);
    if (parent != newparent) {
        remove_node_from_parent_locked(child_node);
        child_node->name = newname;
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
    struct handle* h;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);
    TRACE("[%s] OPEN 0%o @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), fi->flags, ino,
          node ? node->name.c_str() : "?");
    pthread_mutex_unlock(&fuse->lock);

    if (!node) {
        fuse_reply_err(req, ENOENT);
        return;
    }

    h = new handle();
    if (!h) {
        fuse_reply_err(req, ENOMEM);
        return;
    }
    TRACE("[%s] OPEN %s\n", fuse->dest_path.c_str(), path.c_str());
    h->fd = open(path.c_str(), fi->flags);
    if (h->fd < 0) {
        delete h;
        fuse_reply_err(req, errno);
        return;
    }

    fi->fh = ptr_to_id(h);
    fuse_reply_open(req, fi);
}

static void pf_read(fuse_req_t req, fuse_ino_t ino, size_t size, off_t off,
                    struct fuse_file_info* fi) {
    LOG(INFO) << "pf_read";
    struct handle* h = reinterpret_cast<struct handle*>(fi->fh);
    struct fuse_bufvec buf = FUSE_BUFVEC_INIT(size);

    buf.buf[0].fd = h->fd;
    buf.buf[0].pos = off;
    buf.buf[0].flags =
            (enum fuse_buf_flags) (FUSE_BUF_IS_FD | FUSE_BUF_FD_SEEK);

    fuse_reply_data(req, &buf, (enum fuse_buf_copy_flags) 0);
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
    struct handle* h = reinterpret_cast<struct handle*>(fi->fh);
    struct fuse_bufvec buf = FUSE_BUFVEC_INIT(fuse_buf_size(bufv));
    ssize_t size;

    buf.buf[0].fd = h->fd;
    buf.buf[0].pos = off;
    buf.buf[0].flags =
            (enum fuse_buf_flags) (FUSE_BUF_IS_FD | FUSE_BUF_FD_SEEK);
    size = fuse_buf_copy(&buf, bufv, (enum fuse_buf_copy_flags) 0);

    if (size < 0)
        fuse_reply_err(req, -size);
    else
        fuse_reply_write(req, size);
}
// Haven't tested this one. Not sure what calls it.
#if 0
static void pf_copy_file_range(fuse_req_t req, fuse_ino_t ino_in,
                                 off_t off_in, struct fuse_file_info* fi_in,
                                 fuse_ino_t ino_out, off_t off_out,
                                 struct fuse_file_info* fi_out, size_t len,
                                 int flags)
{
    struct handle* h_in = reinterpret_cast<struct handle *>(fi_in->fh);
    struct handle* h_out = reinterpret_cast<struct handle *>(fi_out->fh);
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
    TRACE("[%s] FLUSH is noop\n", fuse->dest_path.c_str());
    fuse_reply_err(req, 0);
}

static void pf_release(fuse_req_t req,
                       fuse_ino_t ino,
                       struct fuse_file_info* fi) {
    struct fuse* fuse = get_fuse(req);
    struct handle* h = reinterpret_cast<struct handle*>(fi->fh);

    TRACE("[%s] RELEASE %p(%d)\n", fuse->dest_path.c_str(), h, h->fd);
    close(h->fd);
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
    struct handle* h = reinterpret_cast<struct handle*>(fi->fh);
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
    LOG(INFO) << "pf_opendir";
    struct fuse* fuse = get_fuse(req);
    const struct fuse_ctx* ctx = fuse_req_ctx(req);
    struct node* node;
    string path;
    struct dirhandle* h;

    pthread_mutex_lock(&fuse->lock);
    node = lookup_node_by_id_locked(fuse, ino);
    path = get_node_path_locked(node);
    TRACE("[%s] OPENDIR @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), ino,
          node ? node->name.c_str() : "?");
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
    TRACE("[%s] OPENDIR %s\n", fuse->dest_path.c_str(), path.c_str());
    h->d = opendir(path.c_str());
    if (!h->d) {
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
    size_t len = min(size, READDIR_BUF);
    char buf[READDIR_BUF];
    size_t used = 0;
    struct dirent* de;
    struct fuse_entry_param e;

    LOG(INFO) << "do_readdir_common";
    TRACE("[%s] READDIR %p\n", fuse->dest_path.c_str(), h);
    if (off != h->next_off) {
        TRACE("[%s] calling seekdir(%"
                      PRIu64
                      ")\n", fuse->dest_path.c_str(), off);
        seekdir(h->d, off);
    }
    while (used < len && (de = readdir(h->d)) != NULL) {
        off += 1;
        errno = 0;
        h->next_off = de->d_off;
        if (plus) {
            if (do_lookup(req, ino, de->d_name, &e))
                used += fuse_add_direntry_plus(req,
                                               buf + used,
                                               len - used,
                                               de->d_name,
                                               &e,
                                               de->d_off);
            else {
                fuse_reply_err(req, errno);
                return;
            }
        } else {
            e.attr.st_ino = FUSE_UNKNOWN_INO;
            e.attr.st_mode = de->d_type << 12;
            used += fuse_add_direntry(req,
                                      buf + used,
                                      len - used,
                                      de->d_name,
                                      &e.attr,
                                      de->d_off);
        }
    }

    if (!de && errno != 0)
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

    TRACE("[%s] RELEASEDIR %p\n", fuse->dest_path.c_str(), h);
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
}
*//*
static void pf_access(fuse_req_t req, fuse_ino_t ino, int mask)
{
    cout << "TODO:" << __func__;
}
*/
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
    struct handle* h;

    pthread_mutex_lock(&fuse->lock);
    parent_node = lookup_node_by_id_locked(fuse, parent);
    parent_path = get_node_path_locked(parent_node);
    TRACE("[%s] MKNOD %s 0%o @ %"
                  PRIx64
                  " (%s)\n", fuse->dest_path.c_str(), name, mode, parent,
          parent_node ? parent_node->name.c_str() : "?");
    pthread_mutex_unlock(&fuse->lock);

    child_path = parent_path + "/" + name;

    h = new handle();
    if (!h) {
        fuse_reply_err(req, ENOMEM);
        return;
    }
    mode = (mode & (~0777)) | 0664;
    h->fd = open(child_path.c_str(),
                 O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                 mode);
    if (h->fd < 0) {
        delete h;
        fuse_reply_err(req, errno);
        return;
    }

    fi->fh = ptr_to_id(h);
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
        .lookup = pf_lookup, .forget = pf_forget, .forget_multi = pf_forget_multi,
        .getattr = pf_getattr, .setattr = pf_setattr,
        /*.readlink = pf_readlink,*/
        .mknod = pf_mknod, .mkdir = pf_mkdir, .unlink = pf_unlink, .rmdir = pf_rmdir,
        /*.symlink = pf_symlink,*/
        .rename = pf_rename,
        /*.link = pf_link,*/
        .open = pf_open, .read = pf_read,
        /*.write = pf_write,*/
        .write_buf = pf_write_buf,
        /*.copy_file_range = pf_copy_file_range,*/
        .flush = pf_flush, .release = pf_release, .fsync = pf_fsync, .fsyncdir = pf_fsyncdir,
        .opendir = pf_opendir, .readdir = pf_readdir, .readdirplus = pf_readdirplus,
        .releasedir = pf_releasedir, .statfs = pf_statfs,
        /*.setxattr = pf_setxattr,
        .getxattr = pf_getxattr,
        .listxattr = pf_listxattr,
        .removexattr = pf_removexattr,
        .access = pf_access,*/
        .create = pf_create,
        /*.getlk = pf_getlk,
        .setlk = pf_setlk,
        .bmap = pf_bmap,
        .ioctl = pf_ioctl,
        .poll = pf_poll,
        .retrieve_reply = pf_retrieve_reply,*/
        /*.flock = pf_flock,
        .fallocate = pf_fallocate,*/
};

static struct fuse_loop_config config = {
        .clone_fd = 0,
        .max_idle_threads = 10,
};

FuseDaemon::FuseDaemon(JNIEnv* env, jobject mediaProvider) {
    //TODO: restore the next line when merging the MediaProviderWrapper code
    //g_mp = new MediaProviderWrapper(env, mediaProvider);
}

FuseDaemon::~FuseDaemon() {}

void FuseDaemon::Start(int fd) {
    struct fuse_args args;
    struct fuse_cmdline_opts opts;
    string source_path = "/data/media/";
    string dest_path = "/mnt/user/0/emulated";

    struct stat stat;
    auto ret = lstat(source_path.c_str(), &stat);

    if (ret == -1)
        PLOG(ERROR) << "ERROR: failed to stat source (\"%s\")" << source_path.c_str();

    if (!S_ISDIR(stat.st_mode))
        PLOG(ERROR) << "ERROR: source is not a directory";

    args = FUSE_ARGS_INIT(0, nullptr);
    if (fuse_opt_add_arg(&args, source_path.c_str())
            || fuse_opt_add_arg(&args, "-odebug")) {
        LOG(INFO) << "err";
        return;
    }

    struct fuse fuse_default;
    memset(&fuse_default, 0, sizeof(fuse_default));

    pthread_mutex_init(&fuse_default.lock, NULL);

    fuse_default.next_generation = 0;
    fuse_default.inode_ctr = 1;

    memset(&fuse_default.root, 0, sizeof(fuse_default.root));
    fuse_default.root.nid = FUSE_ROOT_ID; /* 1 */
    fuse_default.root.refcount = 2;
    fuse_default.root.name = source_path;

    fuse_default.source_path = source_path;

    fuse_default.dest_path = dest_path;

    umask(0);

    LOG(INFO) << "Starting fuse...\n";
    struct fuse_session
            * se = fuse_session_new(&args, &ops, sizeof(ops), &fuse_default);
    se->fd = fd;
    se->mountpoint = strdup(dest_path.c_str());
    //fuse_session_mount(se, fuse_default.dest_path.c_str());
    fuse_session_loop_mt(se, &config);
    //fuse_session_unmount(se);
    LOG(INFO) << "Fuse is done...\n";
    return;
}
} //namespace fuse
} //namespace mediaprovider