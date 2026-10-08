package com.codex.remote.ui

import com.codex.remote.R
import com.codex.remote.ui.uiText

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.remote.*
import com.codex.remote.data.diagnostics.DebugApi
import com.codex.remote.data.store.*
import com.codex.remote.ui.screens.ConnectionsScreen
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Composable
fun CodexRemoteApp(manager: AppViewModel) {
    val managed by manager.state.collectAsState()
    val local: LocalViewModel = viewModel()
    val localState by local.state.collectAsState()
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf("remote") }
    var settings by remember { mutableStateOf(false) }
    var providerEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var allHosts by rememberSaveable { mutableStateOf(false) }
    var pendingThread by remember { mutableStateOf<Pair<String, RemoteThread>?>(null) }
    val hostModels = linkedMapOf<String, AppViewModel>()
    val hostStates = linkedMapOf<String, AppUiState>()
    managed.savedConnections.forEach { saved ->
        val host: AppViewModel = viewModel(viewModelStoreOwner = HostSessions, key = "host-${saved.id}", factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, autoRestore = false) as T
        })
        hostModels[saved.id] = host
        val snapshot by host.state.collectAsState()
        hostStates[saved.id] = snapshot
    }
    BackHandler(enabled = !managed.showConnectionEditor && !settings && !providerEditor) {
        if (managed.showConnections && managed.activeConnection != null) manager.showConnections(false)
        else (context as? Activity)?.moveTaskToBack(true)
    }
    LaunchedEffect(mode) { if (mode == "chat" || mode == "work") local.mode(mode == "work") }
    LaunchedEffect(localState.selected?.workspace) { if (mode == "chat" && localState.selected?.workspace == true) mode = "work" }
    SideEffect {
        DebugApi.state = { buildJsonObject {
            put("schemaVersion", 1)
            put("mode", mode)
            put("savedHostCount", managed.savedConnections.size)
            put("providerCount", localState.providers.size)
            put("localConversationCount", localState.conversations.size)
            put("sending", localState.sending)
            put("cacheBytes", localState.cacheBytes)
            put("connectedHostCount", hostStates.values.count { it.connectionStatus == ConnectionStatus.CONNECTED })
            put("remoteThreadCount", hostStates.values.sumOf { it.threads.size })
            put("remoteReadOnly", hostStates.values.any { it.isReadOnlyThread })
        } }
    }
    if (providerEditor) {
        ProviderEditor(editingProvider, localState, local, { providerEditor = false })
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("chat" to uiText(R.string.ui_250, "Chat"), "work" to uiText(R.string.ui_251, "Work"), "remote" to "Codex", "cloud" to uiText(R.string.ui_252, "Cloud")).forEach { (value, label) ->
                    FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) }, shape = CircleShape,
                        enabled = !localState.sending || value != "chat" && value != "work" || value == if (localState.selected?.workspace == true) "work" else "chat")
                }
            }
            IconButton(onClick = { settings = true; local.refresh() }) { Icon(Icons.Outlined.Settings, contentDescription = uiText(R.string.ui_253, "Settings")) }
        }
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            when (mode) {
                "chat", "work" -> LocalPane(local, mode == "work") { provider -> editingProvider = provider; providerEditor = true }
                "cloud" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CloudQueue, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(uiText(R.string.ui_254, "No tasks"), style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> {
                    if (managed.showConnections || managed.activeConnection == null) {
                        ConnectionsScreen(managed, { manager.showConnections(false) }, { manager.editConnection() }, manager::editConnection,
                            { saved -> hostModels[saved.id]?.disconnect(); manager.deleteConnection(saved) }, manager::connect, manager::saveConnection, manager::closeEditor, manager::clearNotice)
                    } else {
                        val connection = managed.activeConnection!!
                        val host = hostModels[connection.id] ?: return@Box
                        LaunchedEffect(host, connection.id, managed.connectionMessage) {
                            if (host.state.value.activeConnection == null || managed.connectionMessage.startsWith("reconnect-")) host.connect(connection)
                        }
                        LaunchedEffect(pendingThread, hostStates[connection.id]?.connectionStatus) {
                            val pending = pendingThread
                            if (pending?.first == connection.id && hostStates[connection.id]?.connectionStatus == ConnectionStatus.CONNECTED) {
                                host.selectThread(pending.second)
                                pendingThread = null
                            }
                        }
                        Column {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(allHosts, { allHosts = true }, label = { Text(uiText(R.string.all_hosts, "All")) }, shape = CircleShape)
                                managed.savedConnections.forEach { saved ->
                                    FilterChip(!allHosts && saved.id == connection.id, { allHosts = false; if (saved.id != connection.id) manager.connect(saved) }, label = { Text(saved.name) }, shape = CircleShape)
                                }
                                IconButton(onClick = { manager.showConnections(true) }) { Icon(Icons.Outlined.AddLink, contentDescription = uiText(R.string.ui_227, "Connections")) }
                            }
                            Box(Modifier.weight(1f)) {
                                if (allHosts) HostOverview(managed.savedConnections, hostStates) { saved, thread ->
                                    allHosts = false
                                    pendingThread = saved.id to thread
                                    if (saved.id != connection.id) manager.connect(saved)
                                }
                                else RemoteWorkspace(host) { manager.showConnections(true) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (settings) SettingsDialog(localState, local, onDismiss = { settings = false }, onAddProvider = { settings = false; editingProvider = null; providerEditor = true })
}

@Composable
private fun HostOverview(hosts: List<SavedConnection>, live: Map<String, AppUiState>, onOpen: (SavedConnection, RemoteThread) -> Unit) {
    val context = LocalContext.current
    var cached by remember { mutableStateOf<Map<String, RemoteIndex>>(emptyMap()) }
    LaunchedEffect(hosts.map { it.id }) {
        cached = withContext(Dispatchers.IO) { hosts.mapNotNull { host -> runCatching { LocalStore(context).index(host.id) }.getOrNull()?.let { host.id to it } }.toMap() }
    }
    val rows = hosts.flatMap { host ->
        val threads = live[host.id]?.threads?.takeIf { it.isNotEmpty() } ?: cached[host.id]?.threads.orEmpty()
        threads.map { host to it }
    }.sortedWith(compareByDescending<Pair<SavedConnection, RemoteThread>> { it.second.status.contains("active", true) || it.second.status.contains("running", true) }
        .thenByDescending { it.second.updatedAt }.thenBy { it.second.id })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (rows.isEmpty()) item { Text(uiText(R.string.ui_254, "No tasks"), style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { "${it.first.id}/${it.second.id}" }) { (host, thread) ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(host, thread) }.padding(vertical = 6.dp)) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(host.name, style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(thread.cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalPane(viewModel: LocalViewModel, workspace: Boolean, editProvider: (ApiProvider?) -> Unit) {
    val state by viewModel.state.collectAsState()
    var listOpen by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFile) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) exportFile?.let { viewModel.export(it, uri) }
    }
    val provider = state.providers.firstOrNull { it.id == state.providerId }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { listOpen = true; viewModel.refresh() }) { Icon(Icons.Outlined.Menu, contentDescription = uiText(R.string.ui_255, "Conversations")) }
            Text(state.selected?.title ?: if (workspace) uiText(R.string.ui_256, "Workspaces") else uiText(R.string.ui_250, "Chat"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (workspace && state.selected != null) IconButton(onClick = { filesOpen = true }) { Icon(Icons.Outlined.FolderOpen, contentDescription = uiText(R.string.ui_257, "Files")) }
            if (workspace && state.selected != null) IconButton(onClick = viewModel::generateImage, enabled = !state.sending && state.selected?.draft?.isNotBlank() == true && provider != null) {
                Icon(Icons.Outlined.Image, contentDescription = uiText(R.string.generate_image, "Generate image"))
            }
            if (!workspace && state.selected != null) IconButton(onClick = viewModel::promote, enabled = !state.sending) { Icon(Icons.Outlined.CreateNewFolder, contentDescription = uiText(R.string.ui_258, "Save to workspace")) }
            IconButton(onClick = { viewModel.newConversation() }, enabled = !state.sending) { Icon(Icons.Outlined.EditNote, contentDescription = uiText(R.string.ui_259, "New chat")) }
        }
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = viewModel::dismissError) { Icon(Icons.Outlined.Close, contentDescription = uiText(R.string.ui_260, "Dismiss")) }
            }
        }
        if (state.selected?.messages.isNullOrEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(if (workspace) Icons.Outlined.FolderOpen else Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(state.selected?.messages?.lastOrNull()?.text) {
                if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == (state.selected?.messages?.size ?: 0) - 1) listState.scrollToItem((state.selected?.messages?.size ?: 1) - 1)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(state.selected!!.messages, key = { it.id }) { message ->
                    if (message.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 340.dp)) {
                                Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                        }
                    } else com.codex.remote.ui.screens.MarkdownBody(message.text.ifBlank { if (state.sending) uiText(R.string.ui_087, "Working") else "" })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { modelsOpen = true }) {
                    Text(provider?.let { listOf(it.name, it.selectedModel, it.effort).filter(String::isNotBlank).joinToString(" · ") } ?: uiText(R.string.ui_261, "Add API provider"), modifier = Modifier.widthIn(max = 260.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(modelsOpen, { modelsOpen = false }, modifier = Modifier.heightIn(max = 460.dp)) {
                    state.providers.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.name) }, onClick = { viewModel.provider(candidate.id); modelsOpen = false }, leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) }) }
                    if (provider != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(uiText(R.string.ui_067, "Compact context")) }, enabled = !state.sending && provider.protocol == ApiProtocol.RESPONSES && !state.selected?.messages.isNullOrEmpty(), onClick = { viewModel.compact(); modelsOpen = false }, leadingIcon = { Icon(Icons.Outlined.Compress, contentDescription = null) })
                        provider.models.forEach { model -> DropdownMenuItem(text = { Text(model.id) }, onClick = { viewModel.selectModel(model.id); modelsOpen = false }) }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(uiText(R.string.ui_262, "Default reasoning")) }, onClick = { viewModel.selectModel(provider.selectedModel); modelsOpen = false })
                        provider.models.firstOrNull { it.id == provider.selectedModel }?.efforts.orEmpty().forEach { effort ->
                            DropdownMenuItem(text = { Text(effort) }, onClick = { viewModel.selectModel(provider.selectedModel, effort); modelsOpen = false })
                        }
                        DropdownMenuItem(text = { Text(uiText(R.string.ui_263, "Fetch models")) }, onClick = { viewModel.models(provider); modelsOpen = false }, leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) })
                        DropdownMenuItem(text = { Text(uiText(R.string.ui_264, "Edit provider")) }, onClick = { modelsOpen = false; editProvider(provider) }, leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) })
                    }
                    DropdownMenuItem(text = { Text(uiText(R.string.ui_261, "Add API provider")) }, onClick = { modelsOpen = false; editProvider(null) }, leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) })
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.selected?.draft.orEmpty(), onValueChange = viewModel::draft,
                placeholder = { Text(uiText(R.string.ui_265, "Message")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), maxLines = 6,
            )
            FilledIconButton(onClick = if (state.sending) viewModel::stop else viewModel::send, modifier = Modifier.size(48.dp), enabled = state.sending || state.selected?.draft?.isNotBlank() == true && provider != null) {
                Icon(if (state.sending) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, contentDescription = if (state.sending) uiText(R.string.ui_266, "Stop") else uiText(R.string.ui_146, "Send"))
            }
        }
    }
    if (listOpen) ModalBottomSheet(onDismissRequest = { listOpen = false }) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (workspace) uiText(R.string.ui_256, "Workspaces") else uiText(R.string.ui_255, "Conversations"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            FilterChip(archived, { archived = !archived }, label = { Text(uiText(R.string.ui_267, "Archived")) }, shape = CircleShape)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(16.dp)) {
            items(state.conversations.filter { it.archived == archived }, key = { it.id }) { conversation ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.sending) { viewModel.open(conversation); listOpen = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (workspace) Icons.Outlined.FolderOpen else Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { viewModel.archive(conversation) }, enabled = !state.sending) { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = if (archived) uiText(R.string.ui_268, "Restore") else uiText(R.string.ui_072, "Archive")) }
                }
            }
        }
    }
    if (filesOpen) AlertDialog(onDismissRequest = { filesOpen = false }, title = { Text(uiText(R.string.ui_269, "Workspace files")) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            state.files.forEach { file ->
                if (file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
                    val bitmap = remember(file.path, file.length()) { android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = file.name, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                IconButton(onClick = { exportFile = file; exporter.launch(file.name) }) { Icon(Icons.Outlined.Download, contentDescription = uiText(R.string.ui_270, "Export")) }
            } }
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.Add, contentDescription = null); Text(uiText(R.string.ui_271, "Import file")) }
            TextButton(onClick = viewModel::saveReply) { Icon(Icons.Outlined.Save, contentDescription = null); Text(uiText(R.string.ui_272, "Save latest reply")) }
        }
    }, confirmButton = { TextButton(onClick = { filesOpen = false }) { Text(uiText(R.string.ui_151, "Close")) } })
}

