package com.codex.remote.ui

import android.text.InputType
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.addTextChangedListener
import com.codex.remote.R
import com.codex.remote.data.local.*
import com.codex.remote.data.api.readLimited
import com.codex.remote.ui.screens.FileContentPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

@Composable
fun FileManagerScreen(files: PhoneFiles, initialPath: String, initialFile: String? = null, onClose: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var path by remember(files) { mutableStateOf(initialFile?.let {File(it).parent} ?: initialPath) }
    var pathInput by remember(path) { mutableStateOf(path) }
    var entries by remember { mutableStateOf<List<PhoneFile>>(emptyList()) }
    var selected by remember { mutableStateOf(initialFile?.let {PhoneFile(File(it).name,it,false)}) }
    var content by remember { mutableStateOf<ByteArray?>(null) }
    var revision by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var operation by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var closeAfterDiscard by remember {mutableStateOf(false)}
    val dirty=original!=null && text != original
    fun back() { if(dirty) discard=true else if(selected!=null) { selected=null; editing=false } else onClose() }
    BackHandler { back() }
    fun job(block: suspend () -> Unit) { scope.launch { busy=true; error=null; try { block() } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message } finally { busy=false } } }
    LaunchedEffect(path,refresh) { busy=true;error=null;try { entries=files.list(path);selected=selected?.let {file->entries.firstOrNull {it.path==file.path} ?: file} } catch(e: Exception){error=e.message;entries=emptyList()} finally {busy=false} }
    LaunchedEffect(selected?.path) {
        content=null;original=null;archive=null;editing=false;error=null
        val item=selected ?: return@LaunchedEffect
        if(item.directory) return@LaunchedEffect
        busy=true
        try {
            val bytes=files.read(item.path);content=bytes;revision=fileRevision(bytes)
            if(item.name.endsWith(".zip",true)||item.name.endsWith(".apk",true)||item.name.endsWith(".jar",true)) {
                archive=withContext(Dispatchers.IO) {
                    val temp=File.createTempFile("archive-preview-",".zip",context.cacheDir)
                    try { temp.writeBytes(bytes);ZipFile(temp).use { zip -> zip.entries().asSequence().take(2000).map { "${it.name}  (${it.size} B)" }.toList() } } finally {temp.delete()}
                }
            } else if(bytes.size<=1024*1024 && !bytes.contains(0.toByte())) {
                original=runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                text=original.orEmpty()
                editing=original!=null&&!item.link
            }
        } catch(e: Exception){ if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message } finally {busy=false}
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes=content
        if(uri!=null&&bytes!=null)job { withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {it.write(bytes)} ?: error("Cannot export file")} }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null)job {
            val (filename,bytes)=withContext(Dispatchers.IO) {
                val filename=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} ?: "imported-file"
                filename to (context.contentResolver.openInputStream(uri)?.use {it.readLimited(18*1024*1024)} ?: error("Cannot import file"))
            }
            files.save(fileChild(path,filename),bytes,null);refresh++
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest={back()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
    Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),tonalElevation=4.dp) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={back()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.local_back,"Back"))}
                Text(selected?.name ?: uiText(R.string.file_manager,"Files"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1)
                if(selected==null) {
                    IconButton(onClick={importer.launch(arrayOf("*/*"))}){Icon(Icons.Outlined.FileUpload,uiText(R.string.ui_271,"Import file"))}
                    IconButton(onClick={refresh++}){Icon(Icons.Outlined.Refresh,uiText(R.string.file_refresh,"Refresh"))}
                    IconButton(onClick={name="";operation="new"}){Icon(Icons.Outlined.NoteAdd,uiText(R.string.file_new,"New file"))}
                    IconButton(onClick={name="";operation="mkdir"}){Icon(Icons.Outlined.CreateNewFolder,uiText(R.string.file_folder,"New folder"))}
                } else if(editing) TextButton(onClick={job { val bytes=text.toByteArray();files.save(selected!!.path,bytes,revision);revision=fileRevision(bytes);original=text;content=bytes;refresh++ }},enabled=!busy&&dirty,modifier=Modifier.testTag("file-save")){Text(uiText(R.string.provider_save_only,"Save"))}
                else {
                    if(original!=null&&!selected!!.link) IconButton(onClick={editing=true}){Icon(Icons.Outlined.Edit,uiText(R.string.model_edit,"Edit"))}
                    IconButton(onClick={exporter.launch(selected!!.name)},enabled=content!=null){Icon(Icons.Outlined.Download,uiText(R.string.ui_270,"Export"))}
                }
                IconButton(onClick={if(dirty){closeAfterDiscard=true;discard=true} else onClose()}){Icon(Icons.Outlined.Close,uiText(R.string.file_close,"Close files"))}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error) }
            val current=selected
            if(current==null) {
                Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={path=File(path).parent?.takeIf { it.length>=files.root.length } ?: files.root},enabled=path!=files.root){Icon(Icons.Outlined.ArrowUpward,uiText(R.string.file_parent,"Parent folder"))}
                    OutlinedTextField(pathInput,{pathInput=it},Modifier.weight(1f),singleLine=true,label={Text(uiText(R.string.file_path,"Path"))})
                    TextButton(onClick={path=pathInput;refresh++}){Text(uiText(R.string.file_go,"Go"))}
                }
                LazyColumn(Modifier.weight(1f).testTag("file-list"),contentPadding=PaddingValues(16.dp)) {
                    items(entries,key={it.path}) { item ->
                        Row(Modifier.fillMaxWidth().clickable { if(item.directory)path=item.path else selected=item }.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(if(item.directory)Icons.Outlined.Folder else Icons.Outlined.Description,null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){Text(item.name);Text(if(item.directory)uiText(R.string.file_folder_label,"Folder") else android.text.format.Formatter.formatFileSize(context,item.size),style=MaterialTheme.typography.bodySmall)}
                            IconButton(onClick={selected=item;operation="info"}){Icon(Icons.Outlined.Info,uiText(R.string.file_info,"Details"))}
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Text(shortPhonePath(current.path),Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,maxLines=2)
                if(original!=null) Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(editing,{editing=true},label={Text(uiText(R.string.model_edit,"Edit"))})
                    FilterChip(!editing,{editing=false},label={Text(uiText(R.string.file_preview,"Preview"))})
                    TextButton(onClick={back()}){Text(uiText(R.string.file_directory,"Directory"))}
                }
                if(editing) AndroidView(factory={ ctx -> EditText(ctx).apply {
                    contentDescription="Codeb file editor";setText(text);setTextColor(android.graphics.Color.BLACK);setBackgroundColor(android.graphics.Color.WHITE)
                    typeface=android.graphics.Typeface.MONOSPACE;textSize=14f;gravity=android.view.Gravity.TOP
                    inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    typeface=android.graphics.Typeface.MONOSPACE;setPadding((16*resources.displayMetrics.density).toInt(),(12*resources.displayMetrics.density).toInt(),(16*resources.displayMetrics.density).toInt(),0)
                    setHorizontallyScrolling(false);isVerticalScrollBarEnabled=true;addTextChangedListener(afterTextChanged={text=it?.toString().orEmpty()})
                } },modifier=Modifier.weight(1f).fillMaxWidth().testTag("file-editor"))
                else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    archive?.let { names -> Text(uiText(R.string.file_archive,"Archive preview (up to 2000 entries)"));names.forEach {Text(it,style=MaterialTheme.typography.bodySmall)} }
                        ?: content?.let { FileContentPreview(current.name,if(dirty)text.toByteArray() else it,if(dirty)text else original) }
                    Text("${android.text.format.Formatter.formatFileSize(context,current.size)} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(current.modified))} ${current.mode}",style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={name=current.name;operation="rename"},enabled=!current.link){Text(uiText(R.string.file_rename,"Rename"))}
                        TextButton(onClick={operation="delete"}){Text(uiText(R.string.ui_161,"Delete"))}
                    }
                }
            }
        }
    }
    }
    if(operation!=null) AlertDialog(onDismissRequest={operation=null},title={Text(uiText(when(operation){"delete"->R.string.file_delete_confirm;"mkdir"->R.string.file_folder;"rename"->R.string.file_rename;"info"->R.string.file_info;else->R.string.file_new},"File operation"))},
        text={ if(operation=="delete"||operation=="info")Text(selected?.path.orEmpty()) else OutlinedTextField(name,{name=it},singleLine=true,label={Text(uiText(R.string.file_name,"Name"))}) },
        confirmButton={TextButton(onClick={val op=operation;operation=null;job {
            when(op){"new"->{files.save(fileChild(path,name),byteArrayOf(),null);refresh++};"mkdir"->{files.mkdir(fileChild(path,name));refresh++};"rename"->{files.rename(selected!!.path,name);selected=null;refresh++};"delete"->{files.delete(selected!!.path);selected=null;refresh++}}
        }}){Text(uiText(R.string.file_confirm,"Confirm"))}},dismissButton={TextButton(onClick={operation=null}){Text(uiText(R.string.ui_037,"Cancel"))}})
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text(uiText(R.string.file_discard,"Discard unsaved changes?"))},confirmButton={TextButton(onClick={discard=false;editing=false;selected=null;original=null;if(closeAfterDiscard)onClose();closeAfterDiscard=false}){Text(uiText(R.string.file_confirm,"Confirm"))}},dismissButton={TextButton(onClick={discard=false}){Text(uiText(R.string.ui_037,"Cancel"))}})
}
