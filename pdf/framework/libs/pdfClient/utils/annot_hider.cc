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

#include "annot_hider.h"

#include "annot.h"
#include "fpdfview.h"

namespace pdfClient_utils {

AnnotHider::AnnotHider(FPDF_PAGE page, const std::unordered_set<int>& types) {
    GetVisibleAnnots(page, types, &annots_);
    HideAnnots(annots_);
}

AnnotHider::~AnnotHider() {
    UnhideAnnots(annots_);
}

}  // namespace pdfClient_utils