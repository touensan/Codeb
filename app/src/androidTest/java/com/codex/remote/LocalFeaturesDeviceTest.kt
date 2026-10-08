package com.codex.remote

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.data.diagnostics.DebugApi
import com.codex.remote.data.store.*
import com.codex.remote.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalFeaturesDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun nativeResourcesFollowChineseEnglishAndJapanese() {
        listOf("zh" to "保存", "en" to "Save", "ja" to "保存").forEach { (language, expected) ->
            val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
            assertEquals(expected, app.createConfigurationContext(configuration).getString(R.string.ui_038))
            assertTrue(app.createConfigurationContext(configuration).getString(R.string.import_summary, 2, 3).contains("2"))
        }
    }

    @Test fun openedHistoryIsIsolatedMergedAndWorkspaceSurvivesClearing() {
        val context = object : android.content.ContextWrapper(app) {
            private val root = File(app.filesDir, "self-check-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir(): File = root
        }
        val cache = LocalStore(context)
        val api = ApiStore(context)
        fun snapshot(host: String, items: List<TimelineItem>) = AppUiState(
            activeConnection = SavedConnection(id = host, name = "test", host = "example.com", username = "test", authType = AuthType.PASSWORD),
            selectedThreadId = "same-thread-id", selectedProjectPath = "/project", timeline = items,
            threads = listOf(RemoteThread("same-thread-id", "test", "/project", 1, "idle")),
        )
        cache.saveRemote(snapshot("host-a", listOf(TimelineItem("old", TimelineKind.USER, body = "old"))))
        cache.saveRemote(snapshot("host-a", listOf(TimelineItem("new", TimelineKind.AGENT, body = "new"))))
        cache.saveRemote(snapshot("host-b", listOf(TimelineItem("other", TimelineKind.AGENT, body = "separate"))))
        assertEquals(listOf("old", "new"), cache.session("host-a", "same-thread-id")!!.timeline.map { it.id })
        assertEquals("separate", cache.session("host-b", "same-thread-id")!!.timeline.single().body)
        assertNull(cache.session("host-a", "unopened-thread"))
        cache.saveDraft("host-a", "same-thread-id", "draft")
        assertEquals("draft", LocalStore(context).draft("host-a", "same-thread-id"))
        val cached = LocalConversation(title = "cached", messages = listOf(LocalMessage(role = "user", text = "hello")))
        val durable = cached.copy(id = UUID.randomUUID().toString(), workspace = true)
        api.save(cached)
        api.save(cached.copy(draft = "newer draft", revision = 2))
        api.save(cached.copy(draft = "stale draft", revision = 1))
        assertEquals("newer draft", api.list(false).single().draft)
        val primary = api.conversationFile(cached.id, false)
        assertTrue(primary.renameTo(File(primary.path + ".bak")))
        assertEquals("newer draft", api.list(false).single().draft)
        api.save(durable)
        val artifact = File(api.artifacts(durable.id), "result.txt").apply { writeText("keep me") }
        api.saveProviders(listOf(ApiProvider(name = "test", baseUrl = "https://example.com/v1", encryptedKey = "encrypted", selectedModel = "custom")))
        val oldGeneration = cache.cacheGeneration
        cache.clearCache()
        api.save(cached, oldGeneration)
        cache.saveRemote(snapshot("host-a", emptyList()), oldGeneration)
        assertNull(cache.index("host-a"))
        assertTrue(api.list(false).isEmpty())
        assertEquals(durable, ApiStore(context).list(true).single())
        assertEquals("keep me", artifact.readText())
        assertEquals("custom", ApiStore(context).providers().single().selectedModel)
    }

    @Test fun debugApiRequiresAuthenticationAndNeverEchoesErrorMessages() {
        DebugApi.attach(app)
        DebugApi.enable(true)
        try {
            DebugApi.record("self_check", IllegalStateException("secret-api-key-do-not-echo"))
            fun request(path: String, authenticate: Boolean): Pair<Int, String> {
                return java.net.Socket("127.0.0.1", DebugApi.PORT).use { socket ->
                    socket.soTimeout = 5_000
                    val auth = if (authenticate) "Authorization: Bearer ${DebugApi.token}\r\n" else ""
                    socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n${auth}\r\n".toByteArray())
                    val response = socket.getInputStream().bufferedReader().readText()
                    response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
                }
            }
            assertEquals(401, request("/health", false).first)
            assertEquals(200, request("/health", true).first)
            val logs = request("/logs", true).second
            assertTrue(logs.contains("self_check"))
            assertFalse(logs.contains("secret-api-key-do-not-echo"))
        } finally { DebugApi.enable(false) }
    }
}
