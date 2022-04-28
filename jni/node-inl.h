/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * See the License for the specic language governing permissions and
 * limitations under the License.
 */

#ifndef MEDIA_PROVIDER_JNI_NODE_INL_H_
#define MEDIA_PROVIDER_JNI_NODE_INL_H_

#include <android-base/logging.h>

#include <sys/types.h>
#include <atomic>
#include <cstdint>
#include <limits>
#include <list>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

#include "libfuse_jni/ReaddirHelper.h"
#include "libfuse_jni/RedactionInfo.h"

class NodeTest;

namespace mediaprovider {
namespace fuse {

struct handle {
    explicit handle(int fd, const RedactionInfo* ri, bool cached, bool passthrough, uid_t uid,
                    uid_t transforms_uid)
        : fd(fd),
          ri(ri),
          cached(cached),
          passthrough(passthrough),
          uid(uid),
          transforms_uid(transforms_uid) {
        CHECK(ri != nullptr);
    }

    const int fd;
    const std::unique_ptr<const RedactionInfo> ri;
    const bool cached;
    const bool passthrough;
    const uid_t uid;
    const uid_t transforms_uid;

    ~handle() { close(fd); }
};

struct dirhandle {
    explicit dirhandle(DIR* dir) : d(dir), next_off(0) { CHECK(dir != nullptr); }

    DIR* const d;
    off_t next_off;
    // Fuse readdir() is called multiple times based on the size of the buffer and
    // number of directory entries in the given directory. 'de' holds the list
    // of directory entries for the directory handle and this list is available
    // across subsequent readdir() calls for the same directory handle.
    std::vector<std::shared_ptr<DirectoryEntry>> de;

    ~dirhandle() { closedir(d); }
};

/** Represents file open result from MediaProvider */
struct FdAccessResult {
    FdAccessResult(const std::string& file_path, const bool should_redact)
        : file_path(file_path), should_redact(should_redact) {}

    const std::string file_path;
    const bool should_redact;
};

// Whether inode tracking is enabled or not. When enabled, we maintain a
// separate mapping from inode numbers to "live" nodes so we can detect when
// we receive a request to a node that has been deleted.
static constexpr bool kEnableInodeTracking = true;

class node;

// Tracks the set of active nodes associated with a FUSE instance so that we
// can assert that we only ever return an active node in response to a lookup.
class NodeTracker {
  public:
    explicit NodeTracker(std::recursive_mutex* lock) : lock_(lock) {}

    bool Exists(__u64 ino) const {
        if (kEnableInodeTracking) {
            const node* node = reinterpret_cast<const class node*>(ino);
            std::lock_guard<std::recursive_mutex> guard(*lock_);
            return active_nodes_.find(node) != active_nodes_.end();
        }
    }

    void CheckTracked(__u64 ino) const {
        if (kEnableInodeTracking) {
            const node* node = reinterpret_cast<const class node*>(ino);
            std::lock_guard<std::recursive_mutex> guard(*lock_);
            CHECK(active_nodes_.find(node) != active_nodes_.end());
        }
    }

    void NodeDeleted(const node* node) {
        if (kEnableInodeTracking) {
            std::lock_guard<std::recursive_mutex> guard(*lock_);
            LOG(DEBUG) << "Node: " << reinterpret_cast<uintptr_t>(node) << " deleted.";

            CHECK(active_nodes_.find(node) != active_nodes_.end());
            active_nodes_.erase(node);
        }
    }

    void NodeCreated(const node* node) {
        if (kEnableInodeTracking) {
            std::lock_guard<std::recursive_mutex> guard(*lock_);
            LOG(DEBUG) << "Node: " << reinterpret_cast<uintptr_t>(node) << " created.";

            CHECK(active_nodes_.find(node) == active_nodes_.end());
            active_nodes_.insert(node);
        }
    }

  private:
    std::recursive_mutex* lock_;
    std::unordered_set<const node*> active_nodes_;
};

class node {
  public:
    // Creates a new node with the specified parent, name and lock.
    static node* Create(node* parent, const std::string& name, const std::string& io_path,
                        const bool transforms_complete, const int transforms,
                        const int transforms_reason, std::recursive_mutex* lock, ino_t ino,
                        NodeTracker* tracker) {
        // Place the entire constructor under a critical section to make sure
        // node creation, tracking (if enabled) and the addition to a parent are
        // atomic.
        std::lock_guard<std::recursive_mutex> guard(*lock);
        return new node(parent, name, io_path, transforms_complete, transforms, transforms_reason,
                        lock, ino, tracker);
    }

    // Creates a new root node. Root nodes have no parents by definition
    // and their "name" must signify an absolute path.
    static node* CreateRoot(const std::string& path, std::recursive_mutex* lock, ino_t ino,
                            NodeTracker* tracker) {
        std::lock_guard<std::recursive_mutex> guard(*lock);
        node* root = new node(nullptr, path, path, true /* transforms_complete */,
                              0 /* transforms */, 0 /* transforms_reason */, lock, ino, tracker);

        // The root always has one extra reference to avoid it being
        // accidentally collected.
        root->Acquire();
        return root;
    }

    // Maps an inode to its associated node.
    static inline node* FromInode(__u64 ino, const NodeTracker* tracker) {
        tracker->CheckTracked(ino);
        return reinterpret_cast<node*>(static_cast<uintptr_t>(ino));
    }

    // TODO(b/215235604)
    static inline node* FromInodeNoThrow(__u64 ino, const NodeTracker* tracker) {
        if (!tracker->Exists(ino)) return nullptr;
        return reinterpret_cast<node*>(static_cast<uintptr_t>(ino));
    }

    // Maps a node to its associated inode.
    static __u64 ToInode(node* node) {
        return static_cast<__u64>(reinterpret_cast<uintptr_t>(node));
    }

    // Releases a reference to a node. Returns true iff the refcount dropped to
    // zero as a result of this call to Release, meaning that it's no longer
    // safe to perform any operations on references to this node.
    bool Release(uint32_t count) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        if (refcount_ >= count) {
            refcount_ -= count;
            if (refcount_ == 0) {
                delete this;
                return true;
            }
        } else {
            LOG(ERROR) << "Mismatched reference count: refcount_ = " << this->refcount_
                       << " ,count = " << count;
        }

        return false;
    }

    // Builds the full path associated with this node, including all path segments
    // associated with its descendants.
    std::string BuildPath() const;

    // Builds the full PII safe path associated with this node, including all path segments
    // associated with its descendants.
    std::string BuildSafePath() const;

    // Looks up a direct descendant of this node by case-insensitive |name|. If |acquire| is true,
    // also Acquire the node before returning a reference to it.
    // |transforms| is an opaque flag that is used to distinguish multiple nodes sharing the same
    // |name| but requiring different IO transformations as determined by the MediaProvider.
    node* LookupChildByName(const std::string& name, bool acquire, const int transforms = 0) const {
        return ForChild(name, [acquire, transforms](node* child) {
            if (child->transforms_ == transforms) {
                if (acquire) {
                    child->Acquire();
                }
                return true;
            }
            return false;
        });
    }

    // Marks this node children as deleted. They are still associated with their parent, and
    // all open handles etc. to the deleted nodes are preserved until their refcount goes
    // to zero.
    void SetDeletedForChild(const std::string& name) {
        ForChild(name, [](node* child) {
            child->SetDeleted();
            return false;
        });
    }

    void SetDeleted() {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        deleted_ = true;
    }

    void RenameChild(const std::string& old_name, const std::string& new_name, node* new_parent) {
        ForChild(old_name, [=](node* child) {
            child->Rename(new_name, new_parent);
            return false;
        });
    }

    void Rename(const std::string& name, node* new_parent) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        if (new_parent != parent_) {
            RemoveFromParent();
            name_ = name;
            AddToParent(new_parent);
            return;
        }

        // Changing name_ will change the expected position of this node in parent's set of
        // children. Consider following scenario:
        // 1. This node: "b"; parent's set: {"a", "b", "c"}
        // 2. Rename("b", "d")
        //
        // After rename, parent's set should become: {"a", "b", "d"}, but if we simply change the
        // name it will be {"a", "d", "b"}, which violates properties of the set.
        //
        // To make sure that parent's set is always valid, changing name is 3 steps procedure:
        // 1. Remove this node from parent's set.
        // 2  Change the name.
        // 3. Add it back to the set.
        // Rename of node without changing its parent. Still need to remove and re-add it to make
        // sure lookup index is correct.
        if (name_ != name) {
            // If this is a root node, simply rename it.
            if (parent_ == nullptr) {
                name_ = name;
                return;
            }

            auto it = parent_->children_.find(this);
            CHECK(it != parent_->children_.end());
            parent_->children_.erase(it);

            name_ = name;

            parent_->children_.insert(this);
        }
    }

