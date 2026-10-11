package com.codex.remote

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.local.*
import com.codex.remote.data.store.*
import com.codex.remote.ui.*
import com.codex.remote.ui.theme.CodexRemoteTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.codex.remote.data.api.OpenAiClient
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalWorkspaceUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private var previousContext:android.content.Context?=null
    @org.junit.Before fun prepare(){previousContext=UiText.context;UiText.context=rule.activity}
    @org.junit.After fun restore(){UiText.context=previousContext}
    @Test fun dailyModelPickerWorksDuringReplyAndNextRequestsUseTheSelection() {
        val app=rule.activity.application
        val store=ApiStore(app); val original=store.providers()
        val provider=ApiProvider("daily-model-check","Daily check","https://example.com/v1",store.encryptKey("offline-check"),
            models=listOf(ApiModel("model-a",listOf("low","high"),true),ApiModel("model-b",listOf("low","high"),true)),selectedModel="model-a",effort="low")
        val other=provider.copy(id="daily-other-check",name="Other check",models=listOf(ApiModel("model-c",listOf("low"),true)),selectedModel="model-c")
        val gates=CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        val calls=CopyOnWriteArrayList<Triple<String,String,String>>()
        val fake=object:OpenAiClient() {
            override suspend fun stream(provider:ApiProvider,key:String,messages:List<LocalMessage>,prefix:JsonArray?,onDelta:(String)->Unit) {
                val gate=CompletableDeferred<Unit>(); gates+=gate
                calls+=Triple(provider.id,provider.selectedModel,provider.effort)
                onDelta("partial-${messages.last().text}")
                withContext(NonCancellable){gate.await();onDelta("-late")}
            }
        }
        store.saveProviders(original+provider+other)
        val vm=LocalViewModel(app,fake)
        val lifecycle=androidx.lifecycle.ViewModelStore().apply {put("daily-model-check",vm)}
        try {
            rule.waitUntil(10000){vm.state.value.providers.any {it.id==provider.id}}
            rule.runOnIdle {vm.provider(provider.id);vm.newConversation()}
            rule.setContent {CodexRemoteTheme {LocalPane(vm,false,{})}}
            rule.runOnIdle {vm.draft("first");vm.send()}
            rule.waitUntil(10000){calls.size==1&&vm.state.value.selected!!.messages.last().text.isNotEmpty()}
            rule.onNodeWithTag("daily-model").assertIsEnabled().performClick()
            rule.onNodeWithText(uiText(R.string.ui_191,"Model")).performClick()
            rule.onNodeWithText("model-b").performClick()
            assertEquals("model-b",vm.state.value.selected!!.model)
            assertTrue(vm.state.value.sending)
            assertEquals(Triple(provider.id,"model-a","low"),calls.single())
            rule.runOnIdle {vm.selectModel("model-b","high");vm.selectModel("not-configured");vm.draft("queued");vm.send()}
            assertEquals("model-b",vm.state.value.selected!!.model)
            assertEquals("high",vm.state.value.selected!!.effort)
            gates[0].complete(Unit)
            rule.waitUntil(10000){calls.size==2}
            assertEquals(Triple(provider.id,"model-b","high"),calls[1])
            rule.runOnIdle {vm.provider(other.id);vm.selectModel("model-c","low");vm.draft("steered");vm.send(steer=true)}
            rule.waitUntil(10000){calls.size==3}
            assertEquals(Triple(other.id,"model-c","low"),calls[2])
            gates[1].complete(Unit)
            rule.waitUntil(10000){vm.state.value.selected!!.messages.last().text=="partial-steered"}
            assertTrue(vm.state.value.sending)
            assertFalse(vm.state.value.selected!!.messages.first {it.text.startsWith("partial-queued")}.text.endsWith("-late"))
            gates[2].complete(Unit)
            rule.waitUntil(10000){!vm.state.value.sending}
            assertEquals("model-c",vm.state.value.selected!!.model)
        } finally {
            gates.forEach {it.complete(Unit)}
            rule.runOnIdle {lifecycle.clear()}
            store.saveProviders(original)
        }
    }
    @Test fun rootPreferenceAndModelPickerRemainAfterReopeningSettings() {
        val prefs=rule.activity.getSharedPreferences("phone-work",0)
        val before=prefs.all
        prefs.edit().remove("configured").putString("access","standard").apply()
        val provider=ApiProvider("ui-test-provider","UI test","https://example.com/v1","",ApiProtocol.RESPONSES,
            listOf(ApiModel("model-a"),ApiModel("model-b")),"model-a")
        var generation by mutableIntStateOf(0)
        try {
            rule.setContent { CodexRemoteTheme {key(generation){LocalWorkScreen(listOf(provider),provider.id,{}, {}, {}, {_,_->})}} }
            rule.onNodeWithTag("local-root-switch").performClick()
            rule.onNodeWithTag("local-model").performClick()
            rule.onNodeWithTag("local-model-model-b").performClick()
            assertEquals("root",prefs.getString("access",null))
            assertEquals("model-b",prefs.getString("model:${provider.id}",null))
            rule.runOnIdle {generation++}
            rule.onNodeWithTag("local-root-switch").assertIsOn()
            rule.onNodeWithTag("local-model").assertTextContains("model-b",substring=true)
        } finally {
            val edit=prefs.edit().clear();before.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v)}};edit.commit()
        }
    }
    @Test fun markdownOpensInDialogEditorAndSavesBeforeReturningToDirectory() {
        val folder=File(rule.activity.cacheDir,"dialog-${UUID.randomUUID()}").apply {mkdirs()}
        val file=File(folder,"note.md").apply {writeText("# Original\n")}
        val files=PhoneFiles(rule.activity,folder.path)
        try {
            rule.setContent {CodexRemoteTheme {FileManagerScreen(files,folder.path,file.path){}}}
            rule.waitUntil(10000) {rule.onAllNodesWithTag("file-editor").fetchSemanticsNodes().isNotEmpty()}
            rule.onNode(isDialog()).assertExists()
            val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info=automation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS};automation.serviceInfo=info
            fun find(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(node==null)return null
                if(node.className=="android.widget.EditText"&&node.contentDescription=="Codeb file editor")return node
                for(i in 0 until node.childCount)find(node.getChild(i))?.let {return it};return null
            }
            var editor:AccessibilityNodeInfo?=null
            rule.waitUntil(10000){editor=automation.windows.firstNotNullOfOrNull {find(it.root)};editor!=null}
            val longLine="Long text without inserted newlines ".repeat(40)
            assertTrue(editor!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,longLine)}))
            rule.waitForIdle()
            rule.onNodeWithTag("file-save").performClick()
            rule.waitUntil(10000){file.readText()==longLine}
            assertFalse(file.readText().contains("\n"))
            assertTrue(editor!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"# Saved from editor\n")}))
            rule.onNodeWithTag("file-save").performClick()
            rule.waitUntil(10000){file.readText()=="# Saved from editor\n"}
            rule.onNodeWithText(rule.activity.getString(R.string.file_directory)).performClick()
            rule.onNodeWithTag("file-list").assertIsDisplayed()
            assertTrue(runBlocking {runCatching {files.save(file.path,"overwrite".toByteArray(),fileRevision("# Original\n".toByteArray()))}.isFailure})
        } finally {file.delete();folder.delete()}
    }
    @Test fun explicitEnvironmentCheckAndDeployUseTermuxCallback() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime")=="true")
        val context=rule.activity
        assertTrue(RuntimeSetup.run(context,false,false).contains("0.162.0"))
        assertTrue(RuntimeSetup.run(context,true,true).contains("Root UID: 0"))
    }
}
