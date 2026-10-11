package com.codex.remote

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.api.*
import com.codex.remote.data.store.*
import com.codex.remote.domain.*
import com.codex.remote.ui.SkillMarketScreen
import com.codex.remote.ui.theme.CodexRemoteTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SkillMarketDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun preserveProductionDataDuringUpgrade() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("preserveData") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.checkSelfPermission(com.codex.remote.data.local.TERMUX_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) { rule.activity.requestPermissions(arrayOf(com.codex.remote.data.local.TERMUX_PERMISSION), 71) }
            withTimeout(60000) { while (context.checkSelfPermission(com.codex.remote.data.local.TERMUX_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) delay(200) }
        }
        val store = ApiStore(context); val original = store.providers()
        val provider = ApiProvider("market-preserve", "Preservation", "https://api.baiyun.si/v1", store.encryptKey("no-model-request"), ApiProtocol.RESPONSES,
            listOf(ApiModel("gpt-6.1-sol")), "gpt-6.1-sol")
        store.saveProviders(original + provider)
        try {
            val connection = SavedConnection(id="market-preserve", name="Preservation", host="localhost", username="root", authType=AuthType.PASSWORD, localAccess="root", localProviderId=provider.id)
            val transport = com.codex.remote.data.local.TermuxTransport.open(context, connection)
            com.codex.remote.data.rpc.CodexRpcClient(transport).use { client ->
                client.initialize()
                val path = "/sdcard/Download/codeb-v33-preserve.cjs"
                val script = client.readArtifact(path, "/")
                assertEquals(InstrumentationRegistry.getArguments().getString("scriptSha256"), skillDigest(script.decodeToString()))
                val result = client.request("command/exec", buildJsonObject {
                    put("command", buildJsonArray { add("/data/data/com.termux/files/usr/bin/node"); add("-e"); add(script.decodeToString()) })
                    put("cwd", transport.cwd); put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
                })
                assertEquals(result.toString(), 0, result["exitCode"]!!.jsonPrimitive.int)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", result["stdout"]!!.jsonPrimitive.content) })
            }
        } finally { store.saveProviders(original) }
    }

    @Test fun marketCanSearchOpenDetailsAndProtectOfflineTargets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("marketLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = SkillMarketStore(context).refresh()
        assertEquals(4, catalog.items.size)
        val vm = AppViewModel(context.applicationContext as android.app.Application, autoRestore = false)
        rule.setContent { CodexRemoteTheme { SkillMarketScreen(mapOf("QA offline" to vm), "QA offline", onBack = {}) } }
        rule.waitUntil(20000) { rule.onAllNodesWithText("手机 Markdown 笔记").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("install-codeb-notes").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("Canvas")
        rule.onNodeWithText("手机 Canvas 思维白板").assertExists()
        rule.onNodeWithText("手机 Markdown 笔记").assertDoesNotExist()
        rule.runOnIdle { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
        val details = com.codex.remote.ui.uiText(R.string.skills_details, "Details")
        rule.onNodeWithTag("skills-market").performScrollToNode(hasText(details))
        rule.onNodeWithText(details).performClick()
        rule.onNodeWithText(com.codex.remote.ui.uiText(R.string.skills_source, "Source and license")).assertExists()
    }

    @Test fun marketAutomaticallyPreparesLocalTargets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as android.app.Application
        val store = ApiStore(context); val original = store.providers()
        val provider = ApiProvider("market-auto", "Market auto", "https://api.baiyun.si/v1", store.encryptKey("no-model-request"), ApiProtocol.RESPONSES,
            listOf(ApiModel("gpt-6.1-sol")), "gpt-6.1-sol")
        val targets = mapOf("QA phone" to AppViewModel(app, autoRestore=false), "QA Root" to AppViewModel(app, autoRestore=false))
        val connections = mapOf("QA phone" to "standard", "QA Root" to "root").mapValues { (name, access) ->
            SavedConnection(id="market-auto-$access", name=name, host="localhost", username=access, authType=AuthType.PASSWORD,
                localAccess=access, localProviderId=provider.id)
        }
        store.saveProviders(original + provider)
        try {
            rule.setContent { CodexRemoteTheme { SkillMarketScreen(targets, "QA phone", connections, onBack = {}) } }
            for (name in targets.keys) {
                if (name == "QA Root") {
                    rule.onNodeWithTag("skills-target").performClick()
                    rule.onNodeWithText(name).performClick()
                }
                val vm = targets.getValue(name)
                rule.waitUntil(90000) {
                    check(vm.state.value.connectionStatus != ConnectionStatus.ERROR) { vm.state.value.connectionMessage }
                    vm.state.value.connectionStatus == ConnectionStatus.CONNECTED
                }
                rule.waitUntil(30000) { rule.onAllNodes(hasTestTag("install-codeb-notes") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(com.codex.remote.ui.uiText(R.string.skills_offline, "Offline")).assertDoesNotExist()
                rule.onNodeWithText(com.codex.remote.ui.uiText(R.string.setup_ready, "Ready")).assertExists()
            }
        } finally {
            rule.runOnIdle { targets.values.forEach { it.disconnect() } }
            store.saveProviders(original)
        }
    }

    @Test fun nativeTermuxAndRootDiscoverInstalledSkillAndRespectLocalEdits() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as android.app.Application
        if (context.checkSelfPermission(com.codex.remote.data.local.TERMUX_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) { rule.activity.requestPermissions(arrayOf(com.codex.remote.data.local.TERMUX_PERMISSION), 71) }
            withTimeout(60000) { while (context.checkSelfPermission(com.codex.remote.data.local.TERMUX_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) delay(200) }
        }
        val store = ApiStore(context)
        val original = store.providers()
        val provider = ApiProvider("market-check", "Market check", "https://api.baiyun.si/v1", store.encryptKey("no-model-request"), ApiProtocol.RESPONSES,
            listOf(ApiModel("gpt-6.1-sol")), "gpt-6.1-sol")
        val id = "codeb-market-check-${System.currentTimeMillis()}"
        val body = "---\nname: $id\ndescription: Test native skill installation.\n---\n# Test\n"
        val item = MarketSkill(id, "Check", "Check", "Notes", "1.0.0", "None", "https://example.com/source", "MIT", body, skillDigest(body))
        val models = mutableListOf<AppViewModel>()
        var installed = false
        store.saveProviders(original + provider)
        try {
            for (access in listOf("standard", "root")) {
                val vm = AppViewModel(app, autoRestore = false); models.add(vm)
                withContext(Dispatchers.Main) { vm.connect(SavedConnection(id="market-$access", name="Market $access", host="localhost", username=access,
                    authType=AuthType.PASSWORD, localAccess=access, localProviderId=provider.id)) }
                withTimeout(90000) { while (vm.state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    check(vm.state.value.connectionStatus != ConnectionStatus.ERROR) { vm.state.value.connectionMessage }; delay(100)
                } }
                if (access == "standard") {
                    val result = vm.marketSkills("install", item); installed = true
                    assertTrue(result["path"]!!.jsonPrimitive.content.endsWith("/.agents/skills/$id/SKILL.md"))
                    assertFalse(result["modified"]!!.jsonPrimitive.boolean)
                    withTimeout(45000) { while (vm.state.value.skills.none { it.name == id }) delay(100) }
                    val path = result["path"]!!.jsonPrimitive.content
                    val files = vm.phoneFiles()
                    val before = files.read(path)
                    files.save(path, "user edit".toByteArray(), com.codex.remote.data.local.fileRevision(before))
                    assertTrue(runCatching { vm.marketSkills("remove", id=id, expectedSha256=item.sha256) }.isFailure)
                    assertEquals("user edit", files.read(path).decodeToString())
                    files.save(path, before, com.codex.remote.data.local.fileRevision("user edit".toByteArray()))
                    val updated = item.copy(version="1.0.1", content=body+"Updated\n", sha256=skillDigest(body+"Updated\n"))
                    vm.marketSkills("install", updated, expectedSha256=item.sha256)
                } else {
                    val own = vm.marketSkills("list")["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
                    assertEquals("1.0.1", own["version"]!!.jsonPrimitive.content)
                    assertTrue(vm.state.value.skills.any { it.name == id })
                    val longBody = body + "手机📱".repeat(5000)
                    val longItem = item.copy(version="1.0.2", content=longBody, sha256=skillDigest(longBody))
                    vm.marketSkills("install", longItem, expectedSha256=own["sha256"]!!.jsonPrimitive.content)
                    vm.marketSkills("remove", id=id, expectedSha256=longItem.sha256); installed = false
                }
            }
        } finally {
            if (installed) models.lastOrNull()?.let { vm -> runCatching {
                val own = vm.marketSkills("list")["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
                vm.marketSkills("remove", id=id, expectedSha256=own["sha256"]!!.jsonPrimitive.content)
            } }
            withContext(Dispatchers.Main) { models.forEach { it.disconnect() } }
            store.saveProviders(original)
        }
    }
}
