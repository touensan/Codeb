package com.codex.remote

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import com.codex.remote.data.api.OpenAiClient
import com.codex.remote.data.store.*
import com.codex.remote.ui.screens.RichBody
import com.codex.remote.ui.theme.CodexRemoteTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class RealtimeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val real = ApplicationProvider.getApplicationContext<Application>()
    private val key = "realtime-${UUID.randomUUID()}"
    private val root = File(real.filesDir, key).apply { mkdirs() }
    private val isolated = object : Application() {
        override fun getApplicationContext(): android.content.Context = this
        override fun getFilesDir() = root
        override fun getAssets() = real.assets
        override fun getSharedPreferences(name: String, mode: Int) = real.getSharedPreferences("$key-$name", mode)
        override fun getContentResolver() = real.contentResolver
    }
    private val viewModels = ViewModelStore()
    @After fun clean() { rule.runOnUiThread { viewModels.clear() } }

    @Test fun codebIdentityAndTutorialLinkUseThePublicApiGuide() {
        assertEquals("白云Codeb", real.getString(R.string.app_name))
        assertEquals("白云Codeb", com.codex.remote.data.api.CloudAbout().name)
        val application = real.packageManager.getApplicationInfo(real.packageName, 0)
        assertEquals("白云Codeb", application.loadLabel(real.packageManager).toString())
        for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.JAPANESE)) {
            val configuration = android.content.res.Configuration(real.resources.configuration).apply { setLocale(locale) }
            assertEquals("白云Codeb", real.createConfigurationContext(configuration).getString(R.string.app_name))
        }
        val icon = application.loadIcon(real.packageManager) as android.graphics.drawable.AdaptiveIconDrawable
        assertEquals(android.graphics.Color.WHITE, (icon.background as android.graphics.drawable.ColorDrawable).color)
        val foreground = (icon.foreground as android.graphics.drawable.BitmapDrawable).bitmap
        assertEquals(0, android.graphics.Color.alpha(foreground.getPixel(0, 0)))
        val iconBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(android.graphics.Canvas(iconBitmap))
        assertEquals(android.graphics.Color.WHITE, iconBitmap.getPixel(256, 8))
        val iconPixels = IntArray(512 * 512)
        iconBitmap.getPixels(iconPixels, 0, 512, 0, 0, 512, 512)
        assertTrue(iconPixels.count { android.graphics.Color.alpha(it) > 240 && android.graphics.Color.red(it) > 190 && android.graphics.Color.green(it) < 130 && android.graphics.Color.blue(it) < 130 } > 500)
        assertTrue(iconPixels.count { android.graphics.Color.alpha(it) > 240 && android.graphics.Color.blue(it) > 190 && android.graphics.Color.red(it) < 160 } > 500)
        assertTrue(iconPixels.count { android.graphics.Color.alpha(it) > 240 && android.graphics.Color.red(it) > 230 && android.graphics.Color.green(it) > 170 && android.graphics.Color.blue(it) < 130 } > 500)
        val coloredPixels = iconPixels.indices.filter { index ->
            val pixel = iconPixels[index]
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            android.graphics.Color.alpha(pixel) > 240 && maxOf(red, green, blue) - minOf(red, green, blue) > 60
        }
        val artworkWidth = coloredPixels.maxOf { it % 512 } - coloredPixels.minOf { it % 512 } + 1
        val artworkHeight = coloredPixels.maxOf { it / 512 } - coloredPixels.minOf { it / 512 } + 1
        assertTrue("Artwork should fill ~80% of the icon: ${artworkWidth}x${artworkHeight}", artworkWidth in 390..435 && artworkHeight in 375..435)
        File(real.getExternalFilesDir(null), "codeb-launcher-verified.png").outputStream().use { iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var opened: String? = null
        val context = object : android.content.ContextWrapper(real) {
            override fun startActivity(intent: android.content.Intent) { opened = intent.dataString }
        }
        val local = LocalViewModel(isolated).also { viewModels.put("local", it) }
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context) {
                CodexRemoteTheme(darkTheme = false) { androidx.compose.foundation.layout.Column { com.codex.remote.ui.CloudSettings(LocalUiState(), local) } }
            }
        }
        rule.onNodeWithTag("open-codeb-guide").performClick()
        assertEquals("https://api.baiyun.si/docs/codeb/", opened)
    }

    @Test fun anOpenedEmptyProjectIsPersistedWithoutCreatingAServerThread() {
        val host = com.codex.remote.domain.SavedConnection(name = "QA", host = "example.com", username = "qa", authType = com.codex.remote.domain.AuthType.PASSWORD)
        val state = com.codex.remote.domain.AppUiState(activeConnection = host, selectedProjectPath = "/srv/new",
            projects = com.codex.remote.domain.groupThreadsByProject(emptyList(), listOf("/srv/new")))
        val store = LocalStore(isolated)
        store.saveRemote(state)
        assertEquals(listOf("/srv/new"), store.index(host.id)!!.projectPaths)
        assertTrue(store.index(host.id)!!.threads.isEmpty())
    }

    @Test fun simultaneousLocalRequestsKeepStreamingAfterSwitchAndStopOnlyTheSelectedRequest() {
        com.codex.remote.ui.UiText.context = real
        val deltas = ConcurrentHashMap<String, (String) -> Unit>()
        val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val stopped = ConcurrentHashMap.newKeySet<String>()
        val client = object : OpenAiClient() {
            override suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?, onDelta: (String) -> Unit) {
                val prompt = messages.last().text
                val completion = CompletableDeferred<Unit>()
                completions[prompt] = completion
                deltas[prompt] = onDelta
                try { completion.await() } finally { stopped += prompt }
            }
        }
        val store = ApiStore(isolated)
        store.saveProviders(listOf(ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), selectedModel = "gpt-6-luna")))
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        rule.runOnUiThread { local.newConversation(); local.draft("A"); local.send() }
        rule.waitUntil(5_000) { deltas.containsKey("A") }
        val a = local.state.value.selected!!
        rule.runOnUiThread { local.newConversation(); local.draft("B"); local.send() }
        rule.waitUntil(5_000) { deltas.containsKey("B") }
        val b = local.state.value.selected!!
        rule.runOnUiThread { local.newConversation(); local.draft("C"); local.send() }
        rule.waitUntil(5_000) { deltas.containsKey("C") }
        val c = local.state.value.selected!!
        deltas.getValue("A")("alpha ")
        deltas.getValue("B")("beta ")
        assertEquals(setOf(a.id, b.id, c.id), local.state.value.runningConversationIds)
        assertEquals(listOf(c.id, b.id, a.id), local.state.value.conversations.map { it.id })
        rule.runOnUiThread { local.open(a) }
        assertEquals("alpha ", local.state.value.selected!!.messages.last().text)
        assertTrue(local.state.value.sending)
        deltas.getValue("B")("background")
        repeat(10) { deltas.getValue("C")("other "); deltas.getValue("B")(".") }
        assertEquals(listOf(a.id, c.id, b.id), local.state.value.conversations.map { it.id })
        completions.getValue("C").complete(Unit)
        rule.waitUntil(5_000) { c.id !in local.state.value.runningConversationIds }
        rule.runOnUiThread { local.refresh() }
        rule.waitUntil(5_000) { store.conversation(c.id, false)?.messages?.lastOrNull()?.text == "other ".repeat(10) }
        assertEquals(listOf(a.id, c.id, b.id), local.state.value.conversations.map { it.id })
        rule.runOnUiThread { local.stop() }
        rule.waitUntil(5_000) { a.id !in local.state.value.runningConversationIds }
        assertEquals(setOf(b.id), local.state.value.runningConversationIds)
        assertTrue("A" in stopped)
        assertFalse("B" in stopped)
        rule.runOnUiThread { local.open(b) }
        assertEquals("beta background" + ".".repeat(10), local.state.value.selected!!.messages.last().text)
        completions.getValue("B").complete(Unit)
        rule.waitUntil(5_000) { local.state.value.runningConversationIds.isEmpty() }
        rule.waitUntil(5_000) { store.conversation(b.id, false)?.messages?.lastOrNull()?.text == "beta background" + ".".repeat(10) }
        assertEquals("alpha ", store.conversation(a.id, false)!!.messages.last().text)
        assertNotNull(store.conversation(a.id, false)!!.messages.last().completedAt)
        assertNotNull(store.conversation(b.id, false)!!.messages.last().completedAt)
        assertNotNull(store.conversation(c.id, false)!!.messages.last().completedAt)
        assertEquals(listOf(b.id, a.id, c.id), local.state.value.conversations.map { it.id })
        lateinit var restored: LocalViewModel
        rule.runOnUiThread { restored = LocalViewModel(isolated, client); viewModels.put("restored", restored) }
        rule.waitUntil(5_000) { restored.state.value.selected != null }
        assertEquals(b.id, restored.state.value.selected!!.id)
        assertEquals(listOf(b.id, a.id, c.id), restored.state.value.conversations.map { it.id })
    }

    @Test fun svgAnimationActuallyRendersInsideAnIsolatedWebView() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="180"><circle id="dot" cx="10" cy="80" r="9"><animate attributeName="cx" values="10;250;10" dur="1s" repeatCount="indefinite"/></circle></svg><script>setInterval(()=>parent.postMessage('svg:'+document.getElementById('dot').cx.animVal.value,'*'),50)</script>"""
        rule.setContent { CodexRemoteTheme { RichBody("```html\n$svg\n```") } }
        var web: WebView? = null
        rule.waitUntil(5_000) { rule.runOnUiThread { web = findWeb(rule.activity.window.decorView) }; web != null }
        rule.runOnUiThread { web!!.evaluateJavascript("window.__frames=[];window.addEventListener('message',e=>{if(typeof e.data==='string'&&e.data.startsWith('svg:'))window.__frames.push(Number(e.data.slice(4)))})", null) }
        var rendered = false
        rule.waitUntil(10_000) {
            rule.runOnUiThread { web!!.evaluateJavascript("window.__frames.length>3 && Math.max(...window.__frames)-Math.min(...window.__frames)>5", { rendered = it == "true" }) }
            rendered
        }
        var sandboxed = false
        rule.runOnUiThread { web!!.evaluateJavascript("document.querySelector('iframe').contentDocument===null", { sandboxed = it == "true" }) }
        rule.waitUntil(5_000) { sandboxed }
        rule.runOnUiThread {
            assertFalse(web!!.settings.allowFileAccess)
            assertFalse(web!!.settings.allowContentAccess)
        }
    }

    @Test fun fullSizeSvgActuallyPaintsVisiblePixels() {
        val useActual = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("actualSvg") == "true"
        val svg = if (useActual) File(real.getExternalFilesDir(null), "qa-user-svg-response.txt").readText() else """```svg
<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600"><defs><linearGradient id="sky"><stop stop-color="#fff8e9"/><stop offset="1" stop-color="#f3ecd8"/></linearGradient><g id="wheel"><circle r="94" fill="#536a70"/></g></defs><rect width="800" height="600" fill="url(#sky)"/><use href="#wheel" x="200" y="360"/><use href="#wheel" x="600" y="360"/></svg>
```"""
        rule.setContent { CodexRemoteTheme(darkTheme = false) { RichBody(svg) } }
        var web: WebView? = null
        var ready = false
        rule.waitUntil(5_000) { rule.runOnUiThread { web = findWeb(rule.activity.window.decorView); ready = web?.progress == 100 && web!!.width > 0 }; ready }
        var visual = false
        rule.runOnUiThread { web!!.postVisualStateCallback(1, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { visual = true } }) }
        rule.waitUntil(5_000) { visual }
        var dimensions = ""
        rule.runOnUiThread { web!!.evaluateJavascript("JSON.stringify({viewport:[innerWidth,innerHeight],frame:document.querySelector('iframe').getBoundingClientRect().toJSON()})", { dimensions = it }) }
        rule.waitUntil(5_000) { dimensions.isNotEmpty() }
        var bitmap: Bitmap? = null
        var copied = false
        rule.runOnUiThread {
            val location = IntArray(2).also { web!!.getLocationInWindow(it) }
            bitmap = Bitmap.createBitmap(web!!.width, web!!.height, Bitmap.Config.ARGB_8888)
            web!!.postOnAnimation { web!!.postOnAnimation {
                PixelCopy.request(rule.activity.window, Rect(location[0], location[1], location[0] + web!!.width, location[1] + web!!.height), bitmap!!,
                    { copied = it == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            } }
        }
        rule.waitUntil(5_000) { copied }
        val image = bitmap!!
        var colored = 0
        var samples = 0
        for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
            val pixel = image.getPixel(x, y)
            val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
            if (channels.max() - channels.min() > 12) colored++
            samples++
        }
        File(real.getExternalFilesDir(null), "qa-svg-pixels.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        assertTrue("SVG must paint visible colored pixels, not just have a live DOM: $colored/$samples $dimensions", colored > samples / 5)
    }

    @Test fun frequentDeltasRenderWhileTheStreamIsStillProducing() {
        val text = androidx.compose.runtime.mutableStateOf("")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        rule.setContent { CodexRemoteTheme { RichBody(text.value, streaming = true) } }
        val durations = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val listener = object : android.view.Choreographer.FrameCallback {
            var previous = 0L
            override fun doFrame(time: Long) {
                if (previous != 0L) durations.add(time - previous)
                previous = time
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        rule.runOnUiThread { android.view.Choreographer.getInstance().postFrameCallback(listener) }
        val worker = Thread {
            try { repeat(150) { index ->
                rule.runOnUiThread { text.value += "word$index. " }
                Thread.sleep(8)
            } } finally { done.set(true) }
        }.apply { start() }
        var seenDuringStream = false
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                fun visible(view: View): Boolean = if (view is android.widget.TextView) view.text.toString().contains("word")
                    else view is ViewGroup && (0 until view.childCount).any { visible(view.getChildAt(it)) }
                seenDuringStream = !done.get() && visible(rule.activity.window.decorView)
            }
            seenDuringStream || done.get()
        }
        worker.join(5_000)
        assertTrue("Continuous deltas must not postpone rendering until completion", seenDuringStream)
        try {
            rule.waitUntil(30_000) { rule.runOnUiThread { seenDuringStream = findReveal(rule.activity.window.decorView)?.text?.toString() == text.value.trimEnd() }; seenDuringStream }
        } catch (error: Throwable) {
            var count = -1
            rule.runOnUiThread { count = findReveal(rule.activity.window.decorView)?.text?.length ?: -1 }
            val intervals = durations.filter { it > 0 }.sorted()
            val p95 = intervals.getOrNull((intervals.size * 0.95).toInt())?.div(1_000_000.0)
            throw AssertionError("Native characters=$count/${text.value.trimEnd().length}, frame p95=${p95}ms, producerAlive=${worker.isAlive}", error)
        } finally {
            rule.runOnUiThread {
                android.view.Choreographer.getInstance().removeFrameCallback(listener)
                println("QA stream lengths actual=${findReveal(rule.activity.window.decorView)?.text?.length}, expected=${text.value.trimEnd().length}, producerAlive=${worker.isAlive}")
            }
        }
        val frames = durations.filter { it > 0 }.sorted()
        assertTrue("Frame metrics must be collected on real hardware", frames.size > 10)
        val p95 = frames[(frames.size * 0.95).toInt().coerceAtMost(frames.lastIndex)] / 1_000_000.0
        println("QA streaming frame intervals=${frames.size}, p95=${p95}ms")
        assertTrue("Streaming must not stall native frames: p95=${p95}ms", p95 < 50)
    }

    @Test fun aStreamedHeadingKeepsItsParagraphsAfterCompletionAndAnotherMessage() {
        com.codex.remote.ui.UiText.context = real
        var delta: ((String) -> Unit)? = null
        val done = CompletableDeferred<Unit>()
        val client = object : OpenAiClient() {
            override suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?, onDelta: (String) -> Unit) {
                delta = onDelta
                done.await()
            }
        }
        val store = ApiStore(isolated)
        store.saveProviders(listOf(ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), selectedModel = "qa-model")))
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        rule.runOnUiThread { local.newConversation(); local.draft("木兰诗全文"); local.send() }
        rule.setContent { CodexRemoteTheme(darkTheme = false) {
            val state = local.state.collectAsState().value
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().testTag("local-conversation-list")) {
                items(state.selected?.messages.orEmpty().size) { index ->
                    val message = state.selected!!.messages[index]
                    if (message.role == "assistant") RichBody(message.text, Modifier.testTag("local-reply-${message.id}"), streaming = state.sending && index == state.selected!!.messages.lastIndex)
                    else androidx.compose.material3.Text(message.text)
                }
            }
        } }
        rule.waitUntil(5_000) { delta != null }
        val full = "## 《木兰诗》\n**南北朝·乐府民歌**\n\n" + "唧唧复唧唧，木兰当户织。  \n不闻机杼声，惟闻女叹息。\n\n".repeat(8)
        val expected = io.noties.markwon.Markwon.create(real).toMarkdown(full).toString()
        delta!!(full)
        var sawBody = false
        rule.waitUntil(10_000) { rule.runOnUiThread { sawBody = findReveal(rule.activity.window.decorView)?.text?.contains("木兰当户织") == true }; sawBody }
        done.complete(Unit)
        rule.waitUntil(5_000) { !local.state.value.sending }
        val message = local.state.value.selected!!.messages.last()
        var painted = false
        try { rule.waitUntil(15_000) { rule.runOnUiThread { painted = findReveal(rule.activity.window.decorView)?.text?.toString() == expected }; painted } }
        catch (error: Throwable) {
            var detail = ""
            rule.runOnUiThread { val native = findReveal(rule.activity.window.decorView); detail = "stored=${local.state.value.selected!!.messages.first { it.id == message.id }.text.length}, native=${native?.text}, height=${native?.height}, layout=${native?.layout?.height}" }
            throw AssertionError(detail, error)
        }
        rule.runOnUiThread {
            val native = checkNotNull(findReveal(rule.activity.window.decorView))
            assertTrue(native.text.toString().contains("不闻机杼声"))
            assertTrue("Native height=${native.height}, layout=${native.layout.height}, chars=${native.text.length}", native.height >= native.layout.height)
        }
        rule.runOnUiThread { local.draft("给我 Markdown 格式"); local.send() }
        rule.onNodeWithTag("local-conversation-list").performScrollToNode(androidx.compose.ui.test.hasTestTag("local-reply-${message.id}"))
        rule.runOnUiThread {
            val native = checkNotNull(findReveal(rule.activity.window.decorView))
            assertTrue(native.text.toString().contains("不闻机杼声"))
            assertTrue("Native height=${native.height}, layout=${native.layout.height}, chars=${native.text.length}", native.height >= native.layout.height)
        }
        assertEquals(full, store.conversation(local.state.value.selected!!.id, false)!!.messages.first { it.id == message.id }.text)
    }

    @Test fun replacingAShortReplyWithALongReplyPublishesItsPaintedFooter() {
        val text = androidx.compose.runtime.mutableStateOf("Short reply.")
        val displayed = androidx.compose.runtime.mutableStateOf("")
        rule.setContent { CodexRemoteTheme {
            androidx.compose.foundation.layout.Column {
                RichBody(text.value, onDisplayed = { displayed.value = it })
                if (displayed.value == text.value) androidx.compose.material3.Text("Painted footer")
            }
        } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Painted footer").assertExists() }.isSuccess }
        rule.runOnUiThread { text.value = (1..90).joinToString("\n\n") { "QA paragraph $it" } }
        rule.waitUntil(5_000) { displayed.value == text.value }
        rule.onNodeWithText("Painted footer").assertExists()
    }

    @Test fun addingAPreviewToUnchangedMarkdownStillFinishesRendering() {
        val text = androidx.compose.runtime.mutableStateOf("First sentence.\n\n")
        var displayed = ""
        rule.setContent { CodexRemoteTheme { RichBody(text.value, onDisplayed = { displayed = it }) } }
        rule.waitUntil(5_000) { displayed == text.value }
        val full = text.value + "```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\"><rect width=\"200\" height=\"100\" fill=\"orange\"/></svg>\n```"
        rule.runOnUiThread { text.value = full }
        rule.waitUntil(5_000) { displayed == full }
        var web: WebView? = null
        var loaded = false
        rule.waitUntil(5_000) { rule.runOnUiThread { web = findWeb(rule.activity.window.decorView); loaded = web?.progress == 100 }; loaded }
    }

    @Test fun burstReplyRevealsGraphemesAndKeepsTypingAfterCompletion() {
        val text = androidx.compose.runtime.mutableStateOf("")
        val active = androidx.compose.runtime.mutableStateOf(true)
        val expected = "先帝创业未半而中道崩殂，今天下三分🙂e\u0301👨‍👩‍👧‍👦".repeat(3)
        var displayed: String? = null
        rule.setContent { CodexRemoteTheme(darkTheme = false) { RichBody(text.value, streaming = active.value, onDisplayed = { displayed = it }) } }
        rule.runOnUiThread { text.value = expected }
        var finished = false
        val lengths = mutableSetOf<Int>()
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                val view = findReveal(rule.activity.window.decorView)
                val partial = view?.text?.toString().orEmpty()
                assertTrue(expected.startsWith(partial))
                if (partial.isNotEmpty() && partial != expected) lengths += partial.length
                finished = lengths.size >= 4
            }
            finished
        }
        rule.runOnUiThread { assertNotEquals(expected, findReveal(rule.activity.window.decorView)!!.text.toString()); assertNull(displayed) }
        rule.runOnUiThread { active.value = false }
        rule.runOnUiThread { assertNotEquals("Completion must keep revealing the buffered tail", expected, findReveal(rule.activity.window.decorView)!!.text.toString()) }
        rule.waitUntil(5_000) { rule.runOnUiThread { finished = findReveal(rule.activity.window.decorView)?.text?.toString() == expected && displayed == expected }; finished }
        rule.runOnUiThread {
            val content = findReveal(rule.activity.window.decorView)!!.text as android.text.Spanned
            val boundaries = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(content.toString()) }
            content.getSpans(0, content.length, com.codex.remote.ui.screens.RevealTextSpan::class.java).forEach {
                assertTrue(boundaries.isBoundary(content.getSpanStart(it)) && boundaries.isBoundary(content.getSpanEnd(it)))
                val paint = android.text.TextPaint()
                it.updateDrawState(paint)
                assertEquals(255, paint.alpha)
            }
        }
    }

    @Test fun completedFadesPaintEveryParagraphTailAsOpaqueAsStaticText() {
        val sample = "## 《木兰诗》\n\n**南北朝·乐府民歌**\n\n实现原理和目标\n\n检查运行状态\n\n在模型选择器里查看"
        val markwon = io.noties.markwon.Markwon.create(rule.activity)
        var view: com.codex.remote.ui.screens.RevealTextView? = null
        var finished = false
        rule.setContent {
            androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                com.codex.remote.ui.screens.RevealTextView(context).apply {
                    view = this
                    setTextIsSelectable(true)
                    setTextColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.WHITE)
                    textSize = 22f
                    setMarkdown(markwon, markwon.toMarkdown(sample), true) { finished = true }
                }
            }, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
        }
        rule.waitUntil(5_000) { finished }
        fun pixels(): IntArray {
            val ready = java.util.concurrent.CountDownLatch(1)
            lateinit var bitmap: Bitmap
            var result = -1
            rule.runOnUiThread {
                val target = checkNotNull(view)
                val location = IntArray(2).also(target::getLocationInWindow)
                bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(rule.activity.window, Rect(location[0], location[1], location[0] + target.width, location[1] + target.height), bitmap,
                    { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle() }
        }
        val animated = pixels()
        finished = false
        rule.runOnUiThread { view!!.setMarkdown(markwon, markwon.toMarkdown(sample), false) { finished = true } }
        rule.waitUntil(5_000) { finished }
        org.junit.Assert.assertArrayEquals("Finished text must match fully opaque static text", pixels(), animated)
    }

    @Test fun streamPausesNeverErasePreviouslyPaintedText() {
        val markwon = io.noties.markwon.Markwon.create(rule.activity)
        lateinit var view: com.codex.remote.ui.screens.RevealTextView
        var source = "既有文字必须稳定"
        var baseline = 0
        fun ink(): Int {
            val bitmap = Bitmap.createBitmap(700, 100, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            view.draw(canvas)
            val pixels = IntArray(700 * 100)
            bitmap.getPixels(pixels, 0, 700, 0, 0, 700, 100)
            bitmap.recycle()
            return pixels.count { android.graphics.Color.red(it) < 100 }
        }
        rule.runOnUiThread {
            // Detached view ensures this check sees every settle frame, including the formerly blank one.
            view = com.codex.remote.ui.screens.RevealTextView(rule.activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(700, 1800)
                setTextIsSelectable(true); setTextColor(android.graphics.Color.BLACK); textSize = 18f
                setMarkdown(markwon, markwon.toMarkdown(source), false) {}
                measure(android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1800, android.view.View.MeasureSpec.EXACTLY))
                layout(0, 0, 700, 1800)
            }
            baseline = ink()
            assertTrue(baseline > 100)
        }
        repeat(6) { index ->
            source += "\n\n新增段落 $index"
            rule.runOnUiThread { view.setMarkdown(markwon, markwon.toMarkdown(source), true) {} }
            repeat(28) {
                rule.runOnUiThread { assertTrue("A stream frame erased existing text", ink() >= baseline) }
                Thread.sleep(16)
            }
        }
    }

    @Test fun liveCloudControlDisplaysRecommendationAboutAndCustomReasoningTemplate() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("cloudAcceptance") == "true")
        com.codex.remote.ui.UiText.context = real
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated); viewModels.put("local", local) }
        rule.waitUntil(20_000) {
            local.state.value.cloud.recommendationsEnabled && local.state.value.cloud.recommendations.any { it.title == "云控联调" } &&
                local.state.value.catalog?.rule("qa-cloud-model", "https://example.com")?.efforts == listOf("vendor-depth")
        }
        assertEquals("https://codeb.baiyun.si/api/client/reasoning", com.codex.remote.data.api.ReasoningCatalogStore.CATALOG_URL)
        rule.setContent { CodexRemoteTheme {
            val state = local.state.collectAsState().value
            androidx.compose.foundation.layout.Column { com.codex.remote.ui.CloudSettings(state, local) }
        } }
        rule.onNodeWithTag("cloud-recommendations").assertExists()
        rule.onNodeWithText("云控联调").assertExists()
        rule.onNodeWithTag("open-about").performClick()
        rule.waitUntil(15_000) { !local.state.value.checkingCloud && local.state.value.cloudCheckedAt > 0 }
        assertNull(local.state.value.cloudError)
        rule.onNodeWithTag("check-updates").assertExists()
        rule.onNodeWithText(real.getString(R.string.cloud_version, BuildConfig.VERSION_NAME)).assertExists()
        rule.onNodeWithText(real.getString(R.string.cloud_no_update)).assertExists()
    }

    @Test fun updateNoticeRemembersLaterAndShowsTheNextVersion() {
        com.codex.remote.ui.UiText.context = real
        val context = object : android.content.ContextWrapper(rule.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = isolated.getSharedPreferences(name, mode)
        }
        val update = androidx.compose.runtime.mutableStateOf(com.codex.remote.data.api.CloudUpdate(true, BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_CODE + 1, "QA update", "QA notes", "https://codeb.baiyun.si/downloads/test.apk"))
        val generation = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent { CodexRemoteTheme {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context) {
                androidx.compose.runtime.key(generation.intValue) { com.codex.remote.ui.CloudUpdateNotice(update.value) }
            }
        } }
        rule.onNodeWithText(real.getString(R.string.cloud_update_available)).assertExists()
        rule.onNodeWithText(real.getString(R.string.cloud_new_version, "QA update")).assertExists()
        rule.onNodeWithText(real.getString(R.string.cloud_later)).performClick()
        rule.onNodeWithText(real.getString(R.string.cloud_update_available)).assertDoesNotExist()
        rule.runOnUiThread { generation.intValue++ }
        rule.onNodeWithText(real.getString(R.string.cloud_update_available)).assertDoesNotExist()
        rule.runOnUiThread { update.value = update.value.copy(versionCode = BuildConfig.VERSION_CODE + 2) }
        rule.onNodeWithText(real.getString(R.string.cloud_update_available)).assertExists()
    }

    @Test fun providerEditorFetchesSeparateIdsThenSavesTestsEditsAndDeletesThem() {
        com.codex.remote.ui.UiText.context = real
        val tested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = object : OpenAiClient() {
            override suspend fun models(provider: ApiProvider, key: String): List<ApiModel> {
                assertEquals("test-only", key)
                return listOf(ApiModel("qa-good", listOf("low", "medium"), true), ApiModel("qa-bad"))
            }
            override suspend fun testModel(provider: ApiProvider, key: String): ApiModelTest {
                tested += provider.selectedModel
                if (provider.selectedModel == "qa-bad") error("HTTP 404")
                return ApiModelTest("passed", "OK")
            }
        }
        val store = ApiStore(isolated)
        val original = ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), models = listOf(ApiModel("qa-custom")), selectedModel = "qa-custom")
        store.saveProviders(listOf(original))
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        var dismissed = false
        rule.setContent { CodexRemoteTheme { com.codex.remote.ui.ProviderEditor(original, local.state.collectAsState().value, local) { dismissed = true } } }
        rule.onNodeWithTag("fetch-provider-models").performScrollTo().performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("provider-model-row-qa-good").assertExists() }.isSuccess }
        assertEquals(original, store.providers().single())
        rule.onNodeWithTag("edit-provider-model-qa-custom").performScrollTo().performClick()
        rule.onNodeWithTag("provider-model-id").performTextReplacement("qa-new")
        rule.onNodeWithTag("confirm-provider-model").performClick()
        rule.onNodeWithText(real.getString(R.string.test_all_models)).performScrollTo().performClick()
        rule.waitUntil(5_000) { local.state.value.providers.single().models.all { it.test != null } }
        assertEquals(listOf("qa-good", "qa-bad", "qa-new"), tested.toList())
        assertEquals("failed", store.providers().single().models.first { it.id == "qa-bad" }.test!!.outcome)
        assertEquals(listOf("low", "medium"), store.providers().single().models.first { it.id == "qa-good" }.efforts)
        rule.onNodeWithTag("edit-provider-model-qa-bad").performScrollTo().performClick()
        rule.onNodeWithTag("delete-edited-model").performClick()
        rule.onNodeWithTag("edit-provider-model-qa-new").performScrollTo().performClick()
        rule.onNodeWithTag("provider-model-id").performTextReplacement("qa-edited")
        rule.onNodeWithTag("confirm-provider-model").performClick()
        rule.onNodeWithText(real.getString(R.string.provider_save_only)).performClick()
        rule.waitUntil(5_000) { dismissed }
        val saved = store.providers().single()
        assertEquals(original.encryptedKey, saved.encryptedKey)
        assertEquals(listOf("qa-good", "qa-edited"), saved.models.map { it.id })
        assertEquals("passed", saved.models.first().test!!.outcome)
        assertNull(saved.models.last().test)
    }

    @Test fun providerListMatchesLevelsPagesAndTestsOneModelWithRefreshFeedback() {
        com.codex.remote.ui.UiText.context = real
        val store = ApiStore(isolated)
        val models = listOf(ApiModel("gpt-6.1-sol")) + (1..22).map { ApiModel("qa-$it") }
        val provider = ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), models = models, selectedModel = "gpt-6.1-sol")
        store.saveProviders(listOf(provider))
        val tested = java.util.concurrent.CopyOnWriteArrayList<String>()
        var fail = false
        val client = object : OpenAiClient() {
            override suspend fun models(provider: ApiProvider, key: String): List<ApiModel> {
                kotlinx.coroutines.delay(100)
                if (fail) error("QA refresh failed")
                return models
            }
            override suspend fun testModel(provider: ApiProvider, key: String): ApiModelTest {
                tested += provider.selectedModel
                return ApiModelTest("passed", "QA test passed")
            }
        }
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() && local.state.value.catalog != null }
        val manager = AppViewModel(isolated, autoRestore = false).also { viewModels.put("manager", it) }
        rule.setContent { CodexRemoteTheme { com.codex.remote.ui.CodexRemoteApp(manager, local) } }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(real.getString(R.string.ui_253)).performClick()
        rule.onNodeWithText(real.getString(R.string.model_edit)).performClick()
        rule.onNodeWithTag("fetch-provider-models").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("provider-model-row-gpt-6.1-sol").assertExists() }.isSuccess }
        rule.runOnUiThread { local.models(provider) }
        rule.waitUntil(5_000) { local.state.value.modelFetchResults["qa"] != null }
        fail = true
        rule.runOnUiThread { local.models(local.state.value.providers.single()) }
        rule.waitUntil(5_000) { local.state.value.modelFetchResults["qa"] == "QA refresh failed" }
        assertEquals(23, store.providers().single().models.size)
        rule.onNodeWithTag("provider-model-search").performScrollTo().performTextReplacement("gpt-6.1")
        val matched = local.state.value.catalog!!.rule("gpt-6.1-sol", provider.baseUrl).efforts
        assertTrue(matched.contains("ultra"))
        rule.onNodeWithTag("edit-provider-model-gpt-6.1-sol").performScrollTo().performClick()
        rule.onNodeWithTag("model-match-auto").assertExists()
        rule.onNodeWithTag("model-match-manual").performClick()
        rule.onNodeWithTag("model-effort-ids").performScrollTo().performTextReplacement("medium\nvendor-deep.v2")
        rule.onNodeWithTag("confirm-provider-model").performClick()
        rule.onNodeWithTag("test-provider-model-gpt-6.1-sol").performScrollTo().performClick()
        rule.waitUntil(5_000) { local.state.value.providers.single().models.first().test != null }
        assertEquals(listOf("gpt-6.1-sol"), tested.toList())
        assertFalse(store.providers().single().models.first().efforts.contains("low"))
        assertEquals(listOf("medium", "vendor-deep.v2"), store.providers().single().models.first().efforts)
        rule.onNodeWithTag("provider-model-search").performScrollTo().performTextReplacement("")
        rule.onNodeWithTag("model-page-next").performScrollTo().performClick()
        rule.onNodeWithTag("provider-model-row-qa-10").assertExists()
        rule.onAllNodesWithTag("provider-model-row-gpt-6.1-sol").assertCountEquals(0)
    }

    @Test fun markdownAndJsonFramesSwitchBetweenSourceAndPreview() {
        com.codex.remote.ui.UiText.context = real
        val source = androidx.compose.runtime.mutableStateOf("```markdown\n# 木兰诗\n\n**南北朝**\n\n木兰当户织。\n```")
        rule.setContent { CodexRemoteTheme { RichBody(source.value) } }
        rule.runOnUiThread { assertTrue(findReveal(rule.activity.window.decorView)!!.text.contains("# 木兰诗")) }
        rule.onNodeWithText(real.getString(R.string.preview_content)).performClick()
        var rendered = false
        rule.waitUntil(5_000) { rule.runOnUiThread { rendered = findReveal(rule.activity.window.decorView)?.text?.toString() == "木兰诗\n\n南北朝\n\n木兰当户织。" }; rendered }
        rule.onNodeWithText(real.getString(R.string.view_source)).performClick()
        rule.waitForIdle()
        rule.runOnUiThread { assertTrue(findReveal(rule.activity.window.decorView)!!.text.contains("**南北朝**")) }
        rule.runOnUiThread { source.value = "```json\n{\"title\":\"木兰诗\",\"lines\":2}\n```" }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("content-source").assertTextContains("{\"title\":\"木兰诗\",\"lines\":2}", substring = true) }.isSuccess }
        rule.onNodeWithText(real.getString(R.string.preview_content)).performClick()
        rule.onNodeWithTag("content-source").assertTextContains("\n    \"title\": \"木兰诗\"", substring = true)
    }

    @Test fun fullConversationKeepsALongHeadingReplyWhenTheNextTurnArrives() {
        com.codex.remote.ui.UiText.context = real
        val store = ApiStore(isolated)
        val provider = ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), selectedModel = "qa-model")
        store.saveProviders(listOf(provider))
        val poemId = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("poemId")
        val actualPoem = if (poemId != null)
            ApiStore(real).conversation(poemId, false)?.messages?.filter { it.role == "assistant" }
                ?.also { check(it.size >= 2 && it.first().text.contains("木兰")) } ?: error("Requested poem cache is unavailable") else null
        val text = actualPoem?.first()?.text ?: ("## 《木兰诗》\n**南北朝·乐府民歌**\n\n" + "唧唧复唧唧，木兰当户织。  \n不闻机杼声，惟闻女叹息。\n\n".repeat(16))
        val reply = LocalMessage(role = "assistant", text = text, completedAt = 123456789L)
        val chat = LocalConversation(title = "木兰诗", providerId = "qa", messages = listOf(LocalMessage(role = "user", text = "木兰诗全文"), reply))
        store.save(chat)
        val client = object : OpenAiClient() {
            override suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?, onDelta: (String) -> Unit) { onDelta(actualPoem?.last()?.text ?: "```markdown\n# 新回复\n\n新正文\n```") }
        }
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        rule.runOnUiThread { local.open(chat) }
        val manager = AppViewModel(isolated, autoRestore = false).also { viewModels.put("manager", it) }
        rule.setContent { CodexRemoteTheme(darkTheme = false) { com.codex.remote.ui.CodexRemoteApp(manager, local) } }
        var ready = false
        rule.waitUntil(5_000) { rule.runOnUiThread { ready = findReveal(rule.activity.window.decorView)?.text?.toString()?.contains("木兰当户织") == true }; ready }
        rule.runOnUiThread { local.draft("给我 Markdown 格式"); local.send() }
        rule.waitUntil(5_000) { !local.state.value.sending }
        repeat(8) {
            if (runCatching { rule.onNodeWithTag("local-prompt-copy-${chat.messages.first().id}").assertIsDisplayed() }.isFailure)
                rule.onNodeWithTag("local-conversation-list").performTouchInput { swipeDown() }
        }
        rule.runOnUiThread {
            val view = checkNotNull(findReveal(rule.activity.window.decorView))
            assertEquals(io.noties.markwon.Markwon.create(real).toMarkdown(text).toString(), view.text.toString())
            assertTrue("height=${view.height}, layout=${view.layout.height}", view.height >= view.layout.height)
        }
    }

    @Test fun streamedPoemKeepsItsFullHeightAfterLongPreviewSwitchesAndScrollingBack() {
        com.codex.remote.ui.UiText.context = real
        val store = ApiStore(isolated)
        store.saveProviders(listOf(ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), selectedModel = "qa-model")))
        val actual = if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("actualPoem") == "true") {
            ApiStore(real).list(false).first { it.title.contains("木兰") && it.messages.count { message -> message.role == "assistant" } >= 2 }
                .messages.first { it.role == "assistant" }.text.also { check(it.length > 100 && it.contains("木兰当户织")) }
        } else null
        val poem = actual ?: ("# 木兰诗\n\n**南北朝·乐府民歌**\n\n" + "唧唧复唧唧，木兰当户织。  \n不闻机杼声，惟闻女叹息。\n\n".repeat(8))
        var requests = 0
        val client = object : OpenAiClient() {
            override suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?, onDelta: (String) -> Unit) {
                onDelta(if (requests++ == 0) poem else "```markdown\n$poem\n```")
                kotlinx.coroutines.delay(250)
            }
        }
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        val manager = AppViewModel(isolated, autoRestore = false).also { viewModels.put("manager", it) }
        rule.setContent { CodexRemoteTheme(darkTheme = false) { com.codex.remote.ui.CodexRemoteApp(manager, local) } }
        rule.runOnUiThread { local.newConversation(); local.draft("木兰诗全文"); local.send() }
        rule.waitUntil(5_000) { !local.state.value.sending && local.state.value.selected!!.messages.size == 2 }
        val firstReply = local.state.value.selected!!.messages.last().id
        val expected = io.noties.markwon.Markwon.create(real).toMarkdown(poem).toString()
        var painted = false
        rule.waitUntil(30_000) { rule.runOnUiThread { painted = findReveal(rule.activity.window.decorView)?.text?.toString() == expected }; painted }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "QA first poem full\n") })
        rule.runOnUiThread { local.draft("给我 Markdown 格式"); local.send() }
        rule.waitUntil(5_000) { !local.state.value.sending && local.state.value.selected!!.messages.size == 4 }
        val secondReply = local.state.value.selected!!.messages.last().id
        rule.waitUntil(30_000) { rule.runOnUiThread { painted = findReveal(rule.activity.window.decorView)?.text?.toString() == poem + "\n" }; painted }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "QA second poem full\n") })
        repeat(3) {
            // An oversized reply cannot fit in the viewport; scroll by gestures to its toolbar.
            repeat(8) {
                if (runCatching { rule.onNodeWithText(real.getString(R.string.preview_content)).assertIsDisplayed() }.isFailure)
                    rule.onNodeWithTag("local-conversation-list").performTouchInput {
                        swipe(androidx.compose.ui.geometry.Offset(center.x, height * .4f), androidx.compose.ui.geometry.Offset(center.x, height * .6f), 600)
                    }
            }
            val toolbarTop = rule.onNodeWithText(real.getString(R.string.preview_content)).fetchSemanticsNode().boundsInRoot.top
            var native: com.codex.remote.ui.screens.RevealTextView? = null
            rule.runOnUiThread { native = findReveal(rule.activity.window.decorView) }
            rule.onNodeWithText(real.getString(R.string.preview_content)).performClick()
            var full = false
            rule.waitUntil(5_000) { rule.runOnUiThread { full = findReveal(rule.activity.window.decorView)?.text?.toString()?.contains("木兰当户织") == true }; full }
            assertEquals(toolbarTop, rule.onNodeWithText(real.getString(R.string.preview_content)).fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.runOnUiThread { assertSame(native, findReveal(rule.activity.window.decorView)) }
            rule.onNodeWithText(real.getString(R.string.view_source)).performClick()
            rule.waitForIdle()
            rule.runOnUiThread { assertEquals(poem + "\n", findReveal(rule.activity.window.decorView)!!.text.toString()) }
        }
        repeat(8) {
            if (runCatching { rule.onNodeWithTag("local-prompt-copy-${local.state.value.selected!!.messages.first().id}").assertIsDisplayed() }.isFailure)
                rule.onNodeWithTag("local-conversation-list").performTouchInput {
                    swipe(androidx.compose.ui.geometry.Offset(center.x, height * .4f), androidx.compose.ui.geometry.Offset(center.x, height * .6f), 600)
                }
        }
        rule.waitForIdle()
        val rowHeight = rule.onNodeWithTag("local-reply-$firstReply").fetchSemanticsNode().size.height
        rule.runOnUiThread {
            val view = checkNotNull(findReveal(rule.activity.window.decorView))
            assertEquals(expected, view.text.toString())
            assertEquals(expected.length, view.layout.getLineEnd(view.layout.lineCount - 1))
            assertTrue("row=$rowHeight, view=${view.height}, layout=${view.layout.height}", rowHeight >= view.layout.height - 1)
            assertTrue(view.height >= view.layout.height)
        }
    }

    @Test fun realUpstreamCatalogueAndBoundedModelProbe() = kotlinx.coroutines.runBlocking {
        Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("realApi") == "true")
        com.codex.remote.ui.UiText.context = real
        val store = ApiStore(real)
        val provider = store.providers().first { java.net.URI(it.baseUrl).host == "api.baiyun.si" }
        val client = OpenAiClient()
        val key = store.key(provider)
        val models = kotlinx.coroutines.withTimeout(30_000) { client.models(provider, key) }
        assertTrue(models.any { it.id == provider.selectedModel })
        val result = kotlinx.coroutines.withTimeout(25_000) { client.testModel(provider, key) }
        assertEquals("Real upstream probe: ${result.detail}", "passed", result.outcome)
        val invalid = provider.copy(selectedModel = "qa-invalid-${UUID.randomUUID()}")
        val error = runCatching { kotlinx.coroutines.withTimeout(25_000) { client.testModel(invalid, key) } }.exceptionOrNull()
        assertNotNull("An invalid model ID must not pass", error)
        assertTrue(error!!.message.orEmpty().contains("HTTP") || error.message == real.getString(R.string.model_probe_failed))
    }

    @Test fun stoppingModelTestsKeepsFinishedResultsAndDoesNotRequestRemainingIds() {
        val store = ApiStore(isolated)
        val provider = ApiProvider(id = "qa", name = "QA", baseUrl = "https://example.com/v1", encryptedKey = store.encryptKey("test-only"), selectedModel = "one", models = listOf("one", "two", "three").map { ApiModel(it) })
        store.saveProviders(listOf(provider))
        val called = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = object : OpenAiClient() {
            override suspend fun testModel(provider: ApiProvider, key: String): ApiModelTest {
                called += provider.selectedModel
                if (provider.selectedModel == "one") return ApiModelTest("passed", "OK")
                awaitCancellation()
            }
        }
        lateinit var local: LocalViewModel
        rule.runOnUiThread { local = LocalViewModel(isolated, client); viewModels.put("local", local) }
        rule.waitUntil(5_000) { local.state.value.providers.isNotEmpty() }
        rule.runOnUiThread { local.testModels(provider) }
        rule.waitUntil(5_000) { local.state.value.testingModels[provider.id] == "two" }
        rule.runOnUiThread { local.stopModelTests(provider.id) }
        rule.waitUntil(5_000) { local.state.value.testingModels.isEmpty() }
        assertEquals(listOf("one", "two"), called.toList())
        val saved = store.providers().single()
        assertEquals("passed", saved.models.first().test!!.outcome)
        assertTrue(saved.models.drop(1).all { it.test == null })
        assertEquals(provider.encryptedKey, saved.encryptedKey)
    }

    private fun findReveal(view: View): com.codex.remote.ui.screens.RevealTextView? {
        if (view is com.codex.remote.ui.screens.RevealTextView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findReveal(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
}
