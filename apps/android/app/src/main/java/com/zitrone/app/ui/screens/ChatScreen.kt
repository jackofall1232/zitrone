// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zitrone.app.data.Conversation
import com.zitrone.app.data.ConversationRepository
import com.zitrone.app.data.LemonDropCreator
import com.zitrone.app.data.Message
import com.zitrone.app.data.MessageState
import com.zitrone.app.ui.AttachmentLoader
import com.zitrone.app.ui.components.ComposeBar
import com.zitrone.app.ui.components.LemonAvatar
import com.zitrone.app.ui.components.MessageBubble
import com.zitrone.app.ui.components.QrDropResultDialog
import com.zitrone.app.ui.components.QrTtlPickerDialog
import com.zitrone.app.ui.components.fingerprintWatermark
import com.zitrone.app.ui.components.SecurityBadge
import com.zitrone.app.ui.components.SecurityState
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.BackgroundSecondary
import com.zitrone.app.ui.theme.BurnOrange
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import com.zitrone.app.ui.theme.TypeScale
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateDividerFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

/**
 * Chat view (design_system.screens.chat_view): header with avatar, name and
 * the verify/burn-all actions; persistent encryption micro-badge; message
 * list with mono date dividers; compose bar with TTL + burn-on-read.
 *
 * This composable only ever exists inside an Activity that set FLAG_SECURE —
 * everything rendered here is hard-blocked from screenshots at the OS level.
 */
