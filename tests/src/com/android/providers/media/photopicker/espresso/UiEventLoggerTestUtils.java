/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.espresso;

import static org.mockito.Mockito.verify;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.internal.logging.UiEventLogger;

public class UiEventLoggerTestUtils {
    static void verifyLogWithInstanceId(ActivityScenarioRule<PhotoPickerTestActivity> rule,
            UiEventLogger.UiEventEnum event) {
        verifyLogWithInstanceId(rule, event, /* uid */ 0, /* packageName */ null);
    }

    static void verifyLogWithInstanceId(ActivityScenarioRule<PhotoPickerTestActivity> rule,
            UiEventLogger.UiEventEnum event, int uid, String packageName) {
        rule.getScenario().onActivity(activity ->
                verify(activity.getLogger()).logWithInstanceId(
                        event, uid, packageName, activity.getInstanceId()));
    }

    static void verifyLogWithInstanceIdAndPosition(
            ActivityScenarioRule<PhotoPickerTestActivity> rule,
            UiEventLogger.UiEventEnum event, int position) {
        verifyLogWithInstanceIdAndPosition(
                rule, event, /* uid */ 0, /* packageName */ null, position);
    }

    static void verifyLogWithInstanceIdAndPosition(
            ActivityScenarioRule<PhotoPickerTestActivity> rule, UiEventLogger.UiEventEnum event,
            int uid, String packageName, int position) {
        verifyLogWithInstanceIdAndPosition(rule.getScenario(), event, uid, packageName, position);
    }

    static <T extends PhotoPickerTestActivity> void verifyLogWithInstanceIdAndPosition(
            ActivityScenario<T> scenario, UiEventLogger.UiEventEnum event,
            int uid, String packageName, int position) {
        scenario.onActivity(activity ->
                verify(activity.getLogger())
                        .logWithInstanceIdAndPosition(
                                event, uid, packageName, activity.getInstanceId(), position));
    }
}
