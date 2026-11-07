package dev.phlux.owncastdirectory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.phlux.owncastdirectory.data.Category
import dev.phlux.owncastdirectory.data.DirectoryFeed
import dev.phlux.owncastdirectory.data.InstanceProbe
import dev.phlux.owncastdirectory.data.OwncastChat
import dev.phlux.owncastdirectory.data.StreamEntry
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Root()
        }
    }
}

private const val THEME_SYSTEM = "system"
private const val THEME_LIGHT = "light"
private const val THEME_DARK = "dark"

@Composable
private fun Root() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("odc_prefs", Context.MODE_PRIVATE)
    }
    var themeMode by remember {
        mutableStateOf(prefs.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM)
    }
    val dark = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode = it
                    prefs.edit().putString("theme_mode", it).apply()
                },
            )
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val streams: List<StreamEntry>) : LoadState
    data class Failed(val message: String) : LoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var selected by remember { mutableStateOf<Category?>(null) } // null = All
    var playing by remember { mutableStateOf<StreamEntry?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("odc_prefs", Context.MODE_PRIVATE)
    }
    var displayName by remember {
        mutableStateOf(prefs.getString("display_name", "") ?: "")
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var customs by remember { mutableStateOf(loadCustoms(prefs)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<StreamEntry?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        state = LoadState.Loading
        state = try {
            LoadState.Ready(withContext(Dispatchers.IO) { DirectoryFeed.fetch() })
        } catch (e: Exception) {
            LoadState.Failed(e.message ?: "Could not load the directory")
        }
    }

    if (playing != null) {
        BackHandler { playing = null }
        PlayerScreen(
            stream = playing!!,
            displayName = displayName.ifBlank { OwncastChat.DEFAULT_DISPLAY_NAME },
            onDisplayNameChange = { name ->
                displayName = name
                prefs.edit().putString("display_name", name).apply()
            },
            onBack = { playing = null },
        )
        return
    }

    if (showAddDialog) {
        var urlDraft by remember { mutableStateOf("") }
        var nameDraft by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }
        var addBusy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!addBusy) showAddDialog = false },
            title = { Text("Add an Owncast instance") },
            text = {
                Column {
                    Text(
                        "For instances the directory doesn't list. " +
                            "Paste the instance address (or its stream URL) — " +
                            "we check it answers like Owncast before saving.",
                    )
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("Instance URL") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (addError != null) {
                        Text(
                            addError!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !addBusy && urlDraft.isNotBlank(),
                    onClick = {
                        addBusy = true
                        addError = null
                        val base = normalizeInstanceUrl(urlDraft)
                        scope.launch {
                            try {
                                val title = withContext(Dispatchers.IO) {
                                    InstanceProbe.probe(base)
                                }
                                val entry = StreamEntry(
                                    name = nameDraft.trim().ifEmpty {
                                        title ?: base.removePrefix("https://")
                                            .removePrefix("http://")
                                    },
                                    logoUrl = null,
                                    tags = emptyList(),
                                    streamUrl = base + "/hls/stream.m3u8",
                                    isCustom = true,
                                )
                                customs = (customs + entry)
                                    .distinctBy { it.streamUrl }
                                saveCustoms(prefs, customs)
                                showAddDialog = false
                            } catch (e: Exception) {
                                addError = "Couldn't reach an Owncast instance " +
                                    "at $base — ${e.message ?: "no response"}"
                                addBusy = false
                            }
                        }
                    },
                ) { Text(if (addBusy) "Checking…" else "Check & add") }
            },
            dismissButton = {
                TextButton(
                    enabled = !addBusy,
                    onClick = { showAddDialog = false },
                ) { Text("Cancel") }
            },
        )
    }

    if (removeTarget != null) {
        val target = removeTarget!!
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove custom instance?") },
            text = { Text(target.name + " — " + target.streamUrl) },
            confirmButton = {
                TextButton(onClick = {
                    customs = customs.filter { it.streamUrl != target.streamUrl }
                    saveCustoms(prefs, customs)
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showNameDialog) {
        var nameDraft by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text(
                        "The name you chat under on every instance. " +
                            "Blank means \"${OwncastChat.DEFAULT_DISPLAY_NAME}\".",
                    )
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = themeMode == THEME_SYSTEM,
                            onClick = { onThemeModeChange(THEME_SYSTEM) },
                            label = { Text("System") },
                        )
                        FilterChip(
                            selected = themeMode == THEME_LIGHT,
                            onClick = { onThemeModeChange(THEME_LIGHT) },
                            label = { Text("Light") },
                        )
                        FilterChip(
                            selected = themeMode == THEME_DARK,
                            onClick = { onThemeModeChange(THEME_DARK) },
                            label = { Text("Dark") },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    displayName = nameDraft.trim()
                    prefs.edit().putString("display_name", displayName).apply()
                    showNameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Own Access") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add instance by URL")
                    }
                    IconButton(onClick = { showNameDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Chat name")
                    }
                    TextButton(onClick = { reloadKey++ }) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                LoadState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is LoadState.Failed -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't reach the directory.", style = MaterialTheme.typography.titleMedium)
                    Text(s.message, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { reloadKey++ }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Try again")
                    }
                }

                is LoadState.Ready -> {
                    val all = (customs + s.streams).distinctBy { it.streamUrl }
                    CategoryChips(
                        streams = all,
                        selected = selected,
                        onSelect = { selected = it },
                    )
                    val shown = if (selected == null) all
                        else all.filter { selected in it.categories }
                    if (shown.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Nobody live in ${selected?.label ?: "the directory"} right now.",
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.streamUrl }) { stream ->
                                StreamCard(
                                    stream = stream,
                                    onClick = { playing = stream },
                                    onLongPress = if (stream.isCustom) {
                                        { removeTarget = stream }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(
    streams: List<StreamEntry>,
    selected: Category?,
    onSelect: (Category?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All (${streams.size})") },
            )
        }
        items(Category.entries) { category ->
            val count = streams.count { category in it.categories }
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text("${category.label} ($count)") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamCard(
    stream: StreamEntry,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (onLongPress != null) {
                Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                )
            } else {
                Modifier.clickable(onClick = onClick)
            },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                if (stream.logoUrl != null) {
                    AsyncImage(
                        model = stream.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                } else {
                    Text(
                        stream.name.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
            Text(
                stream.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (stream.isCustom) {
                Text(
                    "Custom instance · long-press to remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            } else if (stream.tags.isNotEmpty()) {
                Text(
                    stream.tags.take(4).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** Normalizes what the user pasted into an instance base URL. */
private fun normalizeInstanceUrl(raw: String): String {
    var u = raw.trim()
    if (!u.startsWith("http://") && !u.startsWith("https://")) {
        u = "https://$u"
    }
    u = u.removeSuffix("/")
    u = u.removeSuffix("/hls/stream.m3u8")
    u = u.removeSuffix("/")
    return u
}

private fun loadCustoms(prefs: android.content.SharedPreferences): List<StreamEntry> {
    val raw = prefs.getString("custom_instances", "") ?: ""
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(raw)
        val out = mutableListOf<StreamEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val base = obj.getString("base")
            out += StreamEntry(
                name = obj.optString("name").ifEmpty { base },
                logoUrl = null,
                tags = emptyList(),
                streamUrl = base + "/hls/stream.m3u8",
                isCustom = true,
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveCustoms(
    prefs: android.content.SharedPreferences,
    customs: List<StreamEntry>,
) {
    val arr = org.json.JSONArray()
    for (entry in customs) {
        arr.put(
            org.json.JSONObject()
                .put("base", entry.streamUrl.removeSuffix("/hls/stream.m3u8"))
                .put("name", entry.name),
        )
    }
    prefs.edit().putString("custom_instances", arr.toString()).apply()
}
