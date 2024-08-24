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

package com.android.photopicker.lint.test

import com.android.photopicker.lint.LazyInjectionDetector
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@Suppress("UnstableApiUsage")
@RunWith(JUnit4::class)
class LazyInjectionDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = LazyInjectionDetector()

    override fun getIssues(): List<Issue> = listOf(LazyInjectionDetector.ISSUE)

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    private val entryPointStub: TestFile =
        java(
            """
                package dagger.hilt;
                import static java.lang.annotation.RetentionPolicy.CLASS;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.Target;

                @Retention(CLASS) public @interface EntryPoint {}
                """
        )

    private val inject: TestFile =
        kotlin(
                """
                package javax.inject.Inject

                @Retention(AnnotationRetention.RUNTIME) annotation class Inject
                """
            )
            .indented()

    private val configurationManager: TestFile =
        kotlin(
                """
            package com.android.photopicker.core.configuration

            class ConfigurationManager {}
            """
            )
            .indented()

    private val dataService: TestFile =
        kotlin(
                """
            package com.android.photopicker.data.DataService

            class DataService {}
            """
            )
            .indented()

    private val stubs = arrayOf(configurationManager, inject, dataService, entryPointStub)

    @Test
    fun testInjectConfigurationManager() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker

                import com.android.photopicker.core.configuration.ConfigurationManager
                import dagger.Lazy
                import javax.inject.Inject

                class MainActivity {

                    @Inject lateinit var configurationManager: ConfigurationManager

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testInjectDataServiceLazily() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import dagger.Lazy
                import javax.inject.Inject

                class MainActivity {

                    @Inject lateinit var configurationManager: ConfigurationManager
                    @Inject lateinit var dataService: Lazy<DataService>

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testInjectDataServiceNotLazy() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import dagger.Lazy
                import javax.inject.Inject

                class MainActivity {

                    @Inject lateinit var configurationManager: ConfigurationManager
                    @Inject lateinit var dataService: DataService

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectContains(LazyInjectionDetector.INVALID_INJECTION_FIELD_ERROR)
    }

    @Test
    fun testInjectDataServiceNotLazyNotEnforcedClass() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import dagger.Lazy
                import javax.inject.Inject

                class SomeFeatureViewModel {

                    @Inject lateinit var configurationManager: ConfigurationManager
                    @Inject lateinit var dataService: DataService

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testInjectDataServiceNotLazyEntryPoint() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker.core.embedded

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import dagger.Lazy
                import dagger.hilt.EntryPoint
                import javax.inject.Inject

                class Session {

                    @EntryPoint
                    @InstallIn(EmbeddedServiceComponent::class)
                    interface EmbeddedEntryPoint {
                            fun configurationManager(): Lazy<ConfigurationManager>
                            fun dataService(): DataService
                    }

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectContains(LazyInjectionDetector.INVALID_INJECTION_FIELD_ERROR)
    }

    @Test
    fun testInjectDataServiceNotLazyNotEntryPoint() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker.core.embedded

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import javax.inject.Inject

                class Session {

                    @InstallIn(EmbeddedServiceComponent::class)
                    interface EmbeddedEntryPoint {
                            fun configurationManager(): ConfigurationManager
                            fun dataService(): DataService
                    }

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }

    @Test
    fun testInjectDataServiceLazyEntryPoint() {
        lint()
            .files(
                kotlin(
                        """
                package com.android.photopicker.core.embedded

                import com.android.photopicker.core.configuration.ConfigurationManager
                import com.android.photopicker.data.DataService
                import dagger.Lazy
                import dagger.hilt.EntryPoint
                import javax.inject.Inject

                class Session {

                    @EntryPoint
                    @InstallIn(EmbeddedServiceComponent::class)
                    interface EmbeddedEntryPoint {
                            fun configurationManager(): ConfigurationManager
                            fun dataService(): Lazy<DataService>
                    }

                }
                """
                    )
                    .indented(),
                *stubs
            )
            .run()
            .expectClean()
    }
}
