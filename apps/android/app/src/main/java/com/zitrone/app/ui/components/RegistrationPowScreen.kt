// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.FingerprintTextStyle
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.LemonBright
import com.zitrone.app.ui.theme.LemonDeep
import com.zitrone.app.ui.theme.LemonZest
import com.zitrone.app.ui.theme.Motion
import com.zitrone.app.ui.theme.Pulp
import com.zitrone.app.ui.theme.Radius
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Registration proof-of-work screen — a lemon squeezed into a pitcher.
 *
 * Built against `REGISTRATION_POW_UI_CONTRACT.md` (in this directory). The pitcher's fill is
 * driven ONLY by [RegistrationPowUiState.fractionOfExpectedWork] — never by elapsed time —
 * and past 1.0 the pitcher overflows rather than sitting deceptively full: the spill and
 * puddle grow with the fraction, so the visual keeps moving on every progress emission even
 * with animations disabled.
 *
 * This component is PURE PRESENTATION. It receives numbers and renders them. It does not
 * solve, cancel, schedule, fetch, or know what a proof is — see the contract for why that
 * boundary is drawn where it is.
 */

/** Where the solve is. Ordinal order is not meaningful; treat as a tagged union. */
enum class RegistrationPowState {
    /** Not started (or challenge not yet fetched). Nothing to show but the copy. */
    IDLE,

    /** Work is running and the user is watching. The normal case. */
    SOLVING,

    /** Past 60s: the prompt is shown OVER still-running work. The solve did NOT pause. */
    PROMPTED_AT_60S,

    /** App was backgrounded mid-solve; a foreground service carries the work. */
    BACKGROUNDED,

    /** Solved. */
    COMPLETE,

    /** User chose "try later"; the solve was aborted. */
    CANCELLED,
}

/**
 * Everything the screen renders. Produced by the solve layer; this component never derives
 * any of it.
 *
 * @param fractionOfExpectedWork progress as a fraction of EXPECTED work, driven by actual
 *   hash/evaluation count — never by elapsed time. **May exceed 1.0** (see the contract:
 *   the work required is geometrically distributed, so ~37% of solves run past 1.0).
 * @param elapsedSeconds wall-clock seconds since the solve began. For display only —
 *   it must never drive the progress indicator.
 */
data class RegistrationPowUiState(
    val state: RegistrationPowState = RegistrationPowState.IDLE,
    val fractionOfExpectedWork: Float = 0f,
    val elapsedSeconds: Long = 0L,
)

/** Copy is FINAL and set by the product owner — see the contract. Do not reword. */
object RegistrationPowCopy {
    const val PRIMARY = "proving your device is real so we don't need your phone number"
    const val SUBLINE = "you have to squeeze a few lemons to get lemonade"
    const val SLOW_PROMPT =
        "this is taking longer than expected — your device may be in battery saver or " +
            "under heavy load. Try again with the app in the foreground, or plugged in."
    const val KEEP_WAITING = "keep waiting"
    const val TRY_LATER = "try later"

    /** Contract §6.5 marks this one as NOT locked; reworded from the placeholder. Claims only
     * what the contract guarantees — do NOT promise the notification shows a count unless the
     * foreground service actually renders progress (unbuilt as of 2026-07-26). */
    const val BACKGROUNDED_NOTE =
        "still working — it's safe to leave. we'll finish in the background."

    /** Shown past 1.0. The required work is random; some draws are longer. Not in §3 — raised for sign-off. */
    const val OVERFULL_NOTE = "some lemons are juicier than others"
}

/**
 * True when the user has asked the system to suppress animation
 * (Developer options / accessibility → animation scale 0). Android's equivalent of the web's
 * `prefers-reduced-motion`; there is no direct Compose API for it, so it is read here and
 * passed down rather than re-derived per animation.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/** One full squeeze (compress → drip → relax). Not a Motion token: those cap at 600ms and this is an ambient loop. */
private const val SQUEEZE_CYCLE_MS = 1400

