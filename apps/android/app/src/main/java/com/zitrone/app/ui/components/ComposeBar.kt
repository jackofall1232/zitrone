// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BackgroundSecondary
import com.zitrone.app.ui.theme.BorderActive
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.BurnOrange
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import com.zitrone.app.ui.theme.TypeScale

/** The lemon-drop droplet outline, in a 24×24 viewBox — identical to the web
 *  ComposeBar / the mock (packages/ui ComposeBar.tsx). */
private const val DROPLET_PATH =
    "M12 3C8.2 8 5.8 11.4 5.8 14.6a6.2 6.2 0 0 0 12.4 0C18.2 11.4 15.8 8 12 3z"

/** Human label for a TTL option (features.messaging.disappearing_messages). */
fun ttlLabel(seconds: Int?): String = when (seconds) {
    null -> "Off"
    30 -> "30s"
    60 -> "1m"
    300 -> "5m"
    3600 -> "1h"
    86400 -> "1d"
    604800 -> "1w"
    else -> "${seconds}s"
}

/**
 * Compose bar (design_system.components.compose_bar):
 * #1A1800 surface with a 1px top border, pill input (#242100, lemon border
 * on focus) with the **attachment control inside the pill** (leading edge —
 * paperclip opens photo/file chooser), burn-on-read + TTL outside the pill
 * (ephemeral controls don't fit cleanly inside without crowding the field),
 * optional lemon-drop droplet (Settings-gated by the caller), and the 40dp
 * lemon circular send button.
 *
 * Layout:
 * ```
 * [🔥] [⏱] [ 📎 | message text …… ] [💧?] [send]
 * ```
 */
@Composable
fun ComposeBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    burnOnRead: Boolean,
    onToggleBurnOnRead: () -> Unit,
    ttlSeconds: Int?,
    onCycleTtl: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    /**
     * Open the system camera for a one-shot photo. Null hides the menu item
     * (no camera hardware, or parent chose not to offer capture).
     */
    onAttachCamera: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * Seal the current draft into a lemon drop (QR dead-drop) for this contact.
     * Null hides the droplet entirely — the parent passes null when Settings
     * "Lemon-drop compose button" is off, or when there is no trusted identity
     * to seal to.
     */
    onSendAsQrDrop: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = BorderColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundSecondary)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Ephemeral controls stay OUTSIDE the pill — burn + TTL are
            // message-mode toggles, not field chrome. Putting them inside
            // with attach would make the pill a crowded toolbar.
            IconButton(onClick = onToggleBurnOnRead) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = if (burnOnRead) {
                        "Burn on read enabled"
                    } else {
                        "Enable burn on read"
                    },
                    tint = if (burnOnRead) BurnOrange else TextSecondary,
                )
            }

            IconButton(onClick = onCycleTtl) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = "Disappearing message timer: ${ttlLabel(ttlSeconds)}",
                        tint = if (ttlSeconds != null) Lemon else TextSecondary,
                    )
                    if (ttlSeconds != null) {
                        Text(
                            text = ttlLabel(ttlSeconds),
                            fontFamily = MonoFamily,
                            fontSize = TypeScale.Xs,
                            color = Lemon,
                        )
                    }
                }
            }

            // Pill input with leading attach — Material IconButton already
            // provides a ≥48dp touch target (a11y-safe on mobile).
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            var attachMenuOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(BackgroundElevated, RoundedCornerShape(24.dp))
                    .border(
                        width = 1.dp,
                        color = if (focused) BorderActive else BorderColor,
                        shape = RoundedCornerShape(24.dp),
                    )
                    .padding(start = 2.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = "Attach a photo or file",
                            tint = TextSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false },
                    ) {
                        if (onAttachCamera != null) {
                            DropdownMenuItem(
                                text = { Text("Take photo") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoCamera,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    onAttachCamera()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Photo library") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Image, contentDescription = null)
                            },
                            onClick = {
                                attachMenuOpen = false
                                onAttachImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                attachMenuOpen = false
                                onAttachFile()
                            },
                        )
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Lemon),
                    interactionSource = interactionSource,
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                        }
                        innerTextField()
                    },
                )
            }

            // Lemon-drop create — only when the parent wired a handler (Settings
            // opt-in + trusted contact key). Between pill and send so it reads as
            // a secondary action, not field chrome.
            if (onSendAsQrDrop != null) {
                val dropEnabled = value.isNotBlank()
                IconButton(onClick = onSendAsQrDrop, enabled = dropEnabled) {
                    val droplet = remember { PathParser().parsePathString(DROPLET_PATH).toPath() }
                    val tint = if (dropEnabled) Lemon else TextSecondary
                    Canvas(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Seal into a lemon drop" },
                    ) {
                        val s = size.minDimension / 24f
                        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                            drawPath(
                                path = droplet,
                                color = tint,
                                style = Stroke(width = 1.8f, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }

            LemonSendButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
            )
        }
    }
}
