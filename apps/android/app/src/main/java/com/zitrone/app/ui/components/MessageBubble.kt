// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.data.AttachmentControlPayload
import com.zitrone.app.data.AttachmentLoadState
import com.zitrone.app.data.Message
import com.zitrone.app.data.MessageAttachment
import com.zitrone.app.data.MessageState
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.BubbleReceivedShape
import com.zitrone.app.ui.theme.BubbleSentShape
import com.zitrone.app.ui.theme.BackgroundMessageReceived
import com.zitrone.app.ui.theme.BackgroundMessageSent
import com.zitrone.app.ui.theme.BurnOrange
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TypeScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Message bubble per design_system.components.message_bubble:
 *  - sent:     lemon #F5E642 background, dark text, radius 18/18/4/18
 *  - received: #242100 background, light text, 1px #2E2B00 border,
 *              radius 18/18/18/4
 *  - both:     padding 10x14, 0.9375rem text, max width 72%
 *
 * When the message enters [MessageState.BURNING], the bubble chars and
 * shrinks while flame particles dissolve upward (600ms) — actual removal is
 * handled by MessageRepository once the animation window elapses.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val isMine = message.isMine
    val burning = message.state == MessageState.BURNING
    val progress = rememberBurnProgress(burning)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.72f

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .graphicsLayer {
                            // Char-and-shrink under the particle field — the
                            // particles carry the effect; this is the collapse.
                            val collapse = 1f - progress
                            scaleX = collapse
                            scaleY = collapse
                            alpha = 1f - progress * 0.85f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                0.5f,
                                1f,
                            )
                        }
                        .then(
                            if (isMine) {
                                Modifier.background(BackgroundMessageSent, BubbleSentShape)
                            } else {
                                Modifier
                                    .background(BackgroundMessageReceived, BubbleReceivedShape)
                                    .border(1.dp, BorderColor, BubbleReceivedShape)
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        // Attachment > unsupported-control placeholder > text.
                        // The raw text of an unsupported control payload is never
                        // painted (it may carry key material) — see the coordinator.
                        val attachment = message.attachment
                        when {
                            attachment != null -> AttachmentContent(attachment, isMine)
                            message.unsupported -> Text(
                                text = "Unsupported message — update Zitrone",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = TypeScale.Message,
                                color = if (isMine) TextOnLemon.copy(alpha = 0.8f) else TextMuted,
                            )
                            else -> Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = TypeScale.Message,
                                color = if (isMine) TextOnLemon else TextPrimary,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Timestamps render in JetBrains Mono (critical rule).
                            Text(
                                text = TimeFormatter.format(Instant.ofEpochMilli(message.timestampMs)),
                                fontFamily = MonoFamily,
                                fontSize = TypeScale.Xs,
                                color = if (isMine) {
                                    TextOnLemon.copy(alpha = 0.6f)
                                } else {
                                    TextMuted
                                },
                                textAlign = TextAlign.End,
                            )
                            // Timed message: mini lemon-slice countdown.
                            val deliveredAt = message.deliveredAtMs
                            if (message.ttlSeconds != null && deliveredAt != null) {
                                BurnTimer(
                                    deliveredAtMs = deliveredAt,
                                    ttlSeconds = message.ttlSeconds,
                                    size = 14.dp,
                                )
                            }
                            // Burn-on-read: small flame on the bubble corner.
                            if (message.burnOnRead) {
                                Icon(
                                    imageVector = Icons.Filled.LocalFireDepartment,
                                    contentDescription = "Burns after reading",
                                    tint = BurnOrange,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            // Outgoing status: "…" sending, "✓" handed to the
                            // relay (the protocol has no per-device delivered
                            // event), "✓✓" only when the peer's encrypted read
                            // receipt arrives — so the double mark is a real
                            // read confirmation, never an approximation.
                            if (isMine) {
                                Text(
                                    text = when (message.state) {
                                        MessageState.SENDING -> "…"
                                        MessageState.READ -> "✓✓"
                                        else -> "✓"
                                    },
                                    fontFamily = MonoFamily,
                                    fontSize = TypeScale.Xs,
                                    color = if (message.state == MessageState.READ) {
                                        TextOnLemon
                                    } else {
                                        TextOnLemon.copy(alpha = 0.45f)
                                    },
                                )
                            }
                        }
                    }
                }

                // Flame particles rise OVER the collapsing bubble.
                if (burning) {
                    BurnParticles(
                        progress = progress,
                        seed = message.id.hashCode(),
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/**
 * Renders an attachment inside its bubble. Images decode from the in-memory
 * bytes straight into a Bitmap (never a file); files show name + size with an
 * explicit, user-initiated Save. Everything here is under the Activity's
 * FLAG_SECURE, so it is hard-blocked from screenshots. The bytes are the
 * decrypted plaintext and are held only in memory (see MessageRepository).
 */
@Composable
private fun AttachmentContent(attachment: MessageAttachment, isMine: Boolean) {
    val onColor = if (isMine) TextOnLemon else TextPrimary
    val mutedColor = if (isMine) TextOnLemon.copy(alpha = 0.6f) else TextMuted

    when (attachment.loadState) {
        AttachmentLoadState.UNAVAILABLE -> Text(
            text = "Attachment expired or unavailable",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = TypeScale.Message,
            color = mutedColor,
        )
        AttachmentLoadState.LOADING -> Text(
            text = "Fetching attachment…",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = TypeScale.Message,
            color = mutedColor,
        )
        AttachmentLoadState.LOADED -> {
            val bytes = attachment.bytes
            if (attachment.kind == AttachmentControlPayload.KIND_IMAGE && bytes != null) {
                // decodeByteArray → ImageBitmap, kept across recompositions so a
                // large image isn't re-decoded on every frame of the burn timer.
                val imageBitmap = remember(bytes) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(
                        text = "Couldn't display image",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = TypeScale.Message,
                        color = mutedColor,
                    )
                }
            } else if (bytes != null) {
                FileAttachmentRow(attachment, bytes, onColor, mutedColor)
            }
        }
    }

    // Sender caption, rendered under the attachment when present.
    attachment.caption?.let { caption ->
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = TypeScale.Message,
            color = onColor,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * A file attachment: icon, name and size, plus a Save action. Saving is the ONE
 * sanctioned path to disk — user-initiated, via the system document picker (SAF)
 * — exactly like the user choosing to copy a message's text out of the app.
 */
@Composable
private fun FileAttachmentRow(
    attachment: MessageAttachment,
    bytes: ByteArray,
    onColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color,
) {
    val context = LocalContext.current
    // CreateDocument returns a user-chosen destination uri; we write the
    // in-memory bytes to it and nowhere else.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(attachment.mimetype),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = onColor,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.widthIn(max = 180.dp)) {
            Text(
                text = attachment.filename ?: "Attachment",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = TypeScale.Message,
                color = onColor,
                maxLines = 2,
            )
            Text(
                text = humanReadableSize(attachment.size),
                fontFamily = MonoFamily,
                fontSize = TypeScale.Xs,
                color = mutedColor,
            )
        }
        Icon(
            imageVector = Icons.Outlined.SaveAlt,
            contentDescription = "Save file",
            tint = onColor,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { saveLauncher.launch(attachment.filename ?: "attachment") },
        )
    }
}

/** Human-friendly byte count for the file row (e.g. "1.2 MB"). */
private fun humanReadableSize(bytes: Int): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
