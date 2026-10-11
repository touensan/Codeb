package com.codex.remote.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.codex.remote.AppViewModel
import com.codex.remote.R
import com.codex.remote.data.api.*
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.SavedConnection
import com.codex.remote.data.local.RuntimeSetup
import com.codex.remote.data.local.TERMUX_PERMISSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
fun SkillMarketScreen(targets: Map<String, AppViewModel>, initialTarget: String?,
    localConnections: Map<String, SavedConnection> = emptyMap(), onLocalSetup: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SkillMarketStore(context) }
    var catalog by remember { mutableStateOf(store.cached()) }
    var online by remember { mutableStateOf(false) }
    var targetId by rememberSaveable { mutableStateOf(initialTarget ?: targets.keys.firstOrNull().orEmpty()) }
    val target = targets[targetId]
    val states = targets.mapValues { (_, model) -> model.state.collectAsState().value }
    var installed by remember(target) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var installationKnown by remember(target) { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MarketSkill?>(null) }
    var removal by remember { mutableStateOf<JsonObject?>(null) }
    var chooseTarget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val uri = LocalUriHandler.current
    val connected = states[targetId]?.connectionStatus == ConnectionStatus.CONNECTED
    val localConnection = localConnections[targetId]
    val preparing = states[targetId]?.connectionStatus == ConnectionStatus.CONNECTING
    var localIssue by remember(target) { mutableStateOf("") }
    fun connectLocal() {
        if (target == null || localConnection == null || connected || preparing) return
        localIssue = ""
        target.connect(localConnection)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connectLocal() else localIssue = uiText(R.string.local_termux_permission, "Allow Codeb to run commands in Termux first.")
    }
    fun prepareLocal() {
        if (localConnection == null || connected || preparing) return
        localIssue = when {
            !RuntimeSetup.installed(context) -> uiText(R.string.skills_local_setup, "Set up Local Codeb to install skills on this phone.")
            localConnection.localProviderId == null -> uiText(R.string.setup_no_provider, "Add and enable a Responses provider in settings first.")
            else -> ""
        }
        if (localIssue.isNotEmpty()) return
        if (context.checkSelfPermission(TERMUX_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(TERMUX_PERMISSION)
        else connectLocal()
    }
    LaunchedEffect(target, localConnection?.localProviderId) { prepareLocal() }
    val localError = localIssue.ifBlank { states[targetId]?.takeIf { it.connectionStatus == ConnectionStatus.ERROR }?.connectionMessage.orEmpty() }
    val title = uiText(R.string.skills_market, "Skills market")
    val installedMessage = uiText(R.string.skills_installed_hint, "Installed. Choose it from the chat + menu → Skills.")
    val removedMessage = uiText(R.string.skills_removed, "Skill removed")
    suspend fun reloadInstalled() {
        installationKnown = false
        if (target != null && target.state.value.connectionStatus == ConnectionStatus.CONNECTED) {
            installed = target.marketSkills("list")["items"]!!.jsonArray.map { it.jsonObject }
            installationKnown = true
        }
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
        busy = true; message = ""; online = false
        try { catalog = withContext(Dispatchers.IO) { store.refresh() }; online = true }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = e.message.orEmpty() }
        try { reloadInstalled() }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = e.message.orEmpty() }
        finally { if (isActive) busy = false }
    } }
    LaunchedEffect(target, connected) { refresh() }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.local_back, "Back")) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { prepareLocal(); refresh() }, enabled = !busy) { Icon(Icons.Outlined.Refresh, uiText(R.string.skills_refresh, "Refresh")) }
        }
        LazyColumn(Modifier.weight(1f).testTag("skills-market"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(uiText(R.string.skills_intro, "Phone-ready skills for Local CodeB and SSH workspaces."), style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { chooseTarget = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("skills-target")) {
                        Text(states[targetId]?.activeConnection?.name ?: targetId, Modifier.weight(1f))
                        Text(when {
                            localConnection == null -> if (connected) uiText(R.string.skills_connected, "Connected") else uiText(R.string.skills_offline, "Offline")
                            connected -> uiText(R.string.setup_ready, "Ready")
                            localError.isNotBlank() -> uiText(R.string.skills_needs_setup, "Needs attention")
                            else -> uiText(R.string.skills_preparing, "Preparing…")
                        })
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(chooseTarget, { chooseTarget = false }) {
                        targets.forEach { (id, _) -> DropdownMenuItem(text = { Text(states[id]?.activeConnection?.name ?: id) }, onClick = { targetId = id; chooseTarget = false }) }
                    }
                }
                if (!connected && localConnection == null) Text(uiText(R.string.skills_connect_first, "Connect the SSH server to install there."), style = MaterialTheme.typography.bodySmall)
                if (!connected && localConnection != null) {
                    if (localError.isNotBlank()) {
                        Text(localError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = ::prepareLocal, enabled = !preparing) { Text(uiText(R.string.skills_refresh, "Refresh")) }
                            TextButton(onClick = onLocalSetup) { Text(uiText(R.string.local_workspace, "Local Codeb settings")) }
                        }
                    } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (!online) Text(uiText(R.string.skills_cached, "Offline catalog · refresh before installing"), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (message.isNotBlank()) SelectionContainer { Text(message, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(query, { query = it }, label = { Text(uiText(R.string.skills_search, "Search skills")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            val filtered = catalog.items.filter { query.isBlank() || "${it.title} ${it.description} ${it.category} ${it.id}".contains(query, true) }
            if (filtered.isEmpty()) item { Text(uiText(R.string.skills_empty, "No matching skills")) }
            items(filtered, key = { it.id }) { skill ->
                val own = installed.firstOrNull { it["id"]?.jsonPrimitive?.content == skill.id }
                val modified = own?.get("modified")?.jsonPrimitive?.boolean == true
                val current = own?.get("sha256")?.jsonPrimitive?.content == skill.sha256
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(skill.title, style = MaterialTheme.typography.titleMedium)
                        Text("${skill.category} · ${skill.version} · ${skill.license}", style = MaterialTheme.typography.labelSmall)
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        if (modified) Text(uiText(R.string.skills_modified, "Local edits or an unmanaged skill; your files are protected."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = skill }) { Text(uiText(R.string.skills_details, "Details")) }
                            Spacer(Modifier.weight(1f))
                            Button(enabled = connected && online && installationKnown && !busy && !modified && !current,
                                modifier = Modifier.testTag("install-${skill.id}"), onClick = { scope.launch {
                                    busy = true; message = ""
                                    try {
                                        // Re-fetch immediately: a withdrawn or changed listing must never install from cache.
                                        val fresh = withContext(Dispatchers.IO) { store.refresh() }
                                        catalog = fresh
                                        check(fresh.items.any { it.id == skill.id && it.sha256 == skill.sha256 && it.version == skill.version }) { "Skill listing changed; review the latest version" }
                                        target!!.marketSkills("install", skill, expectedSha256 = own?.get("sha256")?.jsonPrimitive?.content)
                                        reloadInstalled(); message = installedMessage
                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = e.message.orEmpty() }
                                    finally { busy = false }
                                } }) { Text(when { current -> uiText(R.string.skills_installed, "Installed"); own != null -> uiText(R.string.skills_update, "Update"); else -> uiText(R.string.skills_install, "Install") }) }
                        }
                    }
                }
            }
            if (installed.isNotEmpty()) item { Text(uiText(R.string.skills_on_target, "Installed on this target"), style = MaterialTheme.typography.titleMedium) }
            items(installed, key = { "own-${it["id"]}" }) { own ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(own["id"]!!.jsonPrimitive.content)
                        Text(own["version"]!!.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { removal = own }, enabled = !busy && installationKnown && own["modified"]?.jsonPrimitive?.boolean == false) { Text(uiText(R.string.skills_remove, "Uninstall")) }
                }
            }
        }
    }
    selected?.let { skill -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(skill.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(skill.requirements)
            TextButton(onClick = { uri.openUri(skill.sourceUrl) }) { Text(uiText(R.string.skills_source, "Source and license")) }
            SelectionContainer { Text(skill.content, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = { selected = null }) { Text(uiText(R.string.ui_037, "Close")) } }) }
    removal?.let { own -> AlertDialog(onDismissRequest = { removal = null }, title = { Text(uiText(R.string.skills_remove, "Uninstall")) },
        text = { Text(own["id"]!!.jsonPrimitive.content) }, confirmButton = { TextButton(onClick = {
            removal = null
            scope.launch {
                busy = true
                try { target!!.marketSkills("remove", id = own["id"]!!.jsonPrimitive.content, expectedSha256 = own["sha256"]!!.jsonPrimitive.content); reloadInstalled(); message = removedMessage }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = e.message.orEmpty() }
                finally { busy = false }
            }
        }) { Text(uiText(R.string.skills_remove, "Uninstall")) } }, dismissButton = { TextButton(onClick = { removal = null }) { Text(uiText(R.string.ui_037, "Cancel")) } }) }
}
