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
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material.icons.filled.Stop
import com.codex.remote.data.api.resolved
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.ui.text.font.FontWeight
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
import com.codex.remote.ui.screens.modelSettingsSummary
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
fun CodexRemoteApp(manager: AppViewModel, local: LocalViewModel = viewModel()) {
    val managed by manager.state.collectAsState()
    val localState by local.state.collectAsState()
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf("chat") }
    var beforeMarket by rememberSaveable { mutableStateOf("chat") }
    val returnToChat by com.codex.remote.data.local.PhoneControl.returnToChat.collectAsState()
    LaunchedEffect(returnToChat){if(returnToChat>0)mode="work"}
    var dailyFiles by remember {mutableStateOf(false)}
    if(dailyFiles) {val files=remember(localState.selected?.id){local.conversationFiles()};FileManagerScreen(files,files.root){dailyFiles=false}}
    LaunchedEffect(mode) {if(mode!="work")com.codex.remote.data.local.PhoneControl.stop()}
    var settings by rememberSaveable { mutableStateOf(false) }
    var providerEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProvider?>(null) }
    if (!settings && !providerEditor) CloudUpdateNotice(localState.cloud.update)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, local) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) local.refreshCloud()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var allHosts by rememberSaveable { mutableStateOf(false) }
    var pendingThread by remember { mutableStateOf<Pair<String, RemoteThread>?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var archived by rememberSaveable { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<LocalConversation?>(null) }
    val hostModels = linkedMapOf<String, AppViewModel>()
    val hostStates = linkedMapOf<String, AppUiState>()
    managed.savedConnections.forEach { saved ->
        key(saved.id) {
        val host: AppViewModel = viewModel(viewModelStoreOwner = HostSessions, key = "host-${saved.id}", factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application, autoRestore = false) as T
        })
        hostModels[saved.id] = host
        val snapshot by host.state.collectAsState()
        hostStates[saved.id] = snapshot
        LaunchedEffect(saved.id) {
            if (host.state.value.activeConnection == null) host.connect(saved)
        }
        }
    }
    BackHandler(enabled = !managed.showConnectionEditor && !settings && !providerEditor) {
        if (managed.showConnections && managed.activeConnection != null) manager.showConnections(false)
        else (context as? Activity)?.moveTaskToBack(true)
    }
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
    archiveTarget?.let { conversation ->
        com.codex.remote.ui.screens.ArchiveConfirmationDialog(conversation.title,
            onDismiss = { archiveTarget = null }, onArchive = { local.archive(conversation); archiveTarget = null })
    }
    val navigation: @Composable (() -> Unit) -> Unit = { closeDrawer ->
        val select: (String) -> Unit = { target -> navigate(target); closeDrawer() }
        NavigationDrawerItem(label = { Text(uiText(R.string.mode_daily, "Daily")) }, selected = mode == "chat", icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = { select("chat") }, modifier = Modifier.testTag("mode-daily"))
        NavigationDrawerItem(label = { Text(uiText(R.string.mode_work, "Work")) }, selected = mode == "work", icon = { Icon(Icons.Outlined.PhoneAndroid, null) }, onClick = { select("work") }, modifier = Modifier.testTag("mode-work"))
        NavigationDrawerItem(label = { Text(uiText(R.string.mode_remote, "Remote Codex")) }, selected = mode == "remote", icon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { select("remote") })
        NavigationDrawerItem(label = { Text(uiText(R.string.skills_market, "Skills market")) }, selected = mode == "skills", icon = { Icon(Icons.Outlined.Extension, null) }, onClick = { beforeMarket = mode; select("skills") }, modifier = Modifier.testTag("mode-skills"))
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_227, "Connections")) }, selected = false, icon = { Icon(Icons.Outlined.Link, null) }, onClick = { manager.showConnections(true); select("remote") })
        NavigationDrawerItem(label = { Text(uiText(R.string.ui_253, "Settings")) }, selected = false, icon = { Icon(Icons.Outlined.Settings, null) }, onClick = { settings = true; local.refresh(); closeDrawer(); scope.launch { drawer.close() } })
    }
    val connection = managed.activeConnection
    val host = connection?.let { hostModels[it.id] }
    LaunchedEffect(host, connection?.id, managed.connectionMessage) {
        if (host != null && connection != null && managed.connectionMessage.startsWith("reconnect-") &&
            host.state.value.activeConnection != null && host.state.value.connectionStatus != ConnectionStatus.CONNECTING) host.connect(connection)
    }
    hostStates.entries.firstOrNull { it.value.pendingHostKeyFingerprint != null }?.let { (id, state) ->
        val saved = managed.savedConnections.first { it.id == id }
        com.codex.remote.ui.screens.HostKeyConfirmationDialog("${saved.host}:${saved.port}", state.pendingHostKeyFingerprint!!,
            { hostModels[id]?.trustPendingHostKey() }, { hostModels[id]?.rejectPendingHostKey() })
    }
    val sidebar: @Composable (Boolean, () -> Unit) -> Unit = { isOpen, closeDrawer ->
        val sidebarState = rememberLazyListState()
        LaunchedEffect(isOpen, archived) { if (isOpen) sidebarState.scrollToItem(0) }
        Column(Modifier.fillMaxHeight().statusBarsPadding()) {
            Text(uiText(R.string.app_name, "白云Codeb"), Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            navigation(closeDrawer)
            TextButton(onClick = { local.newConversation(); archived = false; navigate("chat"); closeDrawer() }, modifier = Modifier.padding(horizontal = 12.dp).testTag("sidebar-new-conversation")) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiText(R.string.new_conversation, "New conversation"))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            TextButton(onClick = { archived = !archived }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiText(R.string.ui_267, "Archived"))
                if (archived) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
            }
            LazyColumn(Modifier.weight(1f).testTag("sidebar-conversations"), state = sidebarState, contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(localState.conversations.filter { it.archived == archived && it.messages.any { message -> message.role == "user" } }, key = { it.id }) { conversation ->
                    Row(Modifier.fillMaxWidth().testTag("sidebar-conversation-${conversation.id}").clickable { local.open(conversation); navigate("chat"); closeDrawer() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (conversation.id in localState.runningConversationIds) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { if (conversation.archived) local.archive(conversation) else archiveTarget = conversation }, enabled = conversation.id !in localState.runningConversationIds, modifier = Modifier.testTag("archive-conversation-${conversation.id}")) {
                            Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, uiText(R.string.ui_072, "Archive"))
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    val hostSelector: @Composable () -> Unit = {
        var hostsMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { hostsMenu = true }, modifier = Modifier.testTag("host-selector")) {
                Text("Codex", style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(hostsMenu, { hostsMenu = false }) {
                DropdownMenuItem(text = { Text(uiText(R.string.all_hosts, "All")) }, onClick = { allHosts = true; hostsMenu = false })
                managed.savedConnections.forEach { saved ->
                    DropdownMenuItem(text = { Text(saved.name) }, onClick = { allHosts = false; if (saved.id != connection?.id) manager.connect(saved); hostsMenu = false },
                        modifier = Modifier.testTag("host-menu-${saved.id}"),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!allHosts && saved.id == connection?.id) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                com.codex.remote.ui.screens.HostConnectionDot(hostStates[saved.id]?.connectionStatus ?: ConnectionStatus.DISCONNECTED)
                            }
                        })
                }
                DropdownMenuItem(text = { Text(uiText(R.string.ui_227, "Connections")) }, onClick = { hostsMenu = false; manager.showConnections(true) })
            }
        }
    }
    AnimatedContent(mode, transitionSpec={
        (fadeIn(tween(180))+slideInHorizontally(tween(200)){it/14}) togetherWith fadeOut(tween(100))
    },label="codeb-mode") { visibleMode ->
    if (visibleMode == "skills") {
        val standard: AppViewModel = viewModel(viewModelStoreOwner = HostSessions, key = "local-standard", factory = localFactory(context.applicationContext as Application))
        val root: AppViewModel = viewModel(viewModelStoreOwner = HostSessions, key = "local-root", factory = localFactory(context.applicationContext as Application))
        val phone = uiText(R.string.mode_work, "Local CodeB")
        val targets = linkedMapOf(phone to standard, "$phone · Root" to root).apply { putAll(hostModels) }
        val prefs = context.getSharedPreferences("phone-work", 0)
        val available = localState.providers.filter { it.enabled && it.protocol == com.codex.remote.data.store.ApiProtocol.RESPONSES && it.models.isNotEmpty() }
        val provider = available.firstOrNull { it.id == prefs.getString("provider", localState.providerId) } ?: available.firstOrNull()
        val localConnections = mapOf(phone to "standard", "$phone · Root" to "root").mapValues { (name, access) ->
            com.codex.remote.domain.SavedConnection(id = "phone-$access", name = name, host = "localhost", username = access,
                authType = com.codex.remote.domain.AuthType.PASSWORD, localAccess = access, localProviderId = provider?.id)
        }
        val preferred = if (beforeMarket == "remote") connection?.id else if (prefs.getString("access", "standard") == "root") "$phone · Root" else phone
        SkillMarketScreen(targets, preferred, localConnections, onLocalSetup = { navigate("work") }) { navigate(beforeMarket.takeUnless { it == "skills" } ?: "chat") }
        return@AnimatedContent
    }
    if (visibleMode == "work") {
        LocalWorkScreen(localState.providers, localState.providerId, onBack = { navigate("chat") },
            onSettings = { settings = true; local.refresh() }, navigation = { navigation { } }, sidebar = sidebar)
        return@AnimatedContent
    }
    if (visibleMode == "remote" && !managed.showConnections && host != null && connection != null && !allHosts) {
        LaunchedEffect(pendingThread, hostStates[connection.id]?.connectionStatus) {
            val pending = pendingThread
            if (pending?.first == connection.id && hostStates[connection.id]?.connectionStatus == ConnectionStatus.CONNECTED) {
                host.selectThread(pending.second)
                pendingThread = null
            }
        }
        RemoteWorkspace(host, onOpenHosts = { manager.showConnections(true) }, navigation = { navigation { } }, sidebarContent = sidebar,
            hostSelector = hostSelector)
        return@AnimatedContent
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.widthIn(max = 330.dp).fillMaxHeight(), drawerContainerColor = MaterialTheme.colorScheme.background) {
            sidebar(drawer.isOpen) { scope.launch { drawer.close() } }
        }
    }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                val hasConversation = (visibleMode == "chat" || visibleMode == "work") && localState.selected?.messages?.any { it.role == "user" } == true
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.align(Alignment.CenterStart).padding(end = 56.dp).animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() }; local.refresh() }, modifier = Modifier.testTag("home-menu")) { Icon(Icons.Outlined.Menu, uiText(R.string.ui_255, "Conversations")) }
                        AnimatedVisibility(hasConversation) {
                            Text(localState.selected?.title.orEmpty(), Modifier.padding(end = 16.dp).testTag("local-conversation-header"),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (visibleMode == "chat" || visibleMode == "work") {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Row {
                            IconButton(onClick = { local.newConversation() }) { Icon(Icons.Outlined.EditNote, uiText(R.string.ui_259, "New chat")) }
                            var menu by remember {mutableStateOf(false)}
                            Box {
                                IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,uiText(R.string.ui_065,"Actions"))}
                                DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text(uiText(R.string.file_manager,"Files"))},onClick={menu=false;dailyFiles=true})}
                            }
                        }
                    }
                } else if (allHosts) hostSelector() else Text("Codex", style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                when (visibleMode) {
                    "chat", "work" -> LocalPane(local, localState.selected?.workspace == true) { editingProvider = null; providerEditor = true }
                    else -> if (allHosts && connection != null) {
                        HostOverview(managed.savedConnections, hostStates) { saved, thread ->
                            allHosts = false
                            pendingThread = saved.id to thread
                            if (saved.id != connection.id) manager.connect(saved)
                        }
                    } else ConnectionsScreen(managed, { if (managed.activeConnection != null) manager.showConnections(false) else mode = "chat" },
                        { manager.editConnection() }, manager::editConnection,
                        { saved -> hostModels[saved.id]?.disconnect(); manager.deleteConnection(saved) }, manager::connect,
                        manager::saveConnection, manager::closeEditor, manager::clearNotice,
                        hostStatuses = hostStates.mapValues { it.value.connectionStatus })
                }
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
internal fun LocalPane(viewModel: LocalViewModel, workspace: Boolean, openSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var addMenu by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var filesOpen by remember { mutableStateOf(false) }
    var queueMode by remember(state.selected?.id) { mutableStateOf(true) }
    if(filesOpen) {
        val files=remember(state.selected?.id) {viewModel.conversationFiles()}
        FileManagerScreen(files,files.root) {filesOpen=false}
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::attachFile) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::attachFile) }
    val provider = state.providers.firstOrNull { it.id == state.providerId && it.enabled }?.let { raw -> state.catalog?.let { raw.resolved(it, state.selected) } ?: raw }
    val messages = state.selected?.messages.orEmpty()
    val displayedReplies = remember(state.selected?.id) { mutableStateMapOf<String, String>() }
    val displayedSnapshot = displayedReplies.toMap()
    val messageRows = messages.map { it to displayedSnapshot[it.id] }
    var followLatest by remember(state.selected?.id) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().imePadding()) {
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = viewModel::dismissError) { Icon(Icons.Outlined.Close, contentDescription = uiText(R.string.ui_260, "Dismiss")) }
            }
        }
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(if (workspace) Icons.Outlined.FolderOpen else Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val listState = remember(state.selected?.id) { androidx.compose.foundation.lazy.LazyListState() }
            val dragged by listState.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(listState, dragged) {
                if (dragged) followLatest = false
                else snapshotFlow { !listState.isScrollInProgress && !listState.canScrollForward }.distinctUntilChanged().collect { if (it) followLatest = true }
            }
            LaunchedEffect(listState) { listState.scrollToItem(messages.size) }
            LaunchedEffect(listState, followLatest, dragged) {
                if (!followLatest || dragged) return@LaunchedEffect
                snapshotFlow { Triple(listState.canScrollForward, listState.isScrollInProgress, listState.layoutInfo.totalItemsCount) }
                    .distinctUntilChanged().collect { (canScroll, scrolling, count) ->
                        if (canScroll && !scrolling && count > 0) {
                            // Let the new layout paint before following it; repeated immediate scrolls starve updates.
                            withFrameNanos { }
                            if (followLatest && !dragged) listState.scrollToItem(count - 1)
                        }
                    }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("local-conversation-list").pointerInput(state.selected?.id) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); followLatest = false }
            }, state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(messageRows, key = { it.first.id }) { (message, displayedBody) ->
                    if (message.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Column(horizontalAlignment = Alignment.End) {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 340.dp)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    if (message.text.isNotBlank()) Text(message.text)
                                    message.attachments.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                            if (message.text.isNotBlank()) IconButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("prompt", message.text))
                            }, modifier = Modifier.size(40.dp).testTag("local-prompt-copy-${message.id}")) {
                                Icon(Icons.Outlined.ContentCopy, uiText(R.string.copy_prompt, "Copy prompt"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            }
                        }
                    } else Column {
                        val streaming = state.sending && message.id == messages.lastOrNull()?.id
                        CompositionLocalProvider(com.codex.remote.ui.screens.LocalArtifactReader provides { path -> viewModel.readArtifact(path) }) {
                            com.codex.remote.ui.screens.RichBody(message.text, Modifier.testTag("local-reply-${message.id}"), streaming = streaming, onDisplayed = {
                                displayedReplies[message.id] = it
                            })
                        }
                        if (streaming) com.codex.remote.ui.screens.LoadingDots(Modifier.padding(top = 8.dp))
                        if (message.text.isNotBlank() && !streaming && displayedBody == message.text) {
                            Row(Modifier.offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("reply", message.text))
                            }, modifier = Modifier.size(36.dp).testTag("local-copy-${message.id}")) {
                                Icon(Icons.Outlined.ContentCopy, uiText(R.string.copy_reply, "Copy reply"), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            message.completedAt?.let { time ->
                                Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(time)),
                                    Modifier.testTag("local-time-${message.id}"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            }
                        }
                    }
                }
                item(key = "local-bottom") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("local-conversation-bottom")) }
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
                    if ("/new".startsWith(draft.trim(), true)) TextButton(onClick = { viewModel.newConversation() }) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_259, "New chat"))
                    }
                }
            }
        }
        state.selected?.queuedMessages.orEmpty().forEach { message ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).testTag("local-queued-${message.id}")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(uiText(if (state.selected?.queuePaused == true) R.string.queue_paused else R.string.queued_message, "Queued"), style = MaterialTheme.typography.labelSmall)
                        Text(message.text.ifBlank { message.attachments.joinToString { it.name } }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.steerQueuedMessage(message.id) }, modifier = Modifier.testTag("local-steer-queued-${message.id}")) { Text(uiText(R.string.send_steer, "Steer now")) }
                    IconButton(onClick = { viewModel.deleteQueuedMessage(message.id) }) { Icon(Icons.Outlined.Close, uiText(R.string.ui_161, "Delete")) }
                }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (state.sending && (draft.isNotBlank() || !state.selected?.attachments.isNullOrEmpty())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !queueMode, onClick = { queueMode = false }, label = { Text(uiText(R.string.send_steer, "Steer now")) }, modifier = Modifier.testTag("local-send-steer"))
                        FilterChip(selected = queueMode, onClick = { queueMode = true }, label = { Text(uiText(R.string.send_queue, "Queue message")) }, modifier = Modifier.testTag("local-send-queue"))
                    }
                }
                state.selected?.attachments.orEmpty().forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.kind == "image") Icons.Outlined.Image else Icons.Outlined.AttachFile, null, Modifier.size(18.dp))
                        Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.removeAttachment(file.id) }) { Icon(Icons.Outlined.Close, uiText(R.string.ui_260, "Dismiss")) }
                    }
                }
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
                            DropdownMenuItem(text = { Text(uiText(R.string.ui_257, "Files")) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                                onClick = { filePicker.launch(arrayOf("*/*")); addMenu = false })
                            DropdownMenuItem(text = { Text(uiText(R.string.attach_images, "Images")) }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                onClick = { imagePicker.launch("image/*"); addMenu = false })
                            DropdownMenuItem(text = { Text(uiText(R.string.file_manager, "Files")) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                                onClick = { filesOpen = true; addMenu = false })
                            if (workspace && state.selected != null) {
                                DropdownMenuItem(text = { Text(uiText(R.string.generate_image, "Generate image")) }, enabled = !state.sending && draft.isNotBlank() && provider != null,
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { viewModel.generateImage(); addMenu = false })
                            } else if (state.selected != null) DropdownMenuItem(text = { Text(uiText(R.string.ui_258, "Save to workspace")) },
                                enabled = !state.sending, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { viewModel.promote(); addMenu = false })
                            DropdownMenuItem(text = { Text(uiText(R.string.ui_067, "Compact context")) },
                                enabled = !state.sending && provider?.protocol == ApiProtocol.RESPONSES && !state.selected?.messages.isNullOrEmpty(),
                                leadingIcon = { Icon(Icons.Outlined.Compress, null) }, onClick = { viewModel.compact(); addMenu = false })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Box {
                        TextButton(onClick = { if (provider == null) openSettings() else modelsOpen = true }, modifier = Modifier.widthIn(max = 230.dp).testTag("daily-model")) {
                            Text(provider?.let { modelSettingsSummary(it.selectedModel, it.effort.takeIf(String::isNotBlank), null) }
                                ?: uiText(R.string.ui_261, "Add API provider"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp))
                        }
                        if (provider != null) ModelConfigurationMenu(
                            expanded = modelsOpen,
                            models = provider.models.map { com.codex.remote.domain.RemoteModel(it.id, it.id, "", false,
                                supportedReasoningEfforts = it.efforts.map { effort -> com.codex.remote.domain.ReasoningEffortOption(effort) }) },
                            selectedModel = provider.selectedModel, effort = provider.effort.takeIf(String::isNotBlank),
                            onModel = { viewModel.selectModel(it) }, onEffort = { viewModel.selectModel(provider.selectedModel, it) },
                            onDismiss = { modelsOpen = false }, providers = state.providers.filter { it.enabled }.map { it.id to it.name },
                            providerId = provider.id, onProvider = viewModel::provider, onAddProvider = openSettings)
                    }
                    if (state.sending && (draft.isNotBlank() || !state.selected?.attachments.isNullOrEmpty())) IconButton(onClick = viewModel::stop, modifier = Modifier.testTag("local-stop")) {
                        Icon(Icons.Filled.Stop, uiText(R.string.ui_266, "Stop"))
                    }
                    val stopAction = state.sending && draft.isBlank() && state.selected?.attachments.isNullOrEmpty()
                    FilledIconButton(onClick = { if (stopAction) viewModel.stop() else { followLatest = true; viewModel.send(steer = !queueMode); queueMode = true } }, modifier = Modifier.size(42.dp).testTag("local-send"),
                        enabled = state.sending || (draft.isNotBlank() || !state.selected?.attachments.isNullOrEmpty()) && provider != null) {
                        Icon(if (stopAction) Icons.Filled.Stop else Icons.Outlined.ArrowUpward,
                            if (stopAction) uiText(R.string.ui_266, "Stop") else uiText(R.string.ui_146, "Send"))
                    }
                }
            }
        }
    }

}

