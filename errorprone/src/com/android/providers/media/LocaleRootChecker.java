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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.ChildMultiMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.FieldMatchers;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;

@AutoService(BugChecker.class)
@BugPattern(
    name = "MediaProviderLocaleRoot",
    summary = "Verifies that all case-altering methods use Locale.ROOT",
    severity = WARNING)
public final class LocaleRootChecker extends BugChecker implements MethodInvocationTreeMatcher {
    private static final Matcher<ExpressionTree> STRING_TO_UPPER_CASE =
            Matchers.instanceMethod().onExactClass("java.lang.String").named("toUpperCase");
    private static final Matcher<ExpressionTree> STRING_TO_LOWER_CASE =
            Matchers.instanceMethod().onExactClass("java.lang.String").named("toLowerCase");
    private static final Matcher<ExpressionTree> LOCALE_ROOT =
            FieldMatchers.staticField("java.util.Locale", "ROOT");

    private static final Matcher<ExpressionTree> MISSING_LOCALE_ROOT = Matchers.anyOf(
            Matchers.methodInvocation(
                    STRING_TO_UPPER_CASE,
                    ChildMultiMatcher.MatchType.ALL,
                    Matchers.anyOf(Matchers.nothing(), Matchers.not(LOCALE_ROOT))),
            Matchers.methodInvocation(
                    STRING_TO_LOWER_CASE,
                    ChildMultiMatcher.MatchType.ALL,
                    Matchers.anyOf(Matchers.nothing(), Matchers.not(LOCALE_ROOT)))
            );

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (MISSING_LOCALE_ROOT.matches(tree, state)) {
            return buildDescription(tree)
                    .setMessage("All case-altering methods must declare Locale.ROOT")
                    .build();
        }
        return Description.NO_MATCH;
    }
}
