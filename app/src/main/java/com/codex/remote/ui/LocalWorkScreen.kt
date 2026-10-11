package com.codex.remote.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.remote.*
import com.codex.remote.R
import com.codex.remote.data.local.*
import com.codex.remote.data.store.ApiProvider
import com.codex.remote.data.store.ApiProtocol
import com.codex.remote.domain.*
import kotlinx.coroutines.launch

@Composable
fun LocalWorkScreen(providers: List<ApiProvider>, defaultProviderId: String, onBack: () -> Unit, onSettings: () -> Unit,
    navigation: @Composable () -> Unit, sidebar: @Composable (Boolean, () -> Unit) -> Unit) {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("phone-work",0)}
    var access by rememberSaveable {mutableStateOf(prefs.getString("access","standard")!!)}
    var editing by rememberSaveable {mutableStateOf(!prefs.getBoolean("configured",false))}
    var providerId by rememberSaveable {mutableStateOf(prefs.getString("provider",defaultProviderId)!!)}
    val available=providers.filter {it.enabled&&it.protocol==ApiProtocol.RESPONSES&&it.models.isNotEmpty()}
    val provider=available.firstOrNull {it.id==providerId} ?: available.firstOrNull()
    var selectedModel by remember(provider?.id) {mutableStateOf(prefs.getString("model:${provider?.id}",provider?.selectedModel).orEmpty())}
    val standard:AppViewModel=viewModel(viewModelStoreOwner=HostSessions,key="local-standard",factory=localFactory(context.applicationContext as Application))
    val root:AppViewModel=viewModel(viewModelStoreOwner=HostSessions,key="local-root",factory=localFactory(context.applicationContext as Application))
    val standardState by standard.state.collectAsState()
    val rootState by root.state.collectAsState()
    val model=if(access=="root")root else standard
    val state by model.state.collectAsState()
    var task by remember {mutableStateOf<String?>(null)}
    var report by rememberSaveable {mutableStateOf("")}
    var termuxInstalled by remember {mutableStateOf(RuntimeSetup.installed(context))}
    var setupStatus by remember {mutableStateOf("")}
    val lifecycle=LocalLifecycleOwner.current
    LaunchedEffect(editing,lifecycle) {
        if(editing)lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                termuxInstalled=RuntimeSetup.installed(context)
                if(termuxInstalled && ContextCompat.checkSelfPermission(context,TERMUX_PERMISSION)==PackageManager.PERMISSION_GRANTED) {
                    setupStatus=try {RuntimeSetup.run(context,false,false,statusOnly=true)} catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException)throw e
                        ""
                    }
                }
                delay(5000)
            } while(setupStatus.contains("Stage: preparing")||setupStatus.contains("Stage: packages")||setupStatus.contains("Stage: runtime"))
        }
    }
    var choose by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun launchTermux(){runCatching {context.startActivity(Intent().setClassName("com.termux","com.termux.app.TermuxActivity"))}.onFailure {report=it.message.orEmpty()}}
    fun connect() {
        val chosen=provider ?: return
        prefs.edit().putString("access",access).putString("provider",chosen.id).putString("model:${chosen.id}",selectedModel).putBoolean("configured",true).apply()
        if(state.connectionStatus!=ConnectionStatus.CONNECTED || state.activeConnection?.localProviderId!=chosen.id) {
            model.connect(SavedConnection(id="phone-$access",name=uiText(R.string.mode_work,"Local Codeb")+" · "+(if(access=="root")"Root" else uiText(R.string.local_standard,"Standard")),
                host="localhost",username=access,authType=AuthType.PASSWORD,localAccess=access,localProviderId=chosen.id))
        } else if(state.selectedModel!=selectedModel) model.setModel(selectedModel)
        editing=false
    }
    fun setup(install:Boolean) {scope.launch {
        task=if(install)"install" else "check";report=""
        try {report=RuntimeSetup.run(context,install,access=="root");setupStatus=report}
        catch(e:Exception){if(e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException)throw e;report=e.message ?: uiText(R.string.setup_failed,"Check Termux external-command access.")}
        finally{task=null}
    }}
    var permissionAction by remember {mutableStateOf("connect")}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)when(permissionAction){"check"->setup(false);"install"->setup(true);else->connect()}
        else report=uiText(R.string.local_termux_permission,"Allow Codeb to run commands in Termux first.")
    }
    fun run(action:String) {
        permissionAction=action
        if(ContextCompat.checkSelfPermission(context,TERMUX_PERMISSION)!=PackageManager.PERMISSION_GRANTED)permission.launch(TERMUX_PERMISSION)
        else when(action){"check"->setup(false);"install"->setup(true);else->connect()}
    }
    LaunchedEffect(Unit){if(!editing&&state.activeConnection==null)run("connect")}
    LaunchedEffect(state.connectionStatus){if(state.connectionStatus==ConnectionStatus.ERROR)editing=true}
    if(!editing&&state.activeConnection!=null) {
        RemoteWorkspace(model,onOpenHosts={editing=true},navigation=navigation,sidebarContent=sidebar,
            hostSelector={Row(verticalAlignment=Alignment.CenterVertically){
                Text(uiText(R.string.mode_work,"Local Codeb"),style=MaterialTheme.typography.titleMedium)
                IconButton(onClick={selectedModel=state.selectedModel ?: selectedModel;editing=true},modifier=Modifier.testTag("local-work-settings")){Icon(Icons.Outlined.Settings,uiText(R.string.local_workspace,"Settings"))}
            }})
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={if(state.connectionStatus==ConnectionStatus.CONNECTED)editing=false else onBack()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.local_back,"Back"))}
            Text(uiText(R.string.local_workspace,"Local Codeb settings"),style=MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(uiText(R.string.local_access_heading,"Permissions"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=12.dp))
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.local_use_root,"Enable Root"))},supportingContent={Text(uiText(R.string.local_root_support,"KernelSU / Magisk and compatible su"))},
                trailingContent={Switch(access=="root",{enabled -> access=if(enabled)"root" else "standard";prefs.edit().putString("access",access).apply();PhoneControl.stop()},enabled=!standardState.isTurnRunning&&!rootState.isTurnRunning,modifier=Modifier.testTag("local-root-switch"))})
            HorizontalDivider()
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.local_provider_label,"Provider"))},supportingContent={Text(provider?.name ?: uiText(R.string.local_select_provider,"Select a provider"))},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.clickable {choose="provider"})
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.local_model_label,"Model"))},supportingContent={Text(selectedModel)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.clickable {choose="model"}.testTag("local-model"))
            HorizontalDivider()
            Text(uiText(R.string.local_runtime_heading,"Runtime"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=12.dp))
            val runtimeReady=setupStatus.contains("codex-cli ${RuntimeSetup.VERSION}") || report.contains("codex-cli ${RuntimeSetup.VERSION}")
            val hasPermission=ContextCompat.checkSelfPermission(context,TERMUX_PERMISSION)==PackageManager.PERMISSION_GRANTED
            val stage=setupStatus.substringAfter("Stage: ","").trim()
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.setup_termux_step,"1 · Install Termux"))},
                supportingContent={Text(uiText(if(termuxInstalled)R.string.setup_installed else R.string.setup_termux_detail,"Official ARM64 package, provided by Codeb"))},
                trailingContent={if(termuxInstalled)Icon(Icons.Outlined.Check,null) else TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(RuntimeSetup.TERMUX_URL)))}){Text(uiText(R.string.setup_download,"Download"))}})
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.setup_prepare_step,"2 · Prepare the phone"))},
                supportingContent={Text(uiText(if(runtimeReady)R.string.setup_ready else R.string.runtime_bootstrap_detail,"Copy the setup command, run it in Termux, then return."))},
                trailingContent={if(runtimeReady)Icon(Icons.Outlined.Check,null)})
            if(!runtimeReady) TextButton(onClick={
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Codeb Termux setup",RuntimeSetup.bootstrap));launchTermux()
            },enabled=termuxInstalled){Text(uiText(R.string.runtime_bootstrap,"Copy command and open Termux"))}
            ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(uiText(R.string.setup_connect_step,"3 · Allow Codeb to connect"))},
                supportingContent={Text(uiText(if(runtimeReady&&hasPermission)R.string.setup_ready else R.string.setup_permission_detail,"After the script finishes, tap Check and allow the Termux permission."))},
                trailingContent={if(runtimeReady&&hasPermission)Icon(Icons.Outlined.Check,null)})
            if(stage.isNotBlank())Text(uiText(when {
                stage.startsWith("failed") -> R.string.setup_stage_failed
                stage=="packages" -> R.string.setup_stage_packages
                stage=="runtime" -> R.string.setup_stage_runtime
                stage=="ready" -> R.string.setup_ready
                else -> R.string.setup_stage_preparing
            },"Preparing"),style=MaterialTheme.typography.bodySmall)
            Text(uiText(R.string.local_runtime_detail,"Check Termux, Node.js, Codex and permission. One-tap setup installs the verified official runtime."),style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={run("check")},enabled=task==null&&termuxInstalled,modifier=Modifier.testTag("runtime-check")){Text(uiText(R.string.runtime_check,"Check environment"))}
                Button(onClick={run("install")},enabled=task==null&&termuxInstalled&&!state.isTurnRunning,modifier=Modifier.testTag("runtime-install")){Text(uiText(R.string.runtime_install,"One-tap setup"))}
            }
            if(task!=null)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(report.isNotBlank())Text(report,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("runtime-report"))
            TextButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:com.termux")))}){Text(uiText(R.string.local_background,"Termux background settings"))}
            HorizontalDivider()
            PhoneControlSetting(provider,selectedModel,access)
            Text(uiText(R.string.phone_skills,"Use project .agents/skills for Skills."),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onSettings){Text(uiText(R.string.local_manage_providers,"Manage providers"))}
            if(state.connectionStatus==ConnectionStatus.ERROR)Text(state.connectionMessage,color=MaterialTheme.colorScheme.error)
        }
        Button(onClick={run("connect")},enabled=provider!=null&&selectedModel.isNotBlank()&&!state.isTurnRunning&&task==null,modifier=Modifier.fillMaxWidth().padding(20.dp).testTag("local-connect")){Text(uiText(R.string.local_enter,"Save and enter"))}
    }
    if(choose!=null)AlertDialog(onDismissRequest={choose=null},title={Text(uiText(if(choose=="model") R.string.local_model_label else R.string.local_provider_label,"Select"))},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            if(choose=="provider") {
                if(available.isEmpty())Text(uiText(R.string.setup_no_provider,"Add an enabled Responses provider in settings first."))
                available.forEach {item ->
                    ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(item.name)},supportingContent={Text("${android.net.Uri.parse(item.baseUrl).host.orEmpty()} · ${item.models.size} " + uiText(R.string.setup_models,"models"))},
                        leadingContent={RadioButton(selected=provider?.id==item.id,onClick=null)},
                        modifier=Modifier.selectable(selected=provider?.id==item.id,role=Role.RadioButton,onClick={providerId=item.id;prefs.edit().putString("provider",item.id).apply();PhoneControl.stop();choose=null}))
                }
            } else provider?.models?.forEach {item ->
                ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(item.id)},leadingContent={RadioButton(selected=selectedModel==item.id,onClick=null)},
                    modifier=Modifier.testTag("local-model-${item.id}").selectable(selected=selectedModel==item.id,role=Role.RadioButton,onClick={selectedModel=item.id;prefs.edit().putString("model:${provider.id}",item.id).apply();if(state.models.any {it.id==item.id})model.setModel(item.id);PhoneControl.stop();choose=null}))
            }
        }
    },confirmButton={TextButton(onClick={choose=null}){Text(uiText(R.string.ui_037,"Cancel"))}})
}

private fun localFactory(application:Application)=object:ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=AppViewModel(application,autoRestore=false) as T
}