@Composable
private fun SettingsPage(state: LocalUiState, local: LocalViewModel, onDismiss: () -> Unit, onEditProvider: (ApiProvider?) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(local) { local.refreshCloud(force = true) }
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
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(provider.id == state.providerId, { local.provider(provider.id) }, enabled = provider.enabled && !state.sending)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).clickable { onEditProvider(provider) }) {
                        Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${provider.selectedModel} · ${uiText(R.string.provider_model_count, "%1\$d models", provider.models.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (provider.id == state.providerId) Text(uiText(R.string.provider_in_use, "In use"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { onEditProvider(provider) }) { Text(uiText(R.string.model_edit, "Edit")) }
                    Switch(provider.enabled, { local.enableProvider(provider.id, it) }, enabled = !state.sending,
                        modifier = Modifier.semantics { contentDescription = uiText(R.string.provider_enabled, "Enable provider") + ": " + provider.name })
                }
                HorizontalDivider()
                }
            }
            TextButton(onClick = { onEditProvider(null) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(10.dp)); Text(uiText(R.string.ui_261, "Add API provider")) }
            Spacer(Modifier.height(12.dp))
            AppearanceSetting()
            Spacer(Modifier.height(12.dp))
            LanguageSetting()
            Spacer(Modifier.height(12.dp))
            Text(uiText(R.string.ui_274, "Cache"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null)
                Spacer(Modifier.width(14.dp))
                Text(android.text.format.Formatter.formatFileSize(context, state.cacheBytes), Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = state.runningConversationIds.isEmpty()) { Text(uiText(R.string.ui_275, "Clear cache")) }
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
            CloudSettings(state, local)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(uiText(R.string.ui_278, "Clear cache?")) },
        text = { Text(uiText(R.string.ui_279, "Cached messages will be removed. Workspaces, hosts and API credentials are kept.")) },
        confirmButton = { TextButton(onClick = { local.clearCache(); confirmClear = false }) { Text(uiText(R.string.ui_036, "Clear")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(uiText(R.string.ui_037, "Cancel")) } })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProviderEditor(original: ApiProvider?, state: LocalUiState, local: LocalViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var url by remember { mutableStateOf(original?.baseUrl ?: "https://api.baiyun.si/v1") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(original?.selectedModel.orEmpty()) }
    var modelIds by remember { mutableStateOf((original?.models.orEmpty().map { it.id } + listOfNotNull(original?.selectedModel?.takeIf(String::isNotBlank))).distinct().joinToString("\n")) }
    val metadata = remember { mutableStateMapOf<String, ApiModel>().apply { original?.models?.forEach { put(it.id, it) } } }
    var effort by remember { mutableStateOf(original?.effort.orEmpty()) }
    var protocol by remember { mutableStateOf(original?.protocol ?: ApiProtocol.CHAT_COMPLETIONS) }
    var savedId by remember { mutableStateOf(original?.id) }
    val saved = state.providers.firstOrNull { it.id == savedId } ?: original
    val ids = modelIds.lines().map(String::trim).filter(String::isNotEmpty).distinct()
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    val testing = state.testingModels[savedId]
    val busy = fetching || saving || testing != null
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var addingModel by remember { mutableStateOf(false) }
    var modelInput by remember { mutableStateOf("") }
    var automaticEfforts by remember { mutableStateOf(true) }
    var editedEfforts by remember { mutableStateOf<List<String>>(emptyList()) }
    val filteredIds = ids.filter { it.contains(search, ignoreCase = true) }
    val lastPage = (filteredIds.size - 1).coerceAtLeast(0) / 10
    val visiblePage = page.coerceAtMost(lastPage)
    fun save(testIds: List<String>?, dismiss: Boolean = false) {
        saving = true
        local.saveProvider(saved, name, url, key, protocol, ids.map { id ->
            val configured = metadata[id] ?: ApiModel(id)
            configured.copy(test = saved?.models?.firstOrNull { it.id == id }?.test)
        }, model.takeIf { it in ids } ?: ids.firstOrNull().orEmpty(), effort) { provider ->
            savedId = provider.id
            key = ""
            saving = false
            if (testIds != null) local.testModels(provider, testIds)
            if (dismiss) onDismiss()
        }.invokeOnCompletion { saving = false }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = uiText(R.string.ui_151, "Close")) }
                    Text(if (original == null) uiText(R.string.ui_261, "Add API provider") else uiText(R.string.ui_264, "Edit provider"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (saved != null) IconButton(onClick = { confirmDelete = true }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, contentDescription = uiText(R.string.ui_280, "Delete provider")) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (original == null && state.cloud.recommendationsEnabled && state.cloud.recommendations.isNotEmpty()) {
                        Text(uiText(R.string.cloud_recommendations, "Recommended"), style = MaterialTheme.typography.titleMedium)
                        state.cloud.recommendations.forEach { item ->
                            Column(Modifier.fillMaxWidth().testTag("provider-recommendation-${item.id}")) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { openCloudLink(context, item.url) }) { Text(uiText(R.string.provider_website, "Visit website")) }
                                    if (android.net.Uri.parse(item.url).host == "api.baiyun.si") TextButton(onClick = { name = "白云 API"; url = "https://api.baiyun.si/v1"; protocol = ApiProtocol.RESPONSES }, enabled = !busy) { Text(uiText(R.string.provider_use, "Use this provider")) }
                                }
                            }
                        }
                        Text(uiText(R.string.provider_compatibility, "Some providers may not support the latest Codex version or all features. Test the selected model before use."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                    }
                    OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text(uiText(R.string.ui_281, "Provider name")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(url, { url = it }, enabled = !busy, label = { Text(uiText(R.string.ui_282, "Base URL")) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(key, { key = it }, enabled = !busy, label = { Text(if (saved == null) uiText(R.string.ui_173, "API key") else uiText(R.string.ui_283, "API key (leave blank to keep)")) }, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiProtocol.entries.forEach { value -> FilterChip(protocol == value, { protocol = value }, enabled = !busy, label = { Text(if (value == ApiProtocol.RESPONSES) "Responses" else "Chat Completions") }, shape = CircleShape) }
                    }
                    OutlinedButton(onClick = {
                        fetching = true; editorError = null
                        scope.launch {
                            try {
                                val fetched = local.fetchModels(saved, url, key, protocol)
                                fetched.forEach { value -> metadata[value.id] = if (value.effortsReported) value else metadata[value.id] ?: value }
                                modelIds = (fetched.map { it.id } + ids).distinct().joinToString("\n")
                            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                            catch (error: Exception) { editorError = error.message }
                            finally { fetching = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("fetch-provider-models")) { Text(uiText(if (fetching) R.string.fetching_provider_models else R.string.fetch_provider_models, if (fetching) "Fetching…" else "Fetch all upstream models")) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiText(R.string.provider_model_count, "%1\$d models", ids.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { addingModel = true; modelInput = ""; automaticEfforts = true; editedEfforts = emptyList() }, enabled = !busy,
                            modifier = Modifier.testTag("add-provider-model")) { Text(uiText(R.string.model_add, "Add model")) }
                        TextButton(onClick = { if (testing != null) savedId?.let(local::stopModelTests) else save(ids) }, enabled = testing != null || !busy && ids.isNotEmpty()) {
                            Text(uiText(if (testing != null) R.string.stop_model_tests else R.string.test_all_models, if (testing != null) "Stop testing" else "Test all"))
                        }
                    }
                    OutlinedTextField(search, { search = it; page = 0 }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider-model-search"),
                        label = { Text(uiText(R.string.model_search, "Search models")) })
                    Text(uiText(R.string.model_auto_hint, "Reasoning levels match automatically. Edit a model to override; tests save the current configuration first."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    filteredIds.drop(visiblePage * 10).take(10).forEach { id ->
                        val value = metadata[id] ?: ApiModel(id)
                        val automatic = value.efforts.isEmpty() && !value.effortsReported
                        val levels = if (automatic) state.catalog?.rule(id, url)?.efforts.orEmpty() else value.efforts
                        val result = saved?.models?.firstOrNull { it.id == id }?.test.takeIf { saved?.baseUrl == url.trim().trimEnd('/') && saved.protocol == protocol && key.isBlank() }
                            Column(Modifier.fillMaxWidth().testTag("provider-model-row-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(modifier = Modifier.semantics { contentDescription = uiText(R.string.default_provider_model, "Default model %1\$s", id) }, selected = id == (model.takeIf { it in ids } ?: ids.firstOrNull()), onClick = { model = id; effort = "" }, enabled = !busy)
                                    Text(id, Modifier.weight(1f))
                                    TextButton(onClick = {
                                        editingModel = id; modelInput = id; automaticEfforts = automatic; editedEfforts = levels
                                    }, enabled = !busy, modifier = Modifier.testTag("edit-provider-model-$id")) { Text(uiText(R.string.model_edit, "Edit")) }
                                    TextButton(onClick = { save(listOf(id)) }, enabled = !busy, modifier = Modifier.testTag("test-provider-model-$id")) { Text(uiText(R.string.model_test, "Test")) }
                                }
                                Text(uiText(if (automatic) R.string.model_auto_levels else R.string.model_custom_levels, if (automatic) "Automatic: %1\$s" else "Configured: %1\$s",
                                    levels.joinToString(" / ") { it.displayEffort() }.ifBlank { uiText(R.string.ui_239, "None") }), style = MaterialTheme.typography.bodySmall)
                                Text(if (testing == id) uiText(R.string.testing_provider_model, "Testing…") else result?.detail ?: uiText(R.string.model_not_tested, "Not tested"), modifier = Modifier.testTag("provider-model-result-$id"), color = if (result?.outcome == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                    }
                    if (lastPage > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page = visiblePage - 1 }, enabled = visiblePage > 0, modifier = Modifier.testTag("model-page-previous")) { Text(uiText(R.string.models_previous, "Previous")) }
                        Text("${visiblePage + 1} / ${lastPage + 1}")
                        TextButton(onClick = { page = visiblePage + 1 }, enabled = visiblePage < lastPage, modifier = Modifier.testTag("model-page-next")) { Text(uiText(R.string.models_next, "Next")) }
                    }
                    Text(uiText(R.string.model_probe_notice, "Each model gets a short request using this API type. Listed models may support different endpoints."), style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { save(null, dismiss = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text(uiText(R.string.provider_save_only, "Save")) }
                }
            }
        }
    }
    if (addingModel || editingModel != null) AlertDialog(onDismissRequest = { addingModel = false; editingModel = null },
        title = { Text(uiText(if (addingModel) R.string.model_add else R.string.model_edit, if (addingModel) "Add model" else "Edit")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(modelInput, { modelInput = it }, singleLine = true, modifier = Modifier.testTag("provider-model-id"), label = { Text(uiText(R.string.ui_284, "Model ID")) })
            FilterChip(automaticEfforts, { automaticEfforts = true }, label = { Text(uiText(R.string.model_match_auto, "Automatic matching")) }, modifier = Modifier.testTag("model-match-auto"))
            val matched = state.catalog?.rule(modelInput.trim(), url)?.efforts.orEmpty()
            Text(uiText(R.string.model_auto_levels, "Automatic: %1\$s", matched.joinToString(" / ") { it.displayEffort() }.ifBlank { uiText(R.string.ui_239, "None") }), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (automaticEfforts) editedEfforts = matched; automaticEfforts = false }, modifier = Modifier.testTag("model-match-manual")) { Text(uiText(R.string.model_match_manual, "Adjust manually")) }
            if (!automaticEfforts) {
                OutlinedTextField(editedEfforts.joinToString("\n"), { editedEfforts = if (it.isEmpty()) emptyList() else it.lines() }, label = { Text(uiText(R.string.effort_ids, "Reasoning IDs (one per line)")) }, supportingText = { Text(uiText(R.string.effort_ids_help, "These IDs are sent as written. You may rename, add or delete them.")) }, modifier = Modifier.fillMaxWidth().testTag("model-effort-ids"))
            }
            if (!addingModel) TextButton(onClick = { modelIds = ids.filterNot { it == editingModel }.joinToString("\n"); editingModel = null }, modifier = Modifier.testTag("delete-edited-model")) { Text(uiText(R.string.ui_161, "Delete")) }
        } },
        confirmButton = { TextButton(onClick = {
            val id = modelInput.trim()
            val old = editingModel
            modelIds = (if (old == null) ids + id else ids.map { if (it == old) id else it }).distinct().joinToString("\n")
            metadata[id] = ApiModel(id, if (automaticEfforts) emptyList() else editedEfforts, effortsReported = !automaticEfforts)
            if (model == old) model = id
            addingModel = false; editingModel = null
        }, enabled = modelInput.isNotBlank() && modelInput.trim().length <= 256 && modelInput.none(Char::isISOControl) && (automaticEfforts || (editedEfforts.size <= 12 && editedEfforts.distinct() == editedEfforts && editedEfforts.all { com.codex.remote.data.api.validEffortId(it) })) && (modelInput.trim() == editingModel || modelInput.trim() !in ids), modifier = Modifier.testTag("confirm-provider-model")) { Text(uiText(R.string.provider_save_only, "Save")) } },
        dismissButton = { TextButton(onClick = { addingModel = false; editingModel = null }) { Text(uiText(R.string.ui_037, "Cancel")) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(uiText(R.string.ui_287, "Delete provider?")) }, text = { Text(uiText(R.string.ui_288, "Saved credentials will be removed. Conversations and workspace files are kept.")) }, confirmButton = { TextButton(onClick = { saved?.let(local::deleteProvider); onDismiss() }) { Text(uiText(R.string.ui_161, "Delete")) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiText(R.string.ui_037, "Cancel")) } })
}
