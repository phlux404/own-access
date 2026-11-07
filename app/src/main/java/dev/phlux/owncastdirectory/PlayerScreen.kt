package dev.phlux.owncastdirectory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.phlux.owncastdirectory.data.ChatMessage
import dev.phlux.owncastdirectory.data.OwncastChat
import dev.phlux.owncastdirectory.data.StreamEntry
import kotlinx.coroutines.launch

/**
 * Full-screen player: the video on top (16:9), the stream's chat below it.
 * Chat is read/write while the instance gives us a real WebSocket; on
 * instances whose proxies force the polling fallback the panel is
 * read-only and the input says so. If the stream URL isn't an Owncast
 * /hls/stream.m3u8 URL we cannot derive the instance, and if the instance
 * has chat disabled (or refuses registration) the panel says so plainly.
 */
@Composable
fun PlayerScreen(
    stream: StreamEntry,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var chatState by remember { mutableStateOf("connecting") }
    var chatRef by remember { mutableStateOf<OwncastChat?>(null) }
    var draft by remember { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val instanceBase = remember(stream.streamUrl) {
        stream.streamUrl.removeSuffix("/hls/stream.m3u8")
    }
    val chatPossible = instanceBase != stream.streamUrl

    LaunchedEffect(stream.streamUrl) {
        player.setMediaItem(MediaItem.fromUri(stream.streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(stream.streamUrl) {
        val chat = if (chatPossible) {
            OwncastChat(
                instanceBase = instanceBase,
                displayName = displayName,
                onMessage = { msg ->
                    scope.launch {
                        if (messages.size >= MAX_MESSAGES) messages.removeAt(0)
                        messages.add(msg)
                    }
                },
                onState = { state -> scope.launch { chatState = state } },
            )
        } else {
            null
        }
        chatRef = chat
        chat?.start(scope)
        onDispose { chat?.close() }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to directory",
                    tint = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stream.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    chatStatusLabel(chatPossible, chatState),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chatPossible) {
                    TextButton(onClick = { showNameDialog = true }) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (showNameDialog) {
                var nameDraft by remember { mutableStateOf(displayName) }
                AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { Text("Chat display name") },
                    text = {
                        Column {
                            Text("The name you chat under. Applies from the next stream you open.")
                            OutlinedTextField(
                                value = nameDraft,
                                onValueChange = { nameDraft = it },
                                label = { Text("Display name") },
                                singleLine = true,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onDisplayNameChange(nameDraft.trim())
                            showNameDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
                    },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
            when {
                !chatPossible -> ChatNotice("Chat isn't available for this stream.")
                chatState.startsWith("error:") ->
                    ChatNotice("Chat couldn't connect — " + chatState.removePrefix("error:"))
                messages.isEmpty() -> ChatNotice(
                    if (chatState == "live" || chatState == "polling") {
                        "Chat is live — nothing yet. Quiet room."
                    } else {
                        "Connecting to chat…"
                    },
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatRow(msg)
                    }
                }
            }
            }

            if (chatPossible) {
                val canSend = chatState == "live"
                val trySend = {
                    val text = draft
                    if (canSend && text.isNotBlank() && chatRef?.send(text) == true) {
                        draft = ""
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = canSend,
                        placeholder = {
                            Text(
                                when {
                                    canSend -> "Message as $displayName"
                                    chatState == "polling" ->
                                        "Read-only here — this instance's proxy blocks live chat"
                                    chatState.startsWith("error:") -> "Chat unavailable"
                                    else -> "Connecting to chat…"
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { trySend() }),
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { trySend() },
                        enabled = canSend && draft.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                        )
                    }
                }
            }
        }
    }
}

private fun chatStatusLabel(chatPossible: Boolean, chatState: String): String {
    if (!chatPossible) return "no chat"
    return when {
        chatState == "live" || chatState == "polling" -> "chat · live"
        chatState.startsWith("error:") -> "chat · error"
        else -> "chat · …"
    }
}

@Composable
private fun ChatNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChatRow(msg: ChatMessage) {
    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(
            msg.userName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "  " + msg.bodyText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val MAX_MESSAGES = 150
