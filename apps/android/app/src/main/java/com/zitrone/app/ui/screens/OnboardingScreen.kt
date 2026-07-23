// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// The onboarding pager uses Compose Foundation's Pager APIs, which are still
// marked experimental in this Compose version; opt in for the whole file.
@file:OptIn(ExperimentalFoundationApi::class)

package com.zitrone.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.components.BurnParticles
import com.zitrone.app.ui.components.LemonSlice
import com.zitrone.app.ui.components.QrCode
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.BubbleReceivedShape
import com.zitrone.app.ui.theme.BubbleSentShape
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.PillShape
import com.zitrone.app.ui.theme.Rind
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class OnboardingSlide(
    val headline: String,
    val body: String,
)

// design_system.screens.onboarding.slides — copy verbatim.
private val Slides = listOf(
    OnboardingSlide(
        headline = "End-to-end encrypted",
        body = "Your messages are locked before they leave your device. We can't read them. Nobody can.",
    ),
    OnboardingSlide(
        headline = "Messages that disappear",
        body = "Set any message to self-destruct. Once read, once seen, then gone.",
    ),
    OnboardingSlide(
        headline = "Screenshots? Blocked.",
        body = "On Android, screenshots are impossible. On iOS and browser, we blur instantly.",
    ),
    OnboardingSlide(
        headline = "No phone number needed",
        body = "Add contacts by QR code or link. We don't need your number, your email, or your name.",
    ),
)

/**
 * Full-screen card stack, then the vault passphrase-creation step
 * (design_system.screens.onboarding). The 4-slide pager is unchanged; "Get started"
 * / "Skip" advance to passphrase creation rather than completing — a vault-only
 * install has no app without a created vault.
 *
 * @param onCreateVault invoked with the confirmed passphrase; the caller runs the
 *   off-main create (showing [creating]) and surfaces any retryable failure via [createError].
 */
@Composable
fun OnboardingScreen(
    onCreateVault: (passphrase: String) -> Unit,
    creating: Boolean,
    createError: String?,
    modifier: Modifier = Modifier,
) {
    var showPassphrase by remember { mutableStateOf(false) }
    if (showPassphrase) {
        PassphraseSetupStep(
            onCreate = onCreateVault,
            creating = creating,
            createError = createError,
            modifier = modifier,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { Slides.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (page) {
                        0 -> EncryptionLayersVisual()
                        1 -> BurningBubbleVisual()
                        2 -> BlockedScreenshotVisual()
                        else -> QrCode(content = "zitrone://contact", size = 180.dp)
                    }
                }
                Text(
                    text = Slides[page].headline,
                    style = MaterialTheme.typography.displaySmall,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 40.dp),
                )
                Text(
                    text = Slides[page].body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // Page indicator dots — lemon owns the active state.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(Slides.size) { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .width(if (active) 24.dp else 8.dp)
                        .background(if (active) Lemon else Rind, PillShape),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showPassphrase = true }) {
                Text("Skip", color = TextSecondary)
            }
            Button(
                onClick = {
                    if (pagerState.currentPage == Slides.lastIndex) {
                        showPassphrase = true
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lemon,
                    contentColor = TextOnLemon,
                ),
            ) {
                Text(if (pagerState.currentPage == Slides.lastIndex) "Get started" else "Next")
            }
        }
    }
}

/** Minimum passphrase length — the only composition rule (no strength-meter theater). */
private const val MIN_PASSPHRASE_LEN = 8

/**
 * Vault passphrase creation. Two fields (passphrase + confirm), the minimum-length gate,
 * and the key-responsibility line: this passphrase is UNRECOVERABLE. Create is disabled
 * until the two match at [MIN_PASSPHRASE_LEN]+ and while [creating]; [createError] surfaces
 * a retryable failure (e.g. a non-durable write) without ever bricking.
 */
@Composable
private fun PassphraseSetupStep(
    onCreate: (passphrase: String) -> Unit,
    creating: Boolean,
    createError: String?,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val longEnough = passphrase.length >= MIN_PASSPHRASE_LEN
    val matches = passphrase == confirm
    val canCreate = longEnough && matches && !creating

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Create your passphrase",
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This passphrase cannot be recovered — losing it loses this vault. " +
                "There is no reset, no backup, and no one who can let you back in.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            enabled = !creating,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm passphrase") },
            singleLine = true,
            enabled = !creating,
            isError = confirm.isNotEmpty() && !matches,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        if (passphrase.isNotEmpty() && !longEnough) {
            Text(
                text = "Use at least $MIN_PASSPHRASE_LEN characters.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (createError != null) {
            Text(
                text = createError,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = { if (canCreate) onCreate(passphrase) },
            enabled = canCreate,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    color = TextOnLemon,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text("Create")
            }
        }
    }
}

/** Slide 1 — animated lemon slice, segments as encryption layers. */
@Composable
private fun EncryptionLayersVisual() {
    val transition = rememberInfiniteTransition(label = "encryptionLayers")
    val fill by transition.animateFloat(
        initialValue = 0f,
        targetValue = 9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "layersFill",
    )
    LemonSlice(size = 160.dp, filledSegments = fill.toInt().coerceAtMost(8))
}

/** Slide 2 — message bubble looping the upward particle burn. */
@Composable
private fun BurningBubbleVisual() {
    val transition = rememberInfiniteTransition(label = "burnLoop")
    val raw by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1.5f, // pause at the end of each loop
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "burnLoopProgress",
    )
    val progress = raw.coerceAtMost(1f)
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val collapse = 1f - progress
                    scaleX = collapse
                    scaleY = collapse
                    alpha = 1f - progress * 0.85f
                }
                .background(Lemon, BubbleSentShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Read it. It's gone.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextOnLemon,
            )
        }
        BurnParticles(
            progress = progress,
            seed = 7,
            modifier = Modifier.size(180.dp, 80.dp),
        )
    }
}

/** Slide 3 — blurred mockup with a "blocked" pill. */
@Composable
private fun BlockedScreenshotVisual() {
    Box(contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.blur(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .background(Lemon, BubbleSentShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("Secret plans", color = TextOnLemon) }
            Box(
                Modifier
                    .background(BackgroundElevated, BubbleReceivedShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("Say less", color = TextPrimary) }
        }
        Box(
            modifier = Modifier
                .background(BackgroundPrimary.copy(alpha = 0.85f), PillShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Screenshot blocked",
                style = MaterialTheme.typography.labelMedium,
                color = Lemon,
            )
        }
    }
}
