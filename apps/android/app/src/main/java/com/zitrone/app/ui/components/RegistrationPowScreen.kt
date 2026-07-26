// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.theme.TextSecondary

/**
 * Registration proof-of-work screen — **FUNCTIONAL STUB**.
 *
 * ⚠️ THIS IS PLACEHOLDER ART ON PURPOSE. The real screen is a lemon-squeezing-into-pitcher
 * animation, built separately against `REGISTRATION_POW_UI_CONTRACT.md` (in this directory).
 * This stub exists so the feature is testable end to end before the art lands.
 *
 * **Replace the rendering, keep the contract.** [RegistrationPowUiState], the copy constants,
 * and the callback shape are the interface the rest of the app already speaks. A replacement
 * that changes them is not a drop-in.
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
    const val KEEP_WAITING = "Keep waiting"
    const val TRY_LATER = "Try later"
    const val BACKGROUNDED_NOTE = "Still working. You can leave this screen — we'll finish in the background."
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

@Composable
fun RegistrationPowScreen(
    uiState: RegistrationPowUiState,
    onKeepWaiting: () -> Unit,
    onTryLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read so the stub honours the setting too — the real component MUST respect it.
    val reducedMotion = rememberReducedMotion()

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
            style = MaterialTheme.typography.titleMedium,
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

        // PLACEHOLDER for the pitcher fill. Note the coerce: the bar is clamped for
        // RENDERING only — the underlying fraction is deliberately NOT clamped, because a
        // solve past 100% must still visibly progress rather than sit at a full bar. The
        // real component should express the overflow, not hide it.
        LinearProgressIndicator(
            progress = { uiState.fractionOfExpectedWork.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("registration_pow_progress"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = buildString {
                append("${(uiState.fractionOfExpectedWork * 100).toInt()}%")
                if (uiState.fractionOfExpectedWork > 1f) append(" (unlucky — still going)")
                append(" · ${uiState.elapsedSeconds}s")
                if (reducedMotion) append(" · reduced motion")
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )

        if (uiState.state == RegistrationPowState.BACKGROUNDED) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = RegistrationPowCopy.BACKGROUNDED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        // The 60s prompt renders OVER still-running work. Showing it changes nothing about
        // the solve — that is the whole point, and it is why this is a sibling in the same
        // layout rather than a replacement for the progress area.
        if (uiState.state == RegistrationPowState.PROMPTED_AT_60S) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = RegistrationPowCopy.SLOW_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("registration_pow_slow_prompt"),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
