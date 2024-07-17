/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.photopicker.core

import dagger.hilt.DefineComponent
import dagger.hilt.android.components.ServiceComponent

/**
 * A custom child component of the [ServiceComponent] that will be owned by the [EmbeddedService].
 *
 * @see [EmbeddedSessionModule] which is a dependency container installed in this component.
 */
@DefineComponent(parent = ServiceComponent::class) public interface EmbeddedServiceComponent

/**
 * A component builder that can be used to obtain a new instance of the [EmbeddedServiceComponent].
 * This builder should be used to create a unique set of injectable dependencies for each individual
 * embedded photopicker session.
 */
@DefineComponent.Builder
interface EmbeddedServiceComponentBuilder {
    fun build(): EmbeddedServiceComponent
}