    const std::string& GetName() const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        return name_;
    }

    const std::string& GetIoPath() const { return io_path_; }

    int GetTransforms() const { return transforms_; }

    int GetTransformsReason() const { return transforms_reason_; }

    bool IsTransformsComplete() const {
        return transforms_complete_.load(std::memory_order_acquire);
    }

    void SetTransformsComplete(bool complete) {
        transforms_complete_.store(complete, std::memory_order_release);
    }

    node* GetParent() const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        return parent_;
    }

    inline void AddHandle(handle* h) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        handles_.emplace_back(std::unique_ptr<handle>(h));
    }

    void DestroyHandle(handle* h) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        auto comp = [h](const std::unique_ptr<handle>& ptr) { return ptr.get() == h; };
        auto it = std::find_if(handles_.begin(), handles_.end(), comp);
        CHECK(it != handles_.end());
        handles_.erase(it);
    }

    bool HasCachedHandle() const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        for (const auto& handle : handles_) {
            if (handle->cached) {
                return true;
            }
        }
        return false;
    }

    std::unique_ptr<FdAccessResult> CheckHandleForUid(const uid_t uid) const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        bool found_handle = false;
        bool redaction_not_needed = false;
        for (const auto& handle : handles_) {
            if (handle->uid == uid) {
                found_handle = true;
                redaction_not_needed |= !handle->ri->isRedactionNeeded();
            }
        }

        if (found_handle) {
            return std::make_unique<FdAccessResult>(BuildPath(), !redaction_not_needed);
        }

        return std::make_unique<FdAccessResult>(std::string(), false);
    }

    void SetName(std::string name) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        name_ = std::move(name);
    }

    bool HasRedactedCache() const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        return has_redacted_cache_;
    }

    void SetRedactedCache(bool state) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        has_redacted_cache_ = state;
    }

    inline void AddDirHandle(dirhandle* d) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        dirhandles_.emplace_back(std::unique_ptr<dirhandle>(d));
    }

    void DestroyDirHandle(dirhandle* d) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        auto comp = [d](const std::unique_ptr<dirhandle>& ptr) { return ptr.get() == d; };
        auto it = std::find_if(dirhandles_.begin(), dirhandles_.end(), comp);
        CHECK(it != dirhandles_.end());
        dirhandles_.erase(it);
    }

    // Deletes the tree of nodes rooted at |tree|.
    static void DeleteTree(node* tree);

    // Looks up an absolute path rooted at |root|, or nullptr if no such path
    // through the hierarchy exists.
    static const node* LookupAbsolutePath(const node* root, const std::string& absolute_path);

    // Looks up for the node with the given ino rooted at |root|, or nullptr if no such node exists.
    static const node* LookupInode(const node* root, ino_t ino);

  private:
    node(node* parent, const std::string& name, const std::string& io_path,
         const bool transforms_complete, const int transforms, const int transforms_reason,
         std::recursive_mutex* lock, ino_t ino, NodeTracker* tracker)
        : name_(name),
          io_path_(io_path),
          transforms_complete_(transforms_complete),
          transforms_(transforms),
          transforms_reason_(transforms_reason),
          refcount_(0),
          parent_(nullptr),
          has_redacted_cache_(false),
          deleted_(false),
          lock_(lock),
          ino_(ino),
          tracker_(tracker) {
        tracker_->NodeCreated(this);
        Acquire();
        // This is a special case for the root node. All other nodes will have a
        // non-null parent.
        if (parent != nullptr) {
            AddToParent(parent);
        }
    }

    // Acquires a reference to a node. This maps to the "lookup count" specified
    // by the FUSE documentation and must only happen under the circumstances
    // documented in libfuse/include/fuse_lowlevel.h.
    inline void Acquire() {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        refcount_++;
    }

    // Adds this node to a specified parent.
    void AddToParent(node* parent) {
        std::lock_guard<std::recursive_mutex> guard(*lock_);
        // This method assumes this node is currently unparented.
        CHECK(parent_ == nullptr);
        // Check that the new parent isn't nullptr either.
        CHECK(parent != nullptr);

        parent_ = parent;
        parent_->children_.insert(this);

        // TODO(narayan, zezeozue): It's unclear why we need to call Acquire on the
        // parent node when we're adding a child to it.
        parent_->Acquire();
    }

    // Removes this node from its current parent, and set its parent to nullptr.
    void RemoveFromParent() {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        if (parent_ != nullptr) {
            auto it = parent_->children_.find(this);
            CHECK(it != parent_->children_.end());
            parent_->children_.erase(it);

            parent_->Release(1);
            parent_ = nullptr;
        }
    }

    // Finds *all* non-deleted nodes matching |name| and runs the function |callback| on each
    // node until |callback| returns true.
    // When |callback| returns true, the matched node is returned
    node* ForChild(const std::string& name, const std::function<bool(node*)>& callback) const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        // lower_bound will give us the first child with strcasecmp(child->name, name) >=0.
        // For more context see comment on the NodeCompare struct.
        auto start = children_.lower_bound(std::make_pair(name, 0));
        // upper_bound will give us the first child with strcasecmp(child->name, name) > 0
        auto end =
                children_.upper_bound(std::make_pair(name, std::numeric_limits<uintptr_t>::max()));

        // Make a copy of the matches because calling callback might modify the list which will
        // cause issues while iterating over them.
        std::vector<node*> children(start, end);

        for (node* child : children) {
            if (!child->deleted_ && callback(child)) {
                return child;
            }
        }

        return nullptr;
    }

    // A custom heterogeneous comparator used for set of this node's children_ to speed up child
    // node by name lookups.
    //
    // This comparator treats node* as pair (node->name_, node): two nodes* are first
    // compared by their name using case-insenstive comparison function. If their names are equal,
    // then pointers are compared as integers.
    //
    // See LookupChildByName function to see how this comparator is used.
    //
    // Note that it's important to first compare by name_, since it will make all nodes with same
    // name (compared using strcasecmp) together, which allows LookupChildByName function to find
    // range of the candidate nodes by issuing two binary searches.
    struct NodeCompare {
        using is_transparent = void;

        bool operator()(const node* lhs, const node* rhs) const {
            int cmp = strcasecmp(lhs->name_.c_str(), rhs->name_.c_str());
            if (cmp != 0) {
                return cmp < 0;
            }
            return reinterpret_cast<uintptr_t>(lhs) < reinterpret_cast<uintptr_t>(rhs);
        }

        bool operator()(const node* lhs, const std::pair<std::string, uintptr_t>& rhs) const {
            int cmp = strcasecmp(lhs->name_.c_str(), rhs.first.c_str());
            if (cmp != 0) {
                return cmp < 0;
            }
            return reinterpret_cast<uintptr_t>(lhs) < rhs.second;
        }

        bool operator()(const std::pair<std::string, uintptr_t>& lhs, const node* rhs) const {
            int cmp = strcasecmp(lhs.first.c_str(), rhs->name_.c_str());
            if (cmp != 0) {
                return cmp < 0;
            }
            return lhs.second < reinterpret_cast<uintptr_t>(rhs);
        }
    };

    // A helper function to recursively construct the absolute path of a given node.
    // If |safe| is true, builds a PII safe path instead
    void BuildPathForNodeRecursive(bool safe, const node* node, std::stringstream* path) const;

    // The name of this node. Non-const because it can change during renames.
    std::string name_;
    // Filesystem path that will be used for IO (if it is non-empty) instead of node->BuildPath
    const std::string io_path_;
    // Whether any transforms required on |io_path_| are complete.
    // If false, might need to call a node transform function with |transforms| below
    std::atomic_bool transforms_complete_;
    // Opaque flags that determines the 'required' transforms to perform on node
    // before IO. These flags should not be interpreted in native but should be passed to the
    // MediaProvider as part of a transform function and if successful, |transforms_complete_|
    // should be set to true
    const int transforms_;
    // Opaque value indicating the reason why transforms are required.
    // This value should not be interpreted in native but should be passed to the MediaProvider
    // as part of a transform function
    const int transforms_reason_;
    // The reference count for this node. Guarded by |lock_|.
    uint32_t refcount_;
    // Set of children of this node. All of them contain a back reference
    // to their parent. Guarded by |lock_|.
    std::set<node*, NodeCompare> children_;
    // Containing directory for this node. Guarded by |lock_|.
    node* parent_;
    // List of file handles associated with this node. Guarded by |lock_|.
    std::vector<std::unique_ptr<handle>> handles_;
    // List of directory handles associated with this node. Guarded by |lock_|.
    std::vector<std::unique_ptr<dirhandle>> dirhandles_;
    bool has_redacted_cache_;
    bool deleted_;
    std::recursive_mutex* lock_;
    // Inode number of the file represented by this node.
    const ino_t ino_;

    NodeTracker* const tracker_;

    ~node() {
        RemoveFromParent();

        handles_.clear();
        dirhandles_.clear();

        tracker_->NodeDeleted(this);
    }

    friend class ::NodeTest;
};

}  // namespace fuse
}  // namespace mediaprovider

#endif  // MEDIA_PROVIDER_JNI_MODE_INL_H_