@Composable
fun RegistrationPowScreen(
    uiState: RegistrationPowUiState,
    onKeepWaiting: () -> Unit,
    onTryLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val state = uiState.state
    val fraction = uiState.fractionOfExpectedWork.coerceAtLeast(0f)

    val working = state == RegistrationPowState.SOLVING ||
        state == RegistrationPowState.PROMPTED_AT_60S ||
        state == RegistrationPowState.BACKGROUNDED

    // COMPLETE renders a full pitcher regardless of the final draw: completion is a state,
    // not a fraction, and a solve that finished early must still LOOK finished.
    val targetFill = if (state == RegistrationPowState.COMPLETE) 1f else fraction

    // animateFloatAsState starts AT its first target, so composing mid-solve (returning from
    // background, or arriving already COMPLETE) renders the current fill with no replay from
    // zero. Under reduced motion the fill snaps between emissions instead of tweening.
    val fill by animateFloatAsState(
        targetValue = targetFill,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(Motion.DurationSlowMs, easing = Motion.EasingDefault)
        },
        label = "pitcherFill",
    )

    // The ambient squeeze loop runs only while work is actually running, and never under
    // reduced motion — the fill above is the static progress indicator in that mode.
    val squeezing = working && !reducedMotion
    val squeezePhase: Float = if (squeezing) {
        rememberInfiniteTransition(label = "squeeze")
            .animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(SQUEEZE_CYCLE_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "squeezePhase",
            ).value
    } else {
        0f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("registration_pow_screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = RegistrationPowCopy.PRIMARY,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = RegistrationPowCopy.SUBLINE,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        Canvas(
            modifier = Modifier
                .size(width = 200.dp, height = 240.dp)
                .testTag("registration_pow_progress")
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = fraction,
                        range = 0f..maxOf(1f, fraction),
                    )
                },
        ) {
            drawLemonSqueeze(
                fill = fill,
                squeezePhase = squeezePhase,
                squeezing = squeezing,
                complete = state == RegistrationPowState.COMPLETE,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Numbers stay honest: the percent is the raw fraction, uncapped. Timestamps and
        // counters always render in mono (house rule).
        Text(
            text = if (state == RegistrationPowState.COMPLETE) {
                "${uiState.elapsedSeconds}s"
            } else {
                "${(fraction * 100).toInt()}% · ${uiState.elapsedSeconds}s"
            },
            style = FingerprintTextStyle,
            color = if (fraction > 1f && state != RegistrationPowState.COMPLETE) LemonZest else TextSecondary,
            modifier = Modifier.testTag("registration_pow_readout"),
        )

        if (fraction > 1f && working) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = RegistrationPowCopy.OVERFULL_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("registration_pow_overfull"),
            )
        }

        if (state == RegistrationPowState.BACKGROUNDED) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = RegistrationPowCopy.BACKGROUNDED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        // The 60s prompt renders OVER still-running work, as a card below the live pitcher —
        // progress stays visible and keeps advancing the entire time it is up. Showing it
        // changes nothing about the solve; "keep waiting" only dismisses it.
        AnimatedVisibility(
            visible = state == RegistrationPowState.PROMPTED_AT_60S,
            enter = if (reducedMotion) {
                EnterTransition.None
            } else {
                fadeIn(tween(Motion.DurationBaseMs)) +
                    expandVertically(tween(Motion.DurationBaseMs, easing = Motion.EasingDefault))
            },
            exit = if (reducedMotion) {
                ExitTransition.None
            } else {
                fadeOut(tween(Motion.DurationFastMs)) +
                    shrinkVertically(tween(Motion.DurationFastMs))
            },
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .background(BackgroundElevated, RoundedCornerShape(Radius.Lg))
                    .border(1.dp, BorderColor, RoundedCornerShape(Radius.Lg))
                    .padding(16.dp),
            ) {
                Text(
                    text = RegistrationPowCopy.SLOW_PROMPT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.testTag("registration_pow_slow_prompt"),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = onKeepWaiting,
                        modifier = Modifier.testTag("registration_pow_keep_waiting"),
                    ) { Text(RegistrationPowCopy.KEEP_WAITING) }
                    TextButton(
                        onClick = onTryLater,
                        modifier = Modifier.testTag("registration_pow_try_later"),
                    ) { Text(RegistrationPowCopy.TRY_LATER) }
                }
            }
        }
    }
}

/**
 * The scene: lemon up top, pitcher below, juice level = [fill] (clamped at the rim), and
 * past 1.0 an overflow whose spill length and puddle size are pure functions of [fill] —
 * so overfull keeps visibly moving with real progress even when [squeezing] is off.
 */