@Composable
fun ChatScreen(
    conversation: Conversation,
    messages: List<Message>,
    peerTyping: Boolean,
    defaultTtlSeconds: Int?,
    defaultBurnOnRead: Boolean,
    ttlOptions: List<Int?>,
    onBack: () -> Unit,
    onVerifyKeys: () -> Unit,
    onBurnAll: () -> Unit,
    /**
     * Local-only display-name rename. Caller persists via the roster;
     * returns true if the name was accepted (non-empty, max length).
     */
    onRename: (newDisplayName: String) -> Boolean = { false },
    onSend: (text: String, ttlSeconds: Int?, burnOnRead: Boolean) -> Unit,
    onSendAttachment: (
        bytes: ByteArray,
        kind: String,
        mimetype: String,
        filename: String?,
        caption: String?,
        ttlSeconds: Int?,
        burnOnRead: Boolean,
    ) -> Unit,
    onMessagesSeen: (messageIds: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onTyping: (Boolean) -> Unit = {},
    /** Re-send a FAILED message (tap-to-retry on its bubble). */
    onRetry: (messageId: String) -> Unit = {},
    /** Tap a received image to reveal it and arm its 10s reveal-and-burn timer. */
    onRevealImage: (messageId: String) -> Unit = {},
    /** This device's own identity fingerprint for the security-paper watermark. */
    identityFingerprint: String? = null,
    /** Seal the current draft into a lemon drop (QR dead-drop) for this contact
     *  under a chosen TTL. Null hides the droplet affordance. Suspends over the
     *  one-shot seal + PoW + deposit; the caller stays on the ChatScreen. */
    onSendAsQrDrop: (suspend (text: String, ttlHours: Int) -> LemonDropCreator.Result)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // Per-message overrides for the compose-bar burn controls. null = no
    // explicit choice, so the CURRENT settings default applies — live: a new
    // chat starts on the real default, and flipping the setting updates the
    // effective value even while this chat is composed. Deliberately plain
    // remember, NOT rememberSaveable: the old code seeded saveable state from
    // the default once, and a restored stale snapshot (process death, config
    // change) then shadowed the setting for good — the Settings toggles
    // looked completely dead.
    var burnOnReadOverride by remember { mutableStateOf<Boolean?>(null) }
    var ttlOverrideIndex by remember { mutableStateOf<Int?>(null) }
    val burnOnRead = burnOnReadOverride ?: defaultBurnOnRead
    val ttlIndex = ttlOverrideIndex ?: ttlOptions.indexOf(defaultTtlSeconds).coerceAtLeast(0)
    val ttlSeconds = ttlOptions.getOrNull(ttlIndex)

    // Attachment picking. Streams / staging files are read STRAIGHT into memory
    // off the main thread (AttachmentLoader) — never kept as durable files.
    // Images (library + camera) go through a preview-before-send sheet; files
    // still prepare-and-send (no image to preview). Caption seeds from draft.
    val context = LocalContext.current
    val pickScope = rememberCoroutineScope()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<PendingImageAttachment?>(null) }
    // Camera TakePicture staging — FileProvider URI + File for zero-persist delete.
    var cameraStaging by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    val prepareImageForPreview: (suspend () -> AttachmentLoader.Prepared) -> Unit = { prepare ->
        pickScope.launch {
            runCatching { prepare() }
                .onSuccess { prepared ->
                    attachmentError = null
                    pendingImage = PendingImageAttachment(
                        prepared = prepared,
                        caption = draft.trim(),
                    )
                }
                .onFailure { e ->
                    attachmentError = when (e) {
                        is AttachmentLoader.TooLargeException -> e.message
                        else -> "Couldn't attach that."
                    }
                }
        }
    }
    val prepareAndSendFile: (suspend () -> AttachmentLoader.Prepared) -> Unit = { prepare ->
        val caption = draft.trim().ifBlank { null }
        pickScope.launch {
            runCatching { prepare() }
                .onSuccess { prepared ->
                    attachmentError = null
                    draft = ""
                    onSendAttachment(
                        prepared.bytes,
                        prepared.kind,
                        prepared.mimetype,
                        prepared.filename,
                        caption,
                        ttlSeconds,
                        burnOnRead,
                    )
                }
                .onFailure { e ->
                    attachmentError = when (e) {
                        is AttachmentLoader.TooLargeException -> e.message
                        else -> "Couldn't attach that."
                    }
                }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) prepareImageForPreview { AttachmentLoader.prepareImage(context, uri) }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) prepareAndSendFile { AttachmentLoader.prepareFile(context, uri) }
    }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val staging = cameraStaging
        cameraStaging = null
        if (staging == null) return@rememberLauncherForActivityResult
        val (uri, file) = staging
        if (success) {
            // Read into memory, then delete the staging file — zero-persistence.
            prepareImageForPreview {
                try {
                    AttachmentLoader.prepareImage(context, uri)
                } finally {
                    file.delete()
                }
            }
        } else {
            file.delete()
        }
    }
    val launchCameraCapture: () -> Unit = {
        val dir = File(context.cacheDir, AttachmentLoader.CAMERA_CAPTURE_DIR).apply { mkdirs() }
        // Unique name so a discarded capture never collides with a later one.
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        cameraStaging = uri to file
        takePicture.launch(uri)
    }

    // Opening the chat (or a new message arriving while it is open) marks
    // incoming messages as seen — the trigger for burn-on-read grace timers
    // and (when enabled) read receipts. Batched: one callback per change-set,
    // so a backlog of unread messages costs one receipt envelope, not N.
    LaunchedEffect(messages) {
        val seen = messages
            .filter { !it.isMine && (it.state == MessageState.DELIVERED || it.state == MessageState.SENT) }
            .map { it.id }
        if (seen.isNotEmpty()) onMessagesSeen(seen)
    }

    // Typing indicator out — sent as encrypted signals, not plaintext.
    LaunchedEffect(draft.isNotBlank()) {
        onTyping(draft.isNotBlank())
    }

    // Lemon-drop creation flow (mirror of the web ChatView's qrDraft/qrResult):
    // the droplet captures the draft and opens the TTL picker; a chosen lifetime
    // seals + deposits; only THEN is the draft cleared and the result shown.
    var qrDraftText by remember { mutableStateOf<String?>(null) }
    var qrSealing by remember { mutableStateOf(false) }
    var qrError by remember { mutableStateOf<String?>(null) }
    // rememberSaveable, not plain remember: the result (drop URL + expiry) is the
    // ONE copy of the live drop link the creator ever gets. A rotation / Activity
    // recreation while this dialog is up would null a plain remember and lose it
    // before they can copy/save/share. A Pair<String, String> is Serializable, so
    // the default saver bundles it. (The transient sealing/picker state stays plain
    // remember — the URL is the only unrecoverable piece.)
    var qrResult by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val qrScope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    if (showRename) {
        EditDisplayNameDialog(
            currentName = conversation.displayName,
            onDismiss = { showRename = false },
            onSave = { candidate ->
                if (onRename(candidate)) {
                    showRename = false
                    true
                } else {
                    false
                }
            },
        )
    }

    pendingImage?.let { pending ->
        AttachmentPreviewDialog(
            pending = pending,
            onCaptionChange = { pendingImage = pending.copy(caption = it) },
            onSend = {
                val p = pendingImage ?: return@AttachmentPreviewDialog
                pendingImage = null
                draft = ""
                onSendAttachment(
                    p.prepared.bytes,
                    p.prepared.kind,
                    p.prepared.mimetype,
                    p.prepared.filename,
                    p.caption.ifBlank { null },
                    ttlSeconds,
                    burnOnRead,
                )
            },
            onDiscard = { pendingImage = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            // Security paper sits above the ground, below the content.
            .fingerprintWatermark(identityFingerprint)
            .imePadding(),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BackgroundSecondary)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Lemon,
                )
            }
            LemonAvatar(name = conversation.displayName, verified = conversation.verified)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    // Tap the name/subtitle to rename — local label only.
                    .clickable { showRename = true },
            ) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = if (peerTyping) "typing…" else "Encrypted · tap name to edit",
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Xs,
                    color = if (peerTyping) Lemon else TextMuted,
                )
            }
            // Verify keys — lemon slice icon (SecurityBadge handles states).
            SecurityBadge(
                state = when {
                    conversation.keyChanged -> SecurityState.WARNING
                    conversation.verified -> SecurityState.VERIFIED
                    else -> SecurityState.UNVERIFIED
                },
                onClick = onVerifyKeys,
            )
            // Burn all.
            IconButton(onClick = onBurnAll) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = "Burn every message in this chat",
                    tint = BurnOrange,
                )
            }
        }

        // Persistent encryption micro-badge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🔒 End-to-end encrypted",
                style = MaterialTheme.typography.labelMedium,
                color = Lemon.copy(alpha = 0.55f),
            )
        }

        // Message list with mono date dividers.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var lastDate: LocalDate? = null
            messages.forEach { message ->
                val messageDate = Instant.ofEpochMilli(message.timestampMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                if (messageDate != lastDate) {
                    lastDate = messageDate
                    item(key = "divider-$messageDate") {
                        DateDivider(date = messageDate)
                    }
                }
                item(key = message.id) {
                    MessageBubble(
                        message = message,
                        onRetry = { onRetry(message.id) },
                        onRevealImage = { onRevealImage(message.id) },
                    )
                }
            }
        }

        // Attachment error (oversize file, undecodable image) — transient,
        // cleared on the next successful pick or send.
        attachmentError?.let { error ->
            Text(
                text = error,
                fontFamily = MonoFamily,
                fontSize = TypeScale.Xs,
                color = BurnOrange,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ComposeBar(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                if (draft.isNotBlank()) {
                    onSend(draft.trim(), ttlSeconds, burnOnRead)
                    draft = ""
                }
            },
            burnOnRead = burnOnRead,
            onToggleBurnOnRead = { burnOnReadOverride = !burnOnRead },
            ttlSeconds = ttlSeconds,
            onCycleTtl = { ttlOverrideIndex = (ttlIndex + 1) % ttlOptions.size },
            onAttachImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            // "*/*" — any document. SAF grants a one-shot read on the returned
            // uri; we consume it straight into memory and never persist it.
            onAttachFile = { filePicker.launch(arrayOf("*/*")) },
            onAttachCamera = if (hasCamera) launchCameraCapture else null,
            onSendAsQrDrop = onSendAsQrDrop?.let {
                {
                    if (draft.isNotBlank()) {
                        qrError = null
                        qrDraftText = draft.trim()
                    }
                }
            },
        )
    }

    qrDraftText?.let { text ->
        QrTtlPickerDialog(
            sealing = qrSealing,
            error = qrError,
            onPick = { hours ->
                val seal = onSendAsQrDrop ?: return@QrTtlPickerDialog
                if (qrSealing) return@QrTtlPickerDialog
                qrSealing = true
                qrError = null
                qrScope.launch {
                    when (val res = seal(text, hours)) {
                        is LemonDropCreator.Result.Success -> {
                            qrResult = res.url to res.expiresAt
                            // Clear the draft ONLY after a successful deposit — a
                            // cancel or failed seal leaves the user's only copy in
                            // the box (web discipline).
                            draft = ""
                            qrDraftText = null
                        }
                        LemonDropCreator.Result.IdentityChanged ->
                            qrError = "This contact's key changed — the drop was refused."
                        LemonDropCreator.Result.TooLarge ->
                            qrError = "This message is too long to seal into a QR drop — shorten it and try again."
                        LemonDropCreator.Result.StaleRelay ->
                            qrError =
                                "The relay doesn't support QR drops yet (stale server build). Redeploy the relay, then try again."
                        LemonDropCreator.Result.RecipientUnavailable ->
                            qrError =
                                "This contact isn't reachable right now — they may have reset their account."
                        LemonDropCreator.Result.Failed ->
                            qrError = "Couldn't create the drop — try again."
                    }
                    qrSealing = false
                }
            },
            onCancel = {
                if (!qrSealing) {
                    qrDraftText = null
                    qrError = null
                }
            },
        )
    }

    qrResult?.let { (url, expiresAt) ->
        QrDropResultDialog(
            url = url,
            expiresAt = expiresAt,
            recipientName = conversation.displayName,
            onClose = { qrResult = null },
        )
    }
}

