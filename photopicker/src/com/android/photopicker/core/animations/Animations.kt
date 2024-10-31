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

package com.android.photopicker.core.animations

import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** From the material-3 emphasized easing set */
val emphasizedDecelerate: FiniteAnimationSpec<IntOffset> =
    tween(
        durationMillis = 400,
        easing = Easing { PathInterpolator(0.05f, 0.7f, 0.1f, 1f).getInterpolation(it) },
    )

/** From the material-3 emphasized easing set */
val emphasizedAccelerate: FiniteAnimationSpec<IntOffset> =
    tween(
        durationMillis = 200,
        easing = Easing { PathInterpolator(0.03f, 0f, 0.8f, 0.15f).getInterpolation(it) },
    )

/** From the material-3 motion physics system */
val springDefaultEffectFloat: FiniteAnimationSpec<Float> =
    spring(dampingRatio = 0.8f, stiffness = 380f)

/** From the material-3 motion physics system */
val springDefaultEffectOffset: FiniteAnimationSpec<IntOffset> =
    spring(dampingRatio = 0.8f, stiffness = 380f)

/** From the material-3 emphasized easing set */
val emphasizedAccelerateFloat: FiniteAnimationSpec<Float> =
    tween(
        durationMillis = 150,
        easing = Easing { PathInterpolator(0.3f, 0f, 0.8f, 0.15f).getInterpolation(it) },
    )

/** From the material-3 standard easing set */
fun standardDecelerate(durationMillis: Int = 200): FiniteAnimationSpec<IntSize> =
    tween(
        durationMillis = durationMillis,
        easing = Easing { PathInterpolator(0f, 0f, 0f, 1f).getInterpolation(it) },
    )
