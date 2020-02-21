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

#include "node-inl.h"

static std::vector<std::string> GetPathSegments(int segment_start, const std::string& path) {
    std::vector<std::string> segments;
    int segment_end = path.find_first_of('/', segment_start);

    while (segment_end != std::string::npos) {
        if (segment_end == segment_start) {
            // First character is '/' ignore
            segment_end = path.find_first_of('/', ++segment_start);
            continue;
        }

        segments.push_back(path.substr(segment_start, segment_end - segment_start));
        segment_start = segment_end + 1;
        segment_end = path.find_first_of('/', segment_start);
    }
    if (segment_start < path.size()) {
        segments.push_back(path.substr(segment_start));
    }
    return segments;
}

namespace mediaprovider {
namespace fuse {

// Assumes that |node| has at least one child.
void node::BuildPathForNodeRecursive(node* node, std::string* path) {
    if (node->parent_) BuildPathForNodeRecursive(node->parent_, path);

    (*path) += node->GetName() + "/";
}

std::string node::BuildPath() const {
    std::lock_guard<std::recursive_mutex> guard(*lock_);
    std::string path;

    path.reserve(PATH_MAX);
    if (parent_) BuildPathForNodeRecursive(parent_, &path);
    path += name_;
    return path;
}

const node* node::LookupAbsolutePath(const node* root, const std::string& absolute_path) {
    if (absolute_path.find(root->GetName()) != 0) {
        return nullptr;
    }

    std::vector<std::string> segments = GetPathSegments(root->GetName().size(), absolute_path);

    std::lock_guard<std::recursive_mutex> guard(*root->lock_);

    const node* node = root;
    for (const std::string& segment : segments) {
        node = node->LookupChildByName(segment, false /* acquire */);
        if (!node) {
            return nullptr;
        }
    }
    return node;
}

void node::DeleteTree(node* tree) {
    std::lock_guard<std::recursive_mutex> guard(*tree->lock_);

    if (tree) {
        for (node* child : tree->children_) {
            DeleteTree(child);
        }
        tree->children_.clear();

        LOG(DEBUG) << "DELETE node " << tree->GetName();
        tree->RemoveFromParent();
        delete tree;
    }
}

}  // namespace fuse
}  // namespace mediaprovider
