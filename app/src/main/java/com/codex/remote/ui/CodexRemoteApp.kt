package com.codex.remote.ui

import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.codex.remote.AppViewModel
import com.codex.remote.ui.screens.ConnectionsScreen
import com.codex.remote.ui.screens.WorkspaceScreen

@Composable
fun RemoteWorkspace(viewModel: AppViewModel, onOpenHosts: () -> Unit,
    navigation: @Composable () -> Unit = {}, hostSelector: @Composable () -> Unit = {}, sidebarContent: (@Composable (Boolean, () -> Unit) -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    var filesOpen by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf<String?>(null) }
    if(filesOpen && state.connectionStatus==com.codex.remote.domain.ConnectionStatus.CONNECTED) {
        val files=remember(viewModel) {viewModel.phoneFiles()}
        val absolute=filePath?.let {if(it.startsWith('/'))it else state.selectedProjectPath.orEmpty().trimEnd('/')+"/"+it}
        FileManagerScreen(files,state.selectedProjectPath ?: "/",absolute) {filesOpen=false}
    }
    androidx.compose.runtime.LaunchedEffect(state.selectedThreadId, state.connectionStatus) {
        while (state.selectedThreadId != null && state.connectionStatus == com.codex.remote.domain.ConnectionStatus.CONNECTED) {
            viewModel.refreshQueue(); kotlinx.coroutines.delay(3000)
        }
    }
    val showConnections = state.showConnections || state.activeConnection == null

    if (state.isRestoringLastConnection) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (showConnections) {
        ConnectionsScreen(
            state = state,
            onBack = { viewModel.showConnections(false) },
            onAdd = { viewModel.editConnection() },
            onEdit = viewModel::editConnection,
            onDelete = viewModel::deleteConnection,
            onConnect = viewModel::connect,
            onSave = viewModel::saveConnection,
            onCloseEditor = viewModel::closeEditor,
            onDismissNotice = viewModel::clearNotice,
        )
    } else {
        androidx.compose.runtime.CompositionLocalProvider(com.codex.remote.ui.screens.LocalArtifactReader provides { path -> viewModel.readArtifact(path) },
            com.codex.remote.ui.screens.LocalFileManager provides { path -> filePath=path;filesOpen=true },
            com.codex.remote.ui.screens.LocalWorkspaceImporter provides viewModel::importWorkspaceAttachment) {
        WorkspaceScreen(
            state = state,
            startOnTasksPage = true,
            navigation = navigation,
            sidebarContent = sidebarContent,
            hostSelector = {
                androidx.compose.foundation.layout.Row(verticalAlignment=Alignment.CenterVertically) {
                    hostSelector()
                    var menu by remember {mutableStateOf(false)}
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.material3.IconButton(onClick={menu=true}){androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert,uiText(com.codex.remote.R.string.ui_065,"Actions"))}
                        androidx.compose.material3.DropdownMenu(menu,{menu=false}) {
                            androidx.compose.material3.DropdownMenuItem(text={androidx.compose.material3.Text(uiText(com.codex.remote.R.string.file_manager,"Files"))},onClick={menu=false;filePath=null;filesOpen=true},enabled=state.connectionStatus==com.codex.remote.domain.ConnectionStatus.CONNECTED)
                            androidx.compose.material3.DropdownMenuItem(text={androidx.compose.material3.Text(uiText(com.codex.remote.R.string.ui_253,"Settings"))},onClick={menu=false;onOpenHosts()})
                        }
                    }
                }
            },
            onOpenConnections = onOpenHosts,
            onNewThread = viewModel::newThread,
            onSelectProject = viewModel::selectProject,
            onSelectThread = viewModel::selectThread,
            onLoadOlderHistory = viewModel::loadOlderHistory,
            onRenameThread = viewModel::renameThread,
            onArchiveThread = viewModel::archiveThread,
            onLoadArchivedThreads = viewModel::loadArchivedThreads,
            onUnarchiveThread = viewModel::unarchiveThread,
            onDeleteArchivedThread = viewModel::deleteArchivedThread,
            onSetThreadPinned = viewModel::setThreadPinned,
            onSend = viewModel::sendMessage,
            onQueue = viewModel::queueMessage,
            onDeleteQueued = viewModel::deleteQueuedMessage,
            onSteerQueued = viewModel::steerQueuedMessage,
            onStop = viewModel::interruptTurn,
            onCompactThread = viewModel::compactThread,
            onForkThread = viewModel::forkThread,
            onStartReview = viewModel::startReview,
            onRunInit = viewModel::runInit,
            onLoadMcpStatus = viewModel::loadMcpStatus,
            onReloadMcpServers = viewModel::reloadMcpServers,
            onStartMcpLogin = viewModel::startMcpLogin,
            onMcpAuthorizationHandled = viewModel::clearMcpAuthorizationUrl,
            onSubmitFeedback = viewModel::submitFeedback,
            onSetGoal = viewModel::setThreadGoal,
            onSetGoalStatus = viewModel::setThreadGoalStatus,
            onClearGoal = viewModel::clearThreadGoal,
            onShowStatus = viewModel::showConnectionStatus,
            onSetModel = viewModel::setModel,
            onSetReasoningEffort = viewModel::setReasoningEffort,
            onSetServiceTier = viewModel::setServiceTier,
            onSetCollaborationMode = viewModel::setCollaborationMode,
            onSetPermissionProfile = viewModel::setPermissionProfile,
            onSetPermissionMode = viewModel::setPermissionMode,
            onLoadRemoteDirectory = viewModel::loadRemoteDirectory,
            onCreateRemoteDirectory = viewModel::createRemoteDirectory,
            onClearRemoteDirectory = viewModel::clearRemoteDirectory,
            onStartLogin = viewModel::startRemoteLogin,
            onCancelLogin = viewModel::cancelRemoteLogin,
            onApproval = viewModel::respondToApproval,
            onTrustHostKey = viewModel::trustPendingHostKey,
            onRejectHostKey = viewModel::rejectPendingHostKey,
            onDismissNotice = viewModel::clearNotice,
        )
        }
    }
}