@Composable
private fun SettingsDialog(state: LocalUiState, local: LocalViewModel, onDismiss: () -> Unit, onAddProvider: () -> Unit) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var debugEnabled by remember { mutableStateOf(DebugApi.enabled()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText(R.string.ui_253, "Settings")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText(R.string.ui_273, "System language"), style = MaterialTheme.typography.labelLarge)
            Text(java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault()))
            HorizontalDivider()
            TextButton(onClick = onAddProvider) { Icon(Icons.Outlined.AddLink, contentDescription = null); Text(uiText(R.string.ui_261, "Add API provider")) }
            Text(uiText(R.string.ui_274, "Cache"), style = MaterialTheme.typography.labelLarge)
            Text(android.text.format.Formatter.formatFileSize(context, state.cacheBytes))
            TextButton(onClick = { confirmClear = true }, enabled = !state.sending) { Icon(Icons.Outlined.DeleteOutline, contentDescription = null); Text(uiText(R.string.ui_275, "Clear cache")) }
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiText(R.string.ui_276, "Debug API"), Modifier.weight(1f))
                    Switch(debugEnabled, { enabled -> runCatching { DebugApi.enable(enabled) }.onSuccess { debugEnabled = enabled }.onFailure { error = it.message } }, modifier = Modifier.semantics { contentDescription = uiText(R.string.ui_276, "Debug API") })
                }
                if (debugEnabled) TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Debug API token", DebugApi.token))
                }) { Icon(Icons.Outlined.ContentCopy, contentDescription = null); Text(uiText(R.string.ui_277, "Copy debug token")) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.ui_151, "Close")) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(uiText(R.string.ui_278, "Clear cache?")) }, text = { Text(uiText(R.string.ui_279, "Cached remote messages and local chats will be removed. Workspace files, saved hosts and API credentials are kept.")) }, confirmButton = {
        TextButton(onClick = { local.clearCache(); confirmClear = false }) { Text(uiText(R.string.ui_036, "Clear")) }
    }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(uiText(R.string.ui_037, "Cancel")) } })
}

