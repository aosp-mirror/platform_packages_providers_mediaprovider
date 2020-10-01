/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef MEDIA_PROVIDER_FUSE_READDIR_HELPER_H
#define MEDIA_PROVIDER_FUSE_READDIR_HELPER_H

#include <dirent.h>
#include <string>
#include <vector>

namespace mediaprovider {
namespace fuse {

/**
 * Holds a directory entry.
 *
 * DirectoryEntry object holds information about the directory entry such as
 * name and type of the file or directory.
 */
struct DirectoryEntry {
    /**
     * Create a directory entry.
     *
     * @param name directory entry name.
     * @param type directory entry type. Directory entry type corresponds to
     * d_type of dirent structure defined in dirent.h
     */
    DirectoryEntry(const std::string& name, int type) : d_name(name), d_type(type) {}
    const std::string d_name;
    const int d_type;
};

/**
 * Adds directory entries from lower file system to the list.
 *
 * If a filter is specified, directory entries must satisfy the given filter. If filter is null,
 * all directory entries(except '.' & '..') are returned.
 */
void addDirectoryEntriesFromLowerFs(DIR* dirp, bool (*const filter)(const dirent&),
        std::vector<std::shared_ptr<DirectoryEntry>>* directory_entries);

/**
 * Checks if the given dirent is directory.
 */
bool isDirectory(const dirent& entry);

}  // namespace fuse
}  // namespace mediaprovider
#endif