/**
 * In-memory image ready for the user to review before send. Bytes live only
 * here until Send (crypto) or Discard (dropped for GC) — never written back
 * to disk after AttachmentLoader returns.
 */
private data class PendingImageAttachment(
    val prepared: AttachmentLoader.Prepared,
    val caption: String,
)

/**
 * Preview-before-send for library and camera images. Capture-and-send is
 * forbidden: the shutter only stages bytes; this dialog is the commit gate.
 * No watermark on the send path (display-only security paper stays off attachments).
 */
@Composable
private fun AttachmentPreviewDialog(
    pending: PendingImageAttachment,
    onCaptionChange: (String) -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    val previewBitmap = remember(pending.prepared.bytes) {
        BitmapFactory.decodeByteArray(
            pending.prepared.bytes,
            0,
            pending.prepared.bytes.size,
        )
    }
    AlertDialog(
        onDismissRequest = onDiscard,
        title = {
            Text("Send photo?", color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Photo preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "Preview unavailable — you can still send.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = pending.caption,
                    onValueChange = onCaptionChange,
                    label = { Text("Caption (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lemon,
                        focusedLabelColor = Lemon,
                        cursorColor = Lemon,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text("Send", color = Lemon)
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text("Discard", color = TextSecondary)
            }
        },
        containerColor = BackgroundElevated,
    )
}

/**
 * Rename dialog for the local-only contact label. Validation matches
 * [ConversationRepository.sanitizeDisplayName] (non-empty, max length,
 * control-char strip). Nothing crypto-related is edited here.
 */
@Composable
private fun EditDisplayNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    /** Returns true when the name was accepted and persisted. */
    onSave: (String) -> Boolean,
) {
    var draft by remember(currentName) { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    val trySave = {
        val candidate = draft
        when {
            ConversationRepository.sanitizeDisplayName(candidate) == null -> {
                error = if (candidate.trim().isEmpty()) {
                    "Name can’t be empty"
                } else {
                    "Name is too long (max ${ConversationRepository.DISPLAY_NAME_MAX_LEN})"
                }
            }
            onSave(candidate) -> Unit
            else -> error = "Couldn’t save that name"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit name",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = "Only stored on this device. Never sent or synced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { input ->
                        // Soft cap the raw input; never split a surrogate pair
                        // (an emoji) in half — an orphaned surrogate is invalid
                        // UTF-16 and can crash rendering. sanitizeDisplayName does
                        // the real length check on save.
                        val cap = ConversationRepository.DISPLAY_NAME_MAX_LEN + 8
                        draft = if (input.length <= cap) {
                            input
                        } else {
                            val end = if (Character.isHighSurrogate(input[cap - 1])) cap - 1 else cap
                            input.substring(0, end)
                        }
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg ->
                        { Text(text = msg, color = ErrorRed) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { trySave() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lemon,
                        focusedLabelColor = Lemon,
                        cursorColor = Lemon,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = trySave) {
                Text(text = "Save", color = Lemon, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = BackgroundElevated,
    )
}

@Composable
private fun DateDivider(date: LocalDate) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Date dividers: centered, JetBrains Mono, muted (chat_view spec).
        Text(
            text = DateDividerFormatter.format(date),
            fontFamily = MonoFamily,
            fontSize = TypeScale.Xs,
            color = TextMuted,
        )
    }
}
