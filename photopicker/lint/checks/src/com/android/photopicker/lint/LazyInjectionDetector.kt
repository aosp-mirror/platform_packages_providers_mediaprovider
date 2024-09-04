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

package com.android.photopicker.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

/**
 * A linter implementation that enforces Hilt dependencies to be injected Lazily in certain core
 * Photopicker classes.
 */
class LazyInjectionDetector : Detector(), SourceCodeScanner {

    companion object {

        const val INVALID_INJECTION_FIELD_ERROR =
            "Dependencies injected into core classes must be either an allowlisted dependency " +
                "or be injected Lazily. Use dagger.Lazy to inject the dependency, to avoid " +
                "out-of-order initialization issues."

        val ISSUE =
            Issue.create(
                id = "LazyInjectionRequired",
                briefDescription =
                    "Hilt dependencies should be injected into primary classes lazily to avoid " +
                        "out-of-order initialization issues.",
                explanation =
                    "Photopicker's injected classes implementation expects a certain " +
                        "initialization order, namely that the PhotopickerConfiguration is " +
                        "stable before other classes are created to reduce error prone issues " +
                        "related to a configuration update or re-initialization of FeatureManager.",
                category = Category.CORRECTNESS,
                severity = Severity.ERROR,
                implementation =
                    Implementation(LazyInjectionDetector::class.java, Scope.JAVA_FILE_SCOPE),
                androidSpecific = true,
            )

        // Core classes this LazyInjectionDetector enforces.
        val ENFORCED_CLASSES: List<String> =
            listOf(
                "com.android.photopicker.MainActivity",
                "com.android.photopicker.core.embedded.Session",
            )

        /** The list of class that may be injected without using Lazy<...> */
        val ALLOWED_NON_LAZY_CLASSES =
            listOf(
                "android.content.ContentResolver",
                "android.os.UserHandle",
                "com.android.photopicker.core.configuration.ConfigurationManager",
                "com.android.photopicker.core.embedded.EmbeddedLifecycle",
                "kotlinx.coroutines.CoroutineDispatcher",
                "kotlinx.coroutines.CoroutineScope",
            )

        // Qualified name of the @Inject annotation.
        val INJECT_ANNOTATION = "javax.inject.Inject"

        // Qualified name of the EntryPoint annotation.
        val ENTRY_POINT_ANNOTATION = "dagger.hilt.EntryPoint"

        // The qualified name of the Dagger lazy class.
        val DAGGER_LAZY = "dagger.Lazy"
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UClass::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler? {

        return object : UElementHandler() {

            override fun visitClass(node: UClass) {

                // If the class being inspected is not one of the enforced classes, skip the class.
                if (!ENFORCED_CLASSES.contains(node.qualifiedName)) {
                    return
                }

                for (_node in node.getFields()) {

                    // Quickly skip the field if it is not a lateinit field, all Hilt fields are
                    // lateinit.
                    if (!context.evaluator.isLateInit(_node)) {
                        continue
                    }

                    // If the field is not annotated with @Inject then skip it.
                    _node.uAnnotations.find { it.qualifiedName == INJECT_ANNOTATION } ?: continue

                    // This is the qualified type signature of the field
                    val typeQualified = _node.typeReference?.getQualifiedName()

                    // If the qualified type is either in the allowlist, or a Lazy<*> field,
                    // it is allowed.
                    if (
                        typeQualified == DAGGER_LAZY ||
                            ALLOWED_NON_LAZY_CLASSES.contains(typeQualified)
                    ) {
                        continue
                    }

                    // The field is an @Inject non-lazy field that is not in the allow-list.
                    // Report this as an error as this is not permitted.
                    context.report(
                        issue = ISSUE,
                        location = context.getNameLocation(_node),
                        message = INVALID_INJECTION_FIELD_ERROR,
                    )
                }

                for (clazz in node.getInnerClasses()) {

                    // Only check inner classes that are marked with an "EntryPoint annotation"
                    clazz.uAnnotations.find { it.qualifiedName == ENTRY_POINT_ANNOTATION }
                        ?: continue

                    // EntryPoints use methods rather than fields, so iterate all the methods in
                    // the EntryPoint.
                    for (_method in clazz.getMethods()) {

                        val typeQualified = _method.returnTypeReference?.getQualifiedName()
                        // If the qualified type is either in the allowlist, or a Lazy<*> field,
                        // it is allowed.
                        if (
                            typeQualified == DAGGER_LAZY ||
                                ALLOWED_NON_LAZY_CLASSES.contains(typeQualified)
                        ) {
                            continue
                        }

                        // The method is a non-lazy return type that is not in the allow-list.
                        // Report this as an error as this is not permitted.
                        context.report(
                            issue = ISSUE,
                            location = context.getNameLocation(_method),
                            message = INVALID_INJECTION_FIELD_ERROR,
                        )
                    }
                }
            }
        }
    }
}
