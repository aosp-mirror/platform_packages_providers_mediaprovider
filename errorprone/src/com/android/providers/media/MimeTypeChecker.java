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
import com.google.errorprone.bugpatterns.BugChecker.SwitchTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.SwitchTree;

import java.util.function.Predicate;

@AutoService(BugChecker.class)
@BugPattern(
    name = "MediaProviderMimeType",
    summary = "Verifies that all MIME type operations are case-insensitive",
    severity = WARNING)
public final class MimeTypeChecker extends BugChecker
        implements MethodInvocationTreeMatcher, SwitchTreeMatcher {
    private static final String MESSAGE = "MIME type comparisons must be case-insensitive";

    private static final Matcher<ExpressionTree> STRING_EQUALS =
            Matchers.instanceMethod().onExactClass("java.lang.String").named("equals");
    private static final Matcher<ExpressionTree> STRING_STARTS_WITH =
            Matchers.instanceMethod().onExactClass("java.lang.String").named("startsWith");
    private static final Matcher<ExpressionTree> STRING_REGION_MATCHES =
            Matchers.instanceMethod().onExactClass("java.lang.String").named("regionMatches");
    private static final Matcher<ExpressionTree> OBJECTS_EQUALS =
            Matchers.staticMethod().onClass("java.util.Objects").named("equals");

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (STRING_EQUALS.matches(tree, state) || STRING_STARTS_WITH.matches(tree, state)
                || OBJECTS_EQUALS.matches(tree, state)) {
            if (MIME_WITHOUT_CASE_FOLDING.matches(tree.getMethodSelect(), state)
                    || tree.getArguments().stream().anyMatch(MIME_WITHOUT_CASE_FOLDING)) {
                return buildDescription(tree).setMessage(MESSAGE).build();
            }
        }
        if (STRING_REGION_MATCHES.matches(tree, state)) {
            if (!Matchers.booleanLiteral(true).matches(tree.getArguments().get(0), state)) {
                return buildDescription(tree).setMessage(MESSAGE).build();
            }
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchSwitch(SwitchTree tree, VisitorState state) {
        if (MIME_WITHOUT_CASE_FOLDING.matches(tree.getExpression(), state)) {
            return buildDescription(tree).setMessage(MESSAGE).build();
        }
        return Description.NO_MATCH;
    }

    private static final MimeWithoutCaseFoldingMatcher MIME_WITHOUT_CASE_FOLDING =
            new MimeWithoutCaseFoldingMatcher();

    private static class MimeWithoutCaseFoldingMatcher
            implements Matcher<ExpressionTree>, Predicate<ExpressionTree> {
        @Override
        public boolean matches(ExpressionTree tree, VisitorState state) {
            // This is a pretty rough way to match raw names, but it works
            final String string = tree.toString();
            return string.toLowerCase().contains("mime") && !string.contains("toUpperCase")
                    && !string.contains("toLowerCase");
        }

        @Override
        public boolean test(ExpressionTree tree) {
            return matches(tree, null);
        }
    }
}
