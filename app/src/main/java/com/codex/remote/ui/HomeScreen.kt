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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexRemoteApp(manager: AppViewModel) {
    val managed by manager.state.collectAsState()
    val local: LocalViewModel = viewModel()
    val localState by local.state.collectAsState()
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf("chat") }
    var settings by remember { mutableStateOf(false) }
    var providerEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var allHosts by rememberSaveable { mutableStateOf(false) }
    var pendingThread by remember { mutableStateOf<Pair<String, RemoteThread>?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var archived by rememberSaveable { mutableStateOf(false) }
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
    if (settings) {
        SettingsPage(localState, local, onDismiss = { settings = false }, onEditProvider = { editingProvider = it; providerEditor = true })
        return
    }
    val navigate: (String) -> Unit = { target ->
        mode = target
        scope.launch { drawer.close() }
    }
    val navigation: @Composable () -> Unit = {
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_250, "Chat")) }, selected = mode == "chat", icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = { navigate("chat") })
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_251, "Work")) }, selected = mode == "work", icon = { Icon(Icons.Outlined.WorkOutline, null) }, onClick = { navigate("work") })
        NavigationDrawerItem(label = { Text("Codex") }, selected = mode == "remote", icon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { navigate("remote") })
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_252, "Cloud")) }, selected = mode == "cloud", icon = { Icon(Icons.Outlined.CloudQueue, null) }, onClick = { navigate("cloud") })
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_227, "Connections")) }, selected = false, icon = { Icon(Icons.Outlined.Link, null) }, onClick = { manager.showConnections(true); navigate("remote") })
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_253, "Settings")) }, selected = false, icon = { Icon(Icons.Outlined.Settings, null) }, onClick = { settings = true; local.refresh(); scope.launch { drawer.close() } })
    }
    val connection = managed.activeConnection
    val host = connection?.let { hostModels[it.id] }
    if (mode == "remote" && !managed.showConnections && host != null && connection != null && !allHosts) {
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
        RemoteWorkspace(host, onOpenHosts = { manager.showConnections(true) }, navigation = navigation,
            hostSelector = {
                var hostsMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { hostsMenu = true }) {
                        Text("Codex", style = MaterialTheme.typography.titleLarge)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(hostsMenu, { hostsMenu = false }) {
                        DropdownMenuItem(text = { Text(uiText(R.string.all_hosts, "All")) }, onClick = { allHosts = true; hostsMenu = false })
                        managed.savedConnections.forEach { saved ->
                            DropdownMenuItem(text = { Text(saved.name) }, onClick = { if (saved.id != connection.id) manager.connect(saved); hostsMenu = false },
                                trailingIcon = { if (saved.id == connection.id) Icon(Icons.Outlined.Check, null) })
                        }
                        DropdownMenuItem(text = { Text(uiText(R.string.ui_227, "Connections")) }, onClick = { hostsMenu = false; manager.showConnections(true) })
                    }
                }
            })
        return
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.widthIn(max = 330.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.background) {
            Text("Codex Remote", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            navigation()
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            if (mode == "chat" || mode == "work") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { archived = !archived }) {
                        Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(R.string.ui_267, "Archived"))
                        if (archived) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    items(localState.conversations.filter { it.archived == archived }, key = { it.id }) { conversation ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !localState.sending) { local.open(conversation); scope.launch { drawer.close() } }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { local.archive(conversation) }, enabled = !localState.sending) {
                                Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, uiText(R.string.ui_072, "Archive"))
                            }
                        }
                    }
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.align(Alignment.CenterStart)) {
                    IconButton(onClick = { scope.launch { drawer.open() }; local.refresh() }, modifier = Modifier.testTag("home-menu")) { Icon(Icons.Outlined.Menu, uiText(R.string.ui_255, "Conversations")) }
                }
                if (mode == "chat" || mode == "work") {
                    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
                        listOf("chat" to uiText(R.string.ui_250, "Chat"), "work" to uiText(R.string.ui_251, "Work")).forEach { (value, label) ->
                            Surface(shape = CircleShape, color = if (mode == value) MaterialTheme.colorScheme.background else androidx.compose.ui.graphics.Color.Transparent) {
                                TextButton(onClick = { mode = value }, enabled = !localState.sending, modifier = Modifier.width(78.dp).testTag("home-mode-$value")) { Text(label) }
                            }
                        }
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.align(Alignment.CenterEnd)) {
                        IconButton(onClick = { local.newConversation() }, enabled = !localState.sending) { Icon(Icons.Outlined.EditNote, uiText(R.string.ui_259, "New chat")) }
                    }
                } else Text(if (mode == "remote") "Codex" else uiText(R.string.ui_252, "Cloud"), style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                when (mode) {
                    "chat", "work" -> LocalPane(local, mode == "work") { settings = true; local.refresh() }
                    "cloud" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(uiText(R.string.ui_254, "No tasks"), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    else -> if (allHosts && connection != null) {
                        HostOverview(managed.savedConnections, hostStates) { saved, thread ->
                            allHosts = false
                            pendingThread = saved.id to thread
                            if (saved.id != connection.id) manager.connect(saved)
                        }
                    } else ConnectionsScreen(managed, { if (managed.activeConnection != null) manager.showConnections(false) else mode = "chat" },
                        { manager.editConnection() }, manager::editConnection,
                        { saved -> hostModels[saved.id]?.disconnect(); manager.deleteConnection(saved) }, manager::connect,
                        manager::saveConnection, manager::closeEditor, manager::clearNotice)
                }
            }
        }
    }
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
private fun LocalPane(viewModel: LocalViewModel, workspace: Boolean, openSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var addMenu by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFile) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) exportFile?.let { viewModel.export(it, uri) }
    }
    val provider = state.providers.firstOrNull { it.id == state.providerId }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!state.selected?.messages.isNullOrEmpty()) Text(state.selected?.title.orEmpty(), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        val draft = state.selected?.draft.orEmpty()
        if (draft.startsWith("/")) {
            Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(8.dp)) {
                    if ("/compact".startsWith(draft.trim(), true)) TextButton(onClick = { viewModel.draft(""); viewModel.compact() }, enabled = !state.sending && provider?.protocol == ApiProtocol.RESPONSES) {
                        Icon(Icons.Outlined.Compress, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_067, "Compact context"))
                    }
                    if ("/new".startsWith(draft.trim(), true)) TextButton(onClick = { viewModel.newConversation() }, enabled = !state.sending) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_259, "New chat"))
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(value = draft, onValueChange = viewModel::draft, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 164.dp).testTag("local-composer"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), maxLines = 6,
                    decorationBox = { field -> Box {
                        if (draft.isEmpty()) Text(uiText(R.string.ui_265, "Message"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.Add, uiText(R.string.ui_114, "Add")) }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            if (workspace && state.selected != null) {
                                DropdownMenuItem(text = { Text(uiText(R.string.ui_257, "Files")) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                    onClick = { filesOpen = true; addMenu = false })
                                DropdownMenuItem(text = { Text(uiText(R.string.generate_image, "Generate image")) }, enabled = !state.sending && draft.isNotBlank() && provider != null,
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { viewModel.generateImage(); addMenu = false })
                                DropdownMenuItem(text = { Text(uiText(R.string.ui_271, "Import file")) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                                    onClick = { importer.launch(arrayOf("*/*")); addMenu = false })
                            } else if (state.selected != null) DropdownMenuItem(text = { Text(uiText(R.string.ui_258, "Save to workspace")) },
                                enabled = !state.sending, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { viewModel.promote(); addMenu = false })
                            DropdownMenuItem(text = { Text(uiText(R.string.ui_067, "Compact context")) },
                                enabled = !state.sending && provider?.protocol == ApiProtocol.RESPONSES && !state.selected?.messages.isNullOrEmpty(),
                                leadingIcon = { Icon(Icons.Outlined.Compress, null) }, onClick = { viewModel.compact(); addMenu = false })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { if (provider == null) openSettings() else modelsOpen = true }, modifier = Modifier.widthIn(max = 230.dp)) {
                        Text(provider?.let { listOf(it.selectedModel, it.effort.takeIf(String::isNotBlank)?.displayEffort()).filterNotNull().filter(String::isNotBlank).joinToString(" · ") }
                            ?: uiText(R.string.ui_261, "Add API provider"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp))
                    }
                    FilledIconButton(onClick = if (state.sending) viewModel::stop else viewModel::send, modifier = Modifier.size(42.dp),
                        enabled = state.sending || draft.isNotBlank() && provider != null) {
                        Icon(if (state.sending) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                            if (state.sending) uiText(R.string.ui_266, "Stop") else uiText(R.string.ui_146, "Send"))
                    }
                }
            }
        }
    }
    if (modelsOpen && provider != null) ModelConfigurationSheet(
        models = provider.models.map { com.codex.remote.domain.RemoteModel(it.id, it.id, "", false,
            supportedReasoningEfforts = it.efforts.map { effort -> com.codex.remote.domain.ReasoningEffortOption(effort) }) },
        selectedModel = provider.selectedModel, effort = provider.effort.takeIf(String::isNotBlank),
        onModel = { viewModel.selectModel(it) }, onEffort = { viewModel.selectModel(provider.selectedModel, it) },
        onDismiss = { modelsOpen = false }, providerSelector = {
            var providersOpen by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
                ConfigurationRow(uiText(R.string.api_provider, "API provider"), provider.name) { providersOpen = true }
                DropdownMenu(providersOpen, { providersOpen = false }) {
                    state.providers.forEach { candidate ->
                        DropdownMenuItem(text = { Text(candidate.name) }, onClick = { viewModel.provider(candidate.id); providersOpen = false },
                            trailingIcon = { if (candidate.id == provider.id) Icon(Icons.Outlined.Check, null) })
                    }
                }
            }
        })
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
private fun SettingsPage(state: LocalUiState, local: LocalViewModel, onDismiss: () -> Unit, onEditProvider: (ApiProvider?) -> Unit) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var debugEnabled by remember { mutableStateOf(DebugApi.enabled()) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDismiss)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.align(Alignment.CenterStart)) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_151, "Close")) }
            }
            Text(uiText(R.string.ui_253, "Settings"), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(uiText(R.string.api_providers, "API providers"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.providers.forEach { provider ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).clickable { local.provider(provider.id) }) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text(provider.selectedModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (provider.id == state.providerId) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp))
                    IconButton(onClick = { local.models(provider) }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, uiText(R.string.ui_263, "Fetch models")) }
                    IconButton(onClick = { onEditProvider(provider) }) { Icon(Icons.Outlined.Edit, uiText(R.string.ui_264, "Edit provider")) }
                }
            }
            TextButton(onClick = { onEditProvider(null) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_261, "Add API provider")) }
            Spacer(Modifier.height(12.dp))
            Text(uiText(R.string.ui_273, "System language"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault()), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(18.dp))
            Spacer(Modifier.height(12.dp))
            Text(uiText(R.string.ui_274, "Cache"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null)
                Spacer(Modifier.width(14.dp))
                Text(android.text.format.Formatter.formatFileSize(context, state.cacheBytes), Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = !state.sending) { Text(uiText(R.string.ui_275, "Clear cache")) }
            }
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(12.dp))
                Text(uiText(R.string.ui_276, "Debug API"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BugReport, null)
                    Spacer(Modifier.width(14.dp))
                    Text(uiText(R.string.ui_276, "Debug API"), Modifier.weight(1f))
                    Switch(debugEnabled, { enabled -> runCatching { DebugApi.enable(enabled) }.onSuccess { debugEnabled = DebugApi.enabled() }.onFailure { error = it.message } },
                        modifier = Modifier.semantics { contentDescription = uiText(R.string.ui_276, "Debug API") })
                }
                if (debugEnabled) TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Debug API token", DebugApi.token))
                }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_277, "Copy debug token")) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(uiText(R.string.ui_278, "Clear cache?")) },
        text = { Text(uiText(R.string.ui_279, "Cached messages will be removed. Workspaces, hosts and API credentials are kept.")) },
        confirmButton = { TextButton(onClick = { local.clearCache(); confirmClear = false }) { Text(uiText(R.string.ui_036, "Clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(uiText(R.string.ui_037, "Cancel")) } })
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