@Composable
private fun ProviderEditor(original: ApiProvider?, state: LocalUiState, local: LocalViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var url by remember { mutableStateOf(original?.baseUrl ?: "https://api.openai.com/v1") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(original?.selectedModel.orEmpty()) }
    var efforts by remember { mutableStateOf(original?.models?.firstOrNull { it.id == model }?.efforts?.joinToString(", ").orEmpty()) }
    var effort by remember { mutableStateOf(original?.effort.orEmpty()) }
    var protocol by remember { mutableStateOf(original?.protocol ?: ApiProtocol.CHAT_COMPLETIONS) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = uiText(R.string.ui_151, "Close")) }
                    Text(if (original == null) uiText(R.string.ui_261, "Add API provider") else uiText(R.string.ui_264, "Edit provider"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (original != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = uiText(R.string.ui_280, "Delete provider")) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(uiText(R.string.ui_281, "Provider name")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(uiText(R.string.ui_282, "Base URL")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text(if (original == null) uiText(R.string.ui_173, "API key") else uiText(R.string.ui_283, "API key (leave blank to keep)")) }, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiProtocol.entries.forEach { value -> FilterChip(protocol == value, { protocol = value }, label = { Text(if (value == ApiProtocol.RESPONSES) "Responses" else "Chat Completions") }, shape = CircleShape) }
                    }
                    OutlinedTextField(model, { model = it }, label = { Text(uiText(R.string.ui_284, "Model ID")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(efforts, { efforts = it }, label = { Text(uiText(R.string.ui_285, "Supported reasoning efforts (comma separated)")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(effort, { effort = it }, label = { Text(uiText(R.string.ui_286, "Reasoning effort (optional)")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Button(onClick = { local.saveProvider(original, name, url, key, protocol, model, efforts, effort, onDismiss) }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = CircleShape) { Text(uiText(R.string.ui_038, "Save")) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(uiText(R.string.ui_287, "Delete provider?")) }, text = { Text(uiText(R.string.ui_288, "Saved credentials will be removed. Conversations and workspace files are kept.")) }, confirmButton = { TextButton(onClick = { original?.let(local::deleteProvider); onDismiss() }) { Text(uiText(R.string.ui_161, "Delete")) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiText(R.string.ui_037, "Cancel")) } })
}
