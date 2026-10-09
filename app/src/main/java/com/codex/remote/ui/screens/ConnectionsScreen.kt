package com.codex.remote.ui.screens

import com.codex.remote.R
import com.codex.remote.ui.uiText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionDraft
import com.codex.remote.domain.ConnectionDraftIssue
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.validationIssues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SavedConnection?) -> Unit,
    onDelete: (SavedConnection) -> Unit,
    onConnect: (SavedConnection) -> Unit,
    onSave: (ConnectionDraft, Boolean) -> Unit,
    onCloseEditor: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SavedConnection?>(null) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            onDismissNotice()
        }
    }

    if (state.showConnectionEditor) {
        ConnectionEditor(state.editingConnection, state.isBusy, onCloseEditor, onSave)
        return
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiText(R.string.ui_001, "Remote hosts")) },
                navigationIcon = {
                    if (state.activeConnection != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = uiText(R.string.ui_002, "返回工作区"))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = uiText(R.string.ui_003, "添加连接"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.savedConnections.isEmpty()) {
                EmptyConnections(onAdd)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(uiText(R.string.ui_004, "SSH HOSTS"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.savedConnections.forEach { connection ->
                        ConnectionRow(
                            connection = connection,
                            isActive = state.activeConnection?.id == connection.id,
                            isConnecting = state.activeConnection?.id == connection.id && state.connectionStatus == ConnectionStatus.CONNECTING,
                            onConnect = { onConnect(connection) },
                            onEdit = { onEdit(connection) },
                            onDelete = { pendingDelete = connection },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                uiText(R.string.ui_005, "凭据由 Android Keystore 加密。首次连接保存主机指纹，密钥变化时会阻止连接。"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(uiText(R.string.delete_host, "删除 %1\$s？", connection.name)) },
            text = { Text(uiText(R.string.ui_006, "保存的主机和加密凭据将从此设备移除。")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(connection)
                }) { Text(uiText(R.string.ui_007, "删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(uiText(R.string.ui_008, "取消")) } },
        )
    }
}

@Composable
private fun EmptyConnections(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(uiText(R.string.ui_009, "Connect a remote host"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            uiText(R.string.ui_010, "Codex runs on the remote host. Projects and conversations are imported automatically after connection."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(uiText(R.string.ui_011, "Add SSH host"))
        }
    }
}

@Composable
private fun ConnectionRow(
    connection: SavedConnection,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Router, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = uiText(R.string.ui_012, "当前主机"),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Text(
                    if (connection.authType == AuthType.PRIVATE_KEY) uiText(R.string.ui_029, "Private key") else uiText(R.string.ui_028, "Password"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    uiText(R.string.ui_013, "All Codex projects and conversations"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onConnect) { Text(if (isActive) uiText(R.string.ui_014, "Reconnect") else uiText(R.string.ui_015, "Connect")) }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = uiText(R.string.ui_016, "编辑")) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = uiText(R.string.ui_007, "删除")) }
        }
    }
}

@Composable
private fun ConnectionEditor(
    original: SavedConnection?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ConnectionDraft, Boolean) -> Unit,
) {
    var draft by remember(original?.id) { mutableStateOf(original.toDraft()) }
    var attemptedSave by remember(original?.id) { mutableStateOf(false) }
    val validationIssues = draft.validationIssues(original)
    val validationIssue = validationIssues.firstOrNull()
    val submit: (Boolean) -> Unit = { connectAfterSave ->
        attemptedSave = true
        if (validationIssues.isEmpty()) onSave(draft, connectAfterSave)
    }
    BackHandler(enabled = !busy, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = uiText(R.string.ui_017, "关闭"))
                    }
                    Text(
                        if (original == null) uiText(R.string.ui_011, "Add SSH host") else uiText(R.string.ui_018, "Edit SSH host"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 720.dp)
                        .align(Alignment.CenterHorizontally).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionLabel(uiText(R.string.ui_019, "CONNECTION"))
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text(uiText(R.string.ui_020, "Display name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = attemptedSave && ConnectionDraftIssue.CONNECTION_NAME in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.CONNECTION_NAME in validationIssues) {
                            { Text(uiText(R.string.ui_021, "Required")) }
                        } else null,
                    )
                    Text(
                        uiText(R.string.ui_022, "Projects and conversations are discovered from the remote Codex history."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionLabel(uiText(R.string.ui_023, "SSH HOST"))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.host,
                            onValueChange = { draft = draft.copy(host = it) },
                            label = { Text(uiText(R.string.ui_024, "Host")) },
                            placeholder = { Text("devbox.example.com") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = attemptedSave && ConnectionDraftIssue.HOST in validationIssues,
                            supportingText = if (attemptedSave && ConnectionDraftIssue.HOST in validationIssues) {
                                { Text(uiText(R.string.ui_021, "Required")) }
                            } else null,
                        )
                        OutlinedTextField(
                            value = draft.port,
                            onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit)) },
                            label = { Text(uiText(R.string.ui_025, "Port")) },
                            singleLine = true,
                            modifier = Modifier.width(104.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = attemptedSave && ConnectionDraftIssue.PORT in validationIssues,
                            supportingText = if (attemptedSave && ConnectionDraftIssue.PORT in validationIssues) {
                                { Text("1-65535") }
                            } else null,
                        )
                    }
                    OutlinedTextField(
                        value = draft.username,
                        onValueChange = { draft = draft.copy(username = it) },
                        label = { Text(uiText(R.string.ui_026, "Username")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = attemptedSave && ConnectionDraftIssue.USERNAME in validationIssues,
                        supportingText = if (attemptedSave && ConnectionDraftIssue.USERNAME in validationIssues) {
                            { Text(uiText(R.string.ui_021, "Required")) }
                        } else null,
                    )
                    SectionLabel(uiText(R.string.ui_027, "AUTHENTICATION"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.authType == AuthType.PASSWORD,
                            onClick = { draft = draft.copy(authType = AuthType.PASSWORD) },
                            label = { Text(uiText(R.string.ui_028, "Password")) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                        FilterChip(
                            selected = draft.authType == AuthType.PRIVATE_KEY,
                            onClick = { draft = draft.copy(authType = AuthType.PRIVATE_KEY) },
                            label = { Text(uiText(R.string.ui_029, "Private key")) },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    if (draft.authType == AuthType.PASSWORD) {
                        OutlinedTextField(
                            value = draft.password,
                            onValueChange = { draft = draft.copy(password = it) },
                            label = { Text(if (original == null) uiText(R.string.ui_028, "Password") else uiText(R.string.ui_030, "Password (leave blank to keep)")) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            isError = attemptedSave && ConnectionDraftIssue.PASSWORD in validationIssues,
                            supportingText = if (attemptedSave && ConnectionDraftIssue.PASSWORD in validationIssues) {
                                { Text(uiText(R.string.ui_021, "Required")) }
                            } else null,
                        )
                    } else {
                        OutlinedTextField(
                            value = draft.privateKey,
                            onValueChange = { draft = draft.copy(privateKey = it) },
                            label = { Text(if (original == null) uiText(R.string.ui_347, "OpenSSH / PEM private key") else uiText(R.string.ui_031, "Private key (leave blank to keep)")) },
                            minLines = 5,
                            maxLines = 9,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            isError = attemptedSave && ConnectionDraftIssue.PRIVATE_KEY in validationIssues,
                            supportingText = if (attemptedSave && ConnectionDraftIssue.PRIVATE_KEY in validationIssues) {
                                { Text(uiText(R.string.ui_021, "Required")) }
                            } else null,
                        )
                        OutlinedTextField(
                            value = draft.passphrase,
                            onValueChange = { draft = draft.copy(passphrase = it) },
                            label = { Text(uiText(R.string.ui_032, "Key passphrase (optional)")) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SectionLabel(uiText(R.string.ui_033, "REMOTE PLATFORM"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemotePlatform.entries.forEach { platform ->
                            FilterChip(
                                selected = draft.platform == platform,
                                onClick = { draft = draft.copy(platform = platform) },
                                label = { Text(platform.displayName) },
                            )
                        }
                    }
                    if (draft.hostKeyFingerprint.isNotBlank()) {
                        SectionLabel(uiText(R.string.ui_034, "HOST KEY"))
                        OutlinedTextField(
                            value = draft.hostKeyFingerprint,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(uiText(R.string.ui_035, "Pinned fingerprint")) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                TextButton(onClick = {
                                    draft = draft.copy(hostKeyFingerprint = "", clearHostKeyFingerprint = true)
                                }) { Text(uiText(R.string.ui_036, "Clear")) }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                HorizontalDivider()
                if (attemptedSave && validationIssue != null) {
                    Text(
                        validationIssue.message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { submit(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text(uiText(R.string.ui_345, "Save & connect")) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text(uiText(R.string.ui_037, "Cancel")) }
                        OutlinedButton(onClick = { submit(false) }, enabled = !busy, modifier = Modifier.weight(1f), shape = CircleShape) { Text(uiText(R.string.ui_038, "Save")) }
                    }
                }
            }
        }
    }
}

private val ConnectionDraftIssue.message: String
    get() = when (this) {
        ConnectionDraftIssue.CONNECTION_NAME -> uiText(R.string.ui_039, "Display name is required.")
        ConnectionDraftIssue.HOST -> uiText(R.string.ui_040, "SSH host is required.")
        ConnectionDraftIssue.PORT -> uiText(R.string.ui_041, "Port must be between 1 and 65535.")
        ConnectionDraftIssue.USERNAME -> uiText(R.string.ui_042, "Username is required.")
        ConnectionDraftIssue.PASSWORD -> uiText(R.string.ui_043, "Password is required.")
        ConnectionDraftIssue.PRIVATE_KEY -> uiText(R.string.ui_044, "Paste an OpenSSH or PEM private key.")
    }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun SavedConnection?.toDraft(): ConnectionDraft = if (this == null) ConnectionDraft() else ConnectionDraft(
    id = id,
    name = name,
    host = host,
    port = port.toString(),
    username = username,
    authType = authType,
    hostKeyFingerprint = hostKeyFingerprint,
    platform = platform,
)

private val RemotePlatform.displayName: String
    get() = when (this) {
        RemotePlatform.AUTO -> uiText(R.string.ui_045, "Auto")
        RemotePlatform.POSIX -> uiText(R.string.ui_348, "Linux / macOS")
        RemotePlatform.WINDOWS -> "Windows"
    }