private fun DrawScope.drawLemonSqueeze(
    fill: Float,
    squeezePhase: Float,
    squeezing: Boolean,
    complete: Boolean,
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val stroke = 2.5.dp.toPx()

    // --- Pitcher geometry: a gently tapered body, open rim, round handle. ---
    val rimY = h * 0.42f
    val baseY = h * 0.96f
    val topHalf = w * 0.21f
    val botHalf = w * 0.165f
    val corner = w * 0.05f

    val body = Path().apply {
        moveTo(cx - topHalf, rimY)
        lineTo(cx - botHalf, baseY - corner)
        quadraticBezierTo(cx - botHalf, baseY, cx - botHalf + corner, baseY)
        lineTo(cx + botHalf - corner, baseY)
        quadraticBezierTo(cx + botHalf, baseY, cx + botHalf, baseY - corner)
        lineTo(cx + topHalf, rimY)
    }
    val bodyClosed = Path().apply { addPath(body); close() }

    // --- Juice: clipped to the body, level driven by fill (visual level caps at the rim). ---
    val headroom = h * 0.02f
    val interiorTop = rimY + headroom
    val level = min(fill, 1f)
    val juiceTop = baseY - (baseY - interiorTop) * level
    if (level > 0f) {
        clipPath(bodyClosed) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Lemon, LemonDeep),
                    startY = juiceTop,
                    endY = baseY,
                ),
                topLeft = Offset(cx - topHalf, juiceTop),
                size = Size(topHalf * 2f, baseY - juiceTop),
            )
        }
        // Surface line — brighter at the top of the pour; a touch thicker once finished.
        val halfAt = botHalf + (topHalf - botHalf) * ((baseY - juiceTop) / (baseY - rimY))
        drawLine(
            color = Pulp,
            start = Offset(cx - halfAt + stroke, juiceTop),
            end = Offset(cx + halfAt - stroke, juiceTop),
            strokeWidth = if (complete) stroke * 1.6f else stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }

    // --- Overflow past 1.0: spill down both walls plus a growing puddle. Pure function of
    // fill, so every progress emission moves it — no clock involved. ---
    if (fill > 1f) {
        val overflowT = ((fill - 1f) / 0.75f).coerceIn(0f, 1f)
        listOf(-1f, 1f).forEach { side ->
            val rimCorner = Offset(cx + side * (topHalf + stroke * 0.6f), rimY)
            val baseCorner = Offset(cx + side * (botHalf + stroke * 0.6f), baseY)
            drawLine(
                color = Lemon,
                start = rimCorner,
                end = Offset(
                    rimCorner.x + (baseCorner.x - rimCorner.x) * overflowT,
                    rimCorner.y + (baseCorner.y - rimCorner.y) * overflowT,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (overflowT > 0.10f) {
            val puddleHalf = w * (0.20f + 0.17f * overflowT)
            drawOval(
                color = Lemon,
                topLeft = Offset(cx - puddleHalf, baseY - h * 0.008f),
                size = Size(puddleHalf * 2f, h * 0.028f),
            )
        }
    }

    // --- Pitcher outline over the juice, then the handle. ---
    drawPath(body, color = TextMuted, style = Stroke(width = stroke, cap = StrokeCap.Round))
    val handle = Path().apply {
        moveTo(cx + topHalf, rimY + h * 0.05f)
        cubicTo(
            cx + topHalf + w * 0.15f, rimY + h * 0.08f,
            cx + topHalf + w * 0.15f, rimY + h * 0.30f,
            cx + botHalf + w * 0.015f, rimY + (baseY - rimY) * 0.60f,
        )
    }
    drawPath(handle, color = TextMuted, style = Stroke(width = stroke, cap = StrokeCap.Round))

    // --- The lemon: squashes on each squeeze cycle; at rest it just sits there. ---
    val lemonCy = h * 0.14f
    val squash = if (squeezing) {
        val t = ((squeezePhase - 0.05f) / 0.40f).coerceIn(0f, 1f)
        sin(t * PI).toFloat()
    } else {
        0f
    }
    val rx = w * 0.14f * (1f + 0.14f * squash)
    val ry = h * 0.085f * (1f - 0.20f * squash)
    // End nubs first so the body overlaps them into a lemon silhouette.
    listOf(-1f, 1f).forEach { side ->
        drawOval(
            color = if (complete) LemonZest else LemonBright,
            topLeft = Offset(cx + side * rx - ry * 0.30f, lemonCy - ry * 0.30f),
            size = Size(ry * 0.60f, ry * 0.60f),
        )
    }
    drawOval(
        color = if (complete) LemonZest else LemonBright,
        topLeft = Offset(cx - rx, lemonCy - ry),
        size = Size(rx * 2f, ry * 2f),
    )
    drawOval(
        color = Pulp,
        alpha = 0.35f,
        topLeft = Offset(cx - rx * 0.55f, lemonCy - ry * 0.65f),
        size = Size(rx * 0.5f, ry * 0.45f),
    )

    // --- Falling drops, only mid-squeeze. Three per cycle, gravity-eased into the juice. ---
    if (squeezing) {
        val lemonBottom = lemonCy + ry
        val dropR = w * 0.020f
        floatArrayOf(0.18f, 0.32f, 0.46f).forEachIndexed { i, birth ->
            val t = (squeezePhase - birth) / 0.34f
            if (t in 0f..1f) {
                val y = lemonBottom + (juiceTop - dropR - lemonBottom) * t * t
                val x = cx + (i - 1) * w * 0.02f
                drawOval(
                    color = LemonBright,
                    alpha = 1f - 0.4f * t,
                    topLeft = Offset(x - dropR, y - dropR * 1.4f),
                    size = Size(dropR * 2f, dropR * 2.8f),
                )
            }
        }
    }
}
