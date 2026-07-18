// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.graphics.SolidColor
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
 * on focus), burn-on-read flame toggle, TTL cycler, and the 40dp lemon
 * circular send button (scale 0.92 on press, spring back).
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = BorderColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundSecondary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Attach — a paperclip that opens a small chooser (photo/file). The
            // photo picker needs no permission; the file picker uses SAF.
            var attachMenuOpen by remember { mutableStateOf(false) }
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
                    DropdownMenuItem(
                        text = { Text("Photo") },
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

            // Burn-on-read toggle — flame lights orange when armed.
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

            // TTL cycler — taps through Off/30s/1m/5m/1h/1d/1w.
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

            // Pill input.
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(BackgroundElevated, RoundedCornerShape(24.dp))
                    .border(
                        width = 1.dp,
                        color = if (focused) BorderActive else BorderColor,
                        shape = RoundedCornerShape(24.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
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

            // The send button is ALWAYS lemon yellow — primary action colour.
            LemonSendButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
            )
        }
    }
}
