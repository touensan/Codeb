package com.codex.remote

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.data.local.TermuxTransport
import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.store.*
import com.codex.remote.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class LocalRuntimeDeviceTest {
    @Test fun localModelPreferenceWinsOverCachedConversationAndNewTask() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as android.app.Application
        val store=ApiStore(context);val original=store.providers()
        val provider=ApiProvider("model-preference-check","Check","https://api.baiyun.si/v1",store.encryptKey("no-inference"),ApiProtocol.RESPONSES,
            listOf(ApiModel("gpt-5.6-luna"),ApiModel("gpt-6.1-sol")),"gpt-5.6-luna")
        val connection=SavedConnection(id="model-preference-check",name="Check",host="localhost",username="standard",authType=AuthType.PASSWORD,localAccess="standard",localProviderId=provider.id)
        store.saveProviders(original+provider)
        context.getSharedPreferences("phone-work",0).edit().putString("model:${provider.id}","gpt-6.1-sol").commit()
        LocalStore(context).saveRemote(AppUiState(activeConnection=connection,selectedThreadId="old-model-check",selectedProjectPath=com.codex.remote.data.local.TERMUX_HOME,
            selectedModel="gpt-5.6-luna",timeline=listOf(TimelineItem("check",TimelineKind.USER,body="Cache check"))))
        val vm=AppViewModel(app,autoRestore=false)
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){vm.connect(connection)}
            withTimeout(60000){while(vm.state.value.connectionStatus!=ConnectionStatus.CONNECTED){check(vm.state.value.connectionStatus!=ConnectionStatus.ERROR){vm.state.value.connectionMessage};kotlinx.coroutines.delay(100)}}
            assertEquals("gpt-6.1-sol",vm.state.value.selectedModel)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){vm.newThread()}
            assertEquals("gpt-6.1-sol",vm.state.value.selectedModel)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){vm.disconnect()}
            store.saveProviders(original);context.getSharedPreferences("phone-work",0).edit().remove("model:${provider.id}").commit()
        }
    }
    private fun stage(value: String) { InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "LOCAL_CHECK $value\n") }) }
    @Test fun actualTermuxAndKsuCanEditFilesAndRunCommandsWithoutChangingProviders() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val store = ApiStore(context)
        val original = store.providers()
        val provider = ApiProvider(id = "device-local-runtime-check", name = "Runtime check", baseUrl = "https://api.baiyun.si/v1",
            encryptedKey = store.encryptKey("connectivity-only-no-inference"), protocol = ApiProtocol.RESPONSES,
            models = listOf(ApiModel("gpt-6.1-sol")), selectedModel = "gpt-6.1-sol")
        store.saveProviders(original + provider)
        try {
            assertEquals("Grant Termux command permission through Codeb before this hardware test", android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(com.codex.remote.data.local.TERMUX_PERMISSION))
            for (access in listOf("root", "standard")) {
                withTimeout(120_000) {
                    stage("$access opening")
                    val connection = SavedConnection(id = "check-$access", name = access, host = "localhost", username = access, authType = AuthType.PASSWORD,
                        localAccess = access, localProviderId = provider.id)
                    val transport = TermuxTransport.open(context, connection)
                    stage("$access opened uid=${transport.uid}")
                    assertEquals(access == "root", transport.uid == 0)
                    CodexRpcClient(transport).use { client ->
                        assertEquals("0.162.0", client.initialize().codexVersion)
                        stage("$access initialized")
                        val file = "${transport.cwd}/codeb-device-check.txt"
                        client.request("fs/writeFile", buildJsonObject { put("path", file); put("dataBase64", Base64.getEncoder().encodeToString("original\n".toByteArray())) })
                        // Exceed the phone's background-freeze delay before accepting a reply.
                        val result = withTimeout(20_000) { client.request("command/exec", buildJsonObject {
                            put("command", buildJsonArray { add("/data/data/com.termux/files/usr/bin/bash"); add("-c"); add("sleep 8; id; printf 'edited\\n' >> codeb-device-check.txt; cat codeb-device-check.txt") })
                            put("cwd", transport.cwd); put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
                        }) }
                        assertEquals(0, result["exitCode"]!!.jsonPrimitive.int)
                        val output = result["stdout"]!!.jsonPrimitive.content
                        assertTrue(output.contains("edited"))
                        assertEquals(access == "root", output.contains("uid=0(root)"))
                        assertTrue(client.readArtifact(file, transport.cwd).decodeToString().contains("edited"))
                        val files=com.codex.remote.data.local.PhoneFiles(context,"/",client)
                        val folder="${transport.cwd}/codeb-files-check-${java.util.UUID.randomUUID()}"
                        files.mkdir(folder)
                        val managed="$folder/test.md"
                        files.save(managed,"# 文件测试\n".toByteArray(),null)
                        val before=files.read(managed)
                        assertTrue(files.list(folder).any {it.name=="test.md"})
                        files.save(managed,"# 已编辑\n".toByteArray(),com.codex.remote.data.local.fileRevision(before))
                        assertTrue(runCatching {files.save(managed,before,com.codex.remote.data.local.fileRevision(before))}.isFailure)
                        assertTrue(runCatching {files.delete(folder)}.isFailure)
                        files.rename(managed,"renamed.md")
                        assertTrue(files.read("$folder/renamed.md").decodeToString().contains("已编辑"))
                        files.delete("$folder/renamed.md");files.delete(folder)
                        if (access == "standard") {
                            val read = runCatching { client.request("fs/readFile", buildJsonObject { put("path", "/data/data/com.termux/files/home/.local/share/codeb/workspaces/root/codeb-device-check.txt") }) }
                            assertTrue("Ordinary Android UID must not directly read root-owned workspace", read.isFailure)
                        }
                    }
                    stage("$access closed")
                }
            }
        } finally { store.saveProviders(original) }
        assertEquals(original, store.providers())
    }
}
