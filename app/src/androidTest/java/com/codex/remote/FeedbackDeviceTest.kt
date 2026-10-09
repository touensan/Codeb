package com.codex.remote

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import com.codex.remote.data.store.*
import com.codex.remote.data.api.ReasoningCatalog
import com.codex.remote.data.api.ReasoningCatalogStore
import kotlinx.serialization.json.Json
import com.codex.remote.ui.CodexRemoteApp
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeedbackDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val real = ApplicationProvider.getApplicationContext<Application>()
    private val key = "feedback-${UUID.randomUUID()}"
    private val root = File(real.filesDir, key).apply { mkdirs() }
    private val isolated = object : Application() {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
        override fun getAssets() = real.assets
        override fun getSharedPreferences(name: String, mode: Int) = real.getSharedPreferences("$key-$name", mode)
        override fun getContentResolver() = real.contentResolver
    }
    private val viewModels = ViewModelStore()
    private lateinit var local: LocalViewModel
    private lateinit var conversation: LocalConversation

    @Before fun prepare() {
        com.codex.remote.ui.UiText.context = real
        val provider = ApiProvider(id = "qa", name = "QA provider", baseUrl = "https://example.com/v1", encryptedKey = "", selectedModel = "gpt-6-luna",
            models = listOf(ApiModel("gpt-6-luna"), ApiModel("gpt-6-astra")))
        ApiStore(isolated).saveProviders(listOf(provider))
        conversation = LocalConversation(title = "QA question", providerId = "qa", messages = listOf(LocalMessage(role = "user", text = "QA question"), LocalMessage(role = "assistant", text = "QA reply", completedAt = 123456789L)))
        rule.runOnUiThread { local = LocalViewModel(isolated); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        rule.runOnUiThread { local.provider("qa"); local.open(conversation) }
        val manager = AppViewModel(isolated, autoRestore = false).also { viewModels.put("manager", it) }
        rule.setContent { CodexRemoteTheme(darkTheme = false) { CodexRemoteApp(manager, local) } }
    }

    @After fun clean() { rule.runOnUiThread { viewModels.clear() } }

    @Test fun localReplyFooterMatchesRemoteCopyAndTimeLayout() {
        val reply = conversation.messages.last()
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(reply.completedAt!!))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("local-time-${reply.id}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("local-time-${reply.id}").assertTextEquals(time)
        val body = rule.onNodeWithTag("local-reply-${reply.id}").fetchSemanticsNode().boundsInRoot
        val copy = rule.onNodeWithTag("local-copy-${reply.id}").fetchSemanticsNode().boundsInRoot
        val stamp = rule.onNodeWithTag("local-time-${reply.id}").fetchSemanticsNode().boundsInRoot
        assertTrue(copy.left < body.left)
        assertTrue(copy.right <= stamp.left)
        rule.onNodeWithTag("local-copy-${reply.id}").performClick()
        val clipboard = real.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        rule.runOnIdle { assertEquals(reply.text, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }

    @Test fun titleEffortProviderAndAttachmentMenuMatchFeedback() {
        rule.onNodeWithTag("local-conversation-header").assertIsDisplayed()
        rule.onAllNodesWithTag("home-mode-chat").assertCountEquals(0)
        rule.onAllNodesWithTag("home-mode-work").assertCountEquals(0)
        rule.onNodeWithText("6 Luna " + real.getString(com.codex.remote.R.string.ui_242)).performClick()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_242)).assertExists()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_197)).assertDoesNotExist()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_191)).performClick()
        rule.onNodeWithText("QA provider").performClick()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_261)).assertIsDisplayed()
        val providerBounds = rule.onNodeWithTag("api-provider-selector").fetchSemanticsNode().boundsInRoot
        val optionsBounds = rule.onNodeWithTag("api-provider-options").fetchSemanticsNode().boundsInRoot
        assertTrue(optionsBounds.top >= providerBounds.bottom)
        File(real.getExternalFilesDir(null), "qa-provider-inline.png").outputStream().use {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNode(hasText("QA provider") and hasAnyAncestor(hasTestTag("api-provider-options"))).performClick()
        rule.onNodeWithText("gpt-6-luna").performClick()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_242)).performClick()
        rule.onNodeWithContentDescription(real.getString(com.codex.remote.R.string.ui_114)).performClick()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_257)).assertIsDisplayed()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.attach_images)).assertIsDisplayed()
        rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_271)).assertDoesNotExist()
    }

    @Test fun streamingGrowthFollowsUntilUserScrollsUp() {
        val many = (1..90).joinToString("\n\n") { "QA paragraph $it" }
        rule.runOnUiThread { local.open(local.state.value.selected!!.copy(messages = conversation.messages.dropLast(1) + conversation.messages.last().copy(text = many))) }
        try {
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithTag("local-copy-${conversation.messages.last().id}").assertExists() }.isSuccess
            }
        } catch (error: Throwable) {
            println("QA messages=" + local.state.value.selected!!.messages.map { it.id to it.text.length })
            println(rule.onRoot().printToString(maxDepth = 4))
            File(real.getExternalFilesDir(null), "qa-local-scroll-wait.png").outputStream().use {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw error
        }
        try {
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("local-conversation-bottom").assertIsDisplayed() }.isSuccess }
        } catch (error: Throwable) {
            File(real.getExternalFilesDir(null), "qa-local-scroll.png").outputStream().use {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw error
        }
        val reply = "local-reply-${conversation.messages.last().id}"
        val before = rule.onNodeWithTag(reply).fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag("local-conversation-list").performTouchInput { swipeDown() }
        rule.waitForIdle()
        val afterDrag = rule.onNodeWithTag(reply).fetchSemanticsNode().positionInRoot.y
        assertTrue("The gesture must actually scroll the long reply", afterDrag > before + 100)
        rule.runOnUiThread { local.open(local.state.value.selected!!.copy(messages = local.state.value.selected!!.messages + LocalMessage(role = "assistant", text = "QA new final"))) }
        rule.waitForIdle()
        rule.onNodeWithTag("local-conversation-bottom").assertIsNotDisplayed()
        assertEquals(afterDrag, rule.onNodeWithTag(reply).fetchSemanticsNode().positionInRoot.y, 3f)
    }

    @Test fun rapidModelSelectionsAndBackgroundRefreshKeepConversationChoice() {
        rule.runOnUiThread { local.selectModel("gpt-6-astra"); local.selectModel("gpt-6-luna"); local.refresh() }
        rule.waitUntil(5_000) { ApiStore(isolated).providers().single().selectedModel == "gpt-6-luna" }
        assertEquals("gpt-6-luna", local.state.value.selected!!.model)
        assertEquals("medium", local.state.value.selected!!.effort)
        rule.runOnUiThread { local.selectModel("gpt-6-luna", "high"); local.refresh() }
        rule.waitUntil(5_000) { ApiStore(isolated).providers().single().effort == "high" }
        assertEquals("gpt-6-luna", local.state.value.selected!!.model)
        assertEquals("high", local.state.value.selected!!.effort)
        rule.waitUntil(5_000) { ApiStore(isolated).conversation(conversation.id, false)?.effort == "high" }
        ApiStore(isolated).saveProviders(ApiStore(isolated).providers().map { it.copy(selectedModel = "gpt-6-astra", effort = "low") })
        lateinit var restored: LocalViewModel
        rule.runOnUiThread { restored = LocalViewModel(isolated); viewModels.put("restored", restored) }
        rule.waitUntil(5_000) { restored.state.value.selected != null }
        assertEquals("gpt-6-luna", restored.state.value.selected!!.model)
        assertEquals("high", restored.state.value.selected!!.effort)
    }

    @Test fun publicCatalogLoadsAndKeepsAnOfflineCopy() {
        val store = com.codex.remote.data.api.ReasoningCatalogStore(isolated)
        val catalog = store.refresh()
        assertTrue(File(root, "model-reasoning-catalog.json").isFile)
        assertEquals("medium", catalog.rule("gpt-6-luna", "https://example.com").defaultEffort)
        assertEquals(catalog, store.cached())
    }

    @Test fun ultraCanBeSelectedAndRestoredWithAnOldCatalogCache() {
        val store = ReasoningCatalogStore(isolated)
        val oldCatalog = store.bundled.copy(revision = 1, rules = store.bundled.rules.map { it.copy(efforts = it.efforts - "ultra") })
        File(root, "model-reasoning-catalog.json").writeText(Json.encodeToString(ReasoningCatalog.serializer(), oldCatalog))
        assertTrue(store.cached().rule("gpt-6.1-sol", "https://example.com").efforts.contains("ultra"))
        assertEquals(store.bundled.revision, store.refresh().revision)
        rule.runOnUiThread { local.selectModel("gpt-6.1-sol") }
        rule.onNodeWithText("6.1 Sol " + real.getString(com.codex.remote.R.string.ui_242)).performClick()
        rule.onNodeWithText("Ultra").assertIsDisplayed().performClick()
        rule.onNodeWithText("6.1 Sol Ultra").assertIsDisplayed()
        rule.waitUntil(5_000) { ApiStore(isolated).providers().single().effort == "ultra" && ApiStore(isolated).conversation(conversation.id, false)?.effort == "ultra" }
        lateinit var restored: LocalViewModel
        rule.runOnUiThread { restored = LocalViewModel(isolated); viewModels.put("restored", restored) }
        rule.waitUntil(5_000) { restored.state.value.selected != null }
        assertEquals("gpt-6.1-sol", restored.state.value.selected!!.model)
        assertEquals("ultra", restored.state.value.selected!!.effort)
        assertTrue(restored.state.value.catalog!!.rule("gpt-6.1-sol", "https://example.com").efforts.contains("ultra"))
    }

    @Test fun sentLocalPromptHasItsOwnCopyButton() {
        rule.onNodeWithTag("local-prompt-copy-${conversation.messages.first().id}").performClick()
        rule.runOnIdle {
            val clipboard = real.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("QA question", clipboard.primaryClip?.getItemAt(0)?.coerceToText(real)?.toString())
        }
    }

    @Test fun sidebarReopensAtTheTopPromotesTheOpenedConversationAndCanStartANewOne() {
        val older = (1..24).map { index -> LocalConversation(title = "QA older $index", providerId = "qa", updatedAt = index.toLong(),
            messages = listOf(LocalMessage(role = "user", text = "QA older $index"))) }
        older.forEach { ApiStore(isolated).save(it) }
        rule.runOnUiThread { local.refresh() }
        rule.waitUntil(5_000) { local.state.value.conversations.size == 25 }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithTag("sidebar-conversation-${conversation.id}").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-conversations").performScrollToNode(hasTestTag("sidebar-conversation-${older.first().id}"))
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, height / 2f)) }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithTag("sidebar-conversation-${conversation.id}").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-conversations").performScrollToNode(hasTestTag("sidebar-conversation-${older.first().id}"))
        rule.onNodeWithTag("sidebar-conversation-${older.first().id}").performClick()
        assertEquals(older.first().id, local.state.value.conversations.first().id)
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithTag("sidebar-conversation-${older.first().id}").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-new-conversation").performClick()
        assertTrue(local.state.value.selected!!.messages.isEmpty())
        assertEquals(26, local.state.value.conversations.size)
        assertEquals(local.state.value.selected!!.id, local.state.value.conversations.first().id)
    }

    @Test fun archiveCanBeCancelledAndNeverAskIsSavedOnlyWhenConfirmed() {
        val preferences = real.getSharedPreferences("archive-confirmation", Context.MODE_PRIVATE)
        val original = if (preferences.contains("skip")) preferences.getBoolean("skip", false) else null
        preferences.edit().putBoolean("skip", false).commit()
        try {
            rule.onNodeWithTag("home-menu").performClick()
            rule.onNodeWithTag("archive-conversation-${conversation.id}").performClick()
            rule.onNodeWithText(real.getString(com.codex.remote.R.string.archive_conversation_title)).assertIsDisplayed()
            rule.onNodeWithText(real.getString(com.codex.remote.R.string.dont_ask_archive)).performClick()
            rule.onNodeWithText(real.getString(com.codex.remote.R.string.ui_037)).performClick()
            assertFalse(preferences.getBoolean("skip", false))
            assertFalse(local.state.value.conversations.first { it.id == conversation.id }.archived)
            rule.onNodeWithTag("archive-conversation-${conversation.id}").performClick()
            rule.onNodeWithText(real.getString(com.codex.remote.R.string.dont_ask_archive)).performClick()
            rule.onNodeWithTag("confirm-archive").performClick()
            rule.waitUntil(5_000) { ApiStore(isolated).conversation(conversation.id, false)?.archived == true }
            assertTrue(preferences.getBoolean("skip", false))
            val second = conversation.copy(id = UUID.randomUUID().toString(), title = "QA second")
            rule.runOnUiThread { local.open(second) }
            rule.waitUntil(5_000) { local.state.value.conversations.any { it.id == second.id } }
            rule.onNodeWithTag("archive-conversation-${second.id}").performClick()
            rule.waitUntil(5_000) { ApiStore(isolated).conversation(second.id, false)?.archived == true }
            rule.onNodeWithTag("confirm-archive").assertDoesNotExist()
            assertEquals(second.messages, ApiStore(isolated).conversation(second.id, false)!!.messages)
        } finally {
            preferences.edit().let { if (original == null) it.remove("skip") else it.putBoolean("skip", original) }.commit()
        }
    }
}
