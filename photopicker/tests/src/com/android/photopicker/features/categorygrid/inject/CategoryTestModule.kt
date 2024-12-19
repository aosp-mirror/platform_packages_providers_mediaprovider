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

package src.com.android.photopicker.features.categorygrid.inject

import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.features.categorygrid.inject.CategoryActivityRetainedModule
import com.android.photopicker.features.categorygrid.inject.CategoryEmbeddedServiceModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import src.com.android.photopicker.features.categorygrid.data.TestCategoryDataServiceImpl

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CategoryActivityRetainedModule::class, CategoryEmbeddedServiceModule::class],
)
class CategoryTestModule {

    @Singleton
    @Provides
    fun provideCategoryDataService(): CategoryDataService {
        return TestCategoryDataServiceImpl()
    }
}
