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
    explicit handle(int fd, const RedactionInfo* ri, bool cached) : fd(fd), ri(ri), cached(cached) {
        CHECK(ri != nullptr);
    }

    const int fd;
    const std::unique_ptr<const RedactionInfo> ri;
    const bool cached;

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
    static node* Create(node* parent, const std::string& name, std::recursive_mutex* lock,
                        NodeTracker* tracker) {
        // Place the entire constructor under a critical section to make sure
        // node creation, tracking (if enabled) and the addition to a parent are
        // atomic.
        std::lock_guard<std::recursive_mutex> guard(*lock);
        return new node(parent, name, lock, tracker);
    }

    // Creates a new root node. Root nodes have no parents by definition
    // and their "name" must signify an absolute path.
    static node* CreateRoot(const std::string& path, std::recursive_mutex* lock,
                            NodeTracker* tracker) {
        std::lock_guard<std::recursive_mutex> guard(*lock);
        node* root = new node(nullptr, path, lock, tracker);

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

    // Looks up a direct descendant of this node by name. If |acquire| is true,
    // also Acquire the node before returning a reference to it.
    node* LookupChildByName(const std::string& name, bool acquire) const {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        // lower_bound will give us the first child with strcasecmp(child->name, name) >=0.
        // For more context see comment on the NodeCompare struct.
        auto start = children_.lower_bound(std::make_pair(name, 0));
        // upper_bound will give us the first child with strcasecmp(child->name, name) > 0
        auto end =
                children_.upper_bound(std::make_pair(name, std::numeric_limits<uintptr_t>::max()));
        for (auto it = start; it != end; it++) {
            node* child = *it;
            if (!child->deleted_) {
                if (acquire) {
                    child->Acquire();
                }
                return child;
            }
        }
        return nullptr;
    }

    // Marks this node as deleted. It is still associated with its parent, and
    // all open handles etc. to this node are preserved until its refcount goes
    // to zero.
    void SetDeleted() {
        std::lock_guard<std::recursive_mutex> guard(*lock_);

        deleted_ = true;
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

  private:
    node(node* parent, const std::string& name, std::recursive_mutex* lock, NodeTracker* tracker)
        : name_(name),
          refcount_(0),
          parent_(nullptr),
          deleted_(false),
          lock_(lock),
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
    bool deleted_;
    std::recursive_mutex* lock_;

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
