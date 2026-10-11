package com.codex.remote.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.util.Base64
import android.animation.ValueAnimator
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.codex.remote.R
import com.codex.remote.ui.uiText
import com.codex.remote.data.api.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.conflate
import java.io.File
import java.net.URI
import java.util.Locale

internal val LocalArtifactReader = staticCompositionLocalOf<(suspend (String) -> ByteArray)?> { null }
internal val LocalFileManager = staticCompositionLocalOf<((String?) -> Unit)?> { null }

internal data class RichPart(val text: String, val kind: String = "markdown", val label: String = "")

internal fun artifactMime(path: String): String = if (path.startsWith("data:")) path.substringAfter("data:").substringBefore(';') else when (path.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)) {
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "html", "htm" -> "text/html"
    "md", "markdown" -> "text/markdown"
    "txt", "json", "csv", "kt", "java", "js", "ts", "py", "php", "sh", "toml", "xml", "css", "log", "yaml", "yml" -> "text/plain"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

internal fun richParts(text: String): List<RichPart> {
    val trimmed = text.trim()
    if (trimmed.startsWith("<svg", true) && trimmed.endsWith("</svg>", true)) return listOf(RichPart(trimmed, "preview"))
    if ((trimmed.startsWith("<!doctype html", true) || trimmed.startsWith("<html", true)) && trimmed.endsWith("</html>", true)) return listOf(RichPart(trimmed, "preview"))
    // Completed fences only: incomplete code stays visible during streaming.
    val tokens = Regex("(?m)^```([^\\r\\n]*)\\r?\\n([\\s\\S]*?)^```[ \\t]*$|!?\\[([^]\\n]*)]\\((<[^>]+>|[^)\\s]+)(?: \\\"[^\\\"]*\\\")?\\)")
    return buildList<RichPart> {
        var offset = 0
        tokens.findAll(text).forEach { match ->
            if (match.range.first > offset) add(RichPart(text.substring(offset, match.range.first)))
            val code = match.groupValues[2]
            val language = match.groupValues[1].trim().lowercase(Locale.ROOT)
            if (code.isNotEmpty()) {
                val preview = language in setOf("svg", "html", "xml") &&
                    (code.trimStart().startsWith("<svg", true) && code.contains("</svg>", true) || language == "html")
                add(RichPart(code, if (preview) "preview" else "code", language))
            } else {
                val path = match.groupValues[4].removeSurrounding("<", ">")
                val mime = artifactMime(path)
                val image = match.value.startsWith("!")
                val uri = runCatching { URI(path) }.getOrNull()
                val safe = uri != null && (uri.scheme == null || uri.scheme == "https" || uri.scheme == "sandbox" || uri.scheme == "file" || path.startsWith("data:image/") || path.startsWith("data:video/") || path.startsWith("data:audio/"))
                add(if (safe && (image || mime != "application/octet-stream" || uri?.scheme != "https")) RichPart(path, "artifact", match.groupValues[3]) else RichPart(match.value))
            }
            offset = match.range.last + 1
        }
        if (offset < text.length) add(RichPart(text.substring(offset)))
    }.let { parts -> buildList {
        parts.forEach { part ->
            val previous = lastOrNull()
            if (part.kind == "markdown" && previous?.kind == "markdown") {
                removeAt(lastIndex)
                add(previous.copy(text = previous.text + part.text))
            } else add(part)
        }
    } }
}

@Composable
internal fun RichBody(text: String, modifier: Modifier = Modifier, streaming: Boolean = false, onDisplayed: (String) -> Unit = {}) {
    val latest by rememberUpdatedState(text)
    val running by rememberUpdatedState(streaming)
    val notifyDisplayed by rememberUpdatedState(onDisplayed)
    var displayed by remember { mutableStateOf(if (streaming && ValueAnimator.areAnimatorsEnabled()) "" else text) }
    var animateUpdates by remember { mutableStateOf(streaming) }
    LaunchedEffect(Unit) {
        var wasRunning = streaming
        val boundaries = android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT)
        var boundarySource = ""
        var lastRevealFrame = 0L
        snapshotFlow { latest to running }.conflate().collect {
            animateUpdates = running || wasRunning
            // ponytail: one grapheme per frame; Markdown parsing conflates updates on a worker.
            // Very large prose takes proportional reveal time; opening saved replies shows them immediately.
            while (displayed != latest) {
                val target = latest
                if (markdownHasTable(target)) {
                    kotlinx.coroutines.delay(80)
                    displayed = latest
                    break
                }
                if (!animateUpdates || !ValueAnimator.areAnimatorsEnabled() || !target.startsWith(displayed) || richParts(target).all { it.kind == "preview" }) {
                    displayed = target
                    break
                }
                val frame = awaitFrame()
                if (frame - lastRevealFrame < 16_000_000L) continue
                lastRevealFrame = frame
                if (boundarySource != target) { boundaries.setText(target); boundarySource = target }
                val end = boundaries.following(displayed.length)
                if (end == android.icu.text.BreakIterator.DONE) break
                displayed = target.substring(0, end)
            }
            wasRunning = running
        }
    }
    val parts = remember(displayed) { richParts(displayed) }
    val renderedParts = remember { mutableStateMapOf<Int, String>() }
    val currentParts by rememberUpdatedState(parts)
    val ready = parts.withIndex().all { (index, part) -> part.kind !in setOf("markdown", "code") || renderedParts[index] == part.text }
    LaunchedEffect(text, streaming, displayed, ready) {
        if (!streaming && displayed == text && ready) { animateUpdates = false; notifyDisplayed(text) }
    }
    Column(modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            when (part.kind) {
                "preview" -> InlinePreview(part.text)
                "artifact" -> ArtifactCard(part.text, part.label)
                "code" -> CodePreview(part.text, part.label, onDisplayed = { renderedParts[index] = part.text })
                else -> MarkdownBody(part.text, streaming = animateUpdates, onDisplayed = { source ->
                    if (currentParts.getOrNull(index)?.text == source) {
                        renderedParts[index] = source
                        if (!running && displayed == latest && currentParts.withIndex().all { (i, p) -> p.kind !in setOf("markdown", "code") || renderedParts[i] == p.text }) notifyDisplayed(displayed)
                    }
                })
            }
        }
    }
}

@Composable
internal fun LoadingDots(modifier: Modifier = Modifier) {
    val animate = ValueAnimator.areAnimatorsEnabled()
    val phase = if (animate) rememberInfiniteTransition(label = "loading-dots").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "dot-phase") else null
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val label = uiText(R.string.loading_reply, "Loading reply")
    Canvas(modifier.size(36.dp, 20.dp).semantics { contentDescription = label }.testTag("reply-loading-dots")) {
        repeat(3) { index ->
            val wave = if (animate) ((kotlin.math.sin(phase!!.value * 2 * Math.PI - index * 0.8) + 1) / 2).toFloat() else 0.5f
            drawCircle(color.copy(alpha = 0.3f + wave * 0.7f), 2.5.dp.toPx(),
                androidx.compose.ui.geometry.Offset((6 + index * 11).dp.toPx(), size.height / 2 - wave * 3.dp.toPx()))
        }
    }
}

internal class RevealTextSpan(private val start: Long) : CharacterStyle() {
    val endsAt: Long get() = start + 80
    override fun updateDrawState(paint: TextPaint) {
        val fraction = ((SystemClock.uptimeMillis() - start) / 80f).coerceIn(0f, 1f)
        paint.alpha = (paint.alpha * fraction * fraction * (3 - 2 * fraction)).toInt()
    }
}

internal class RevealTextView(context: android.content.Context) : android.widget.TextView(context) {
    private var fadeUntil = 0L
    private var renderedContent: Spanned? = null
    private var onNextDraw: (() -> Unit)? = null
    private var settleText = false

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        // Let the conversation take vertical drags; native long-press selection still takes ownership.
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }

    fun setMarkdown(markwon: io.noties.markwon.Markwon, content: Spanned, animate: Boolean, onDisplayed: () -> Unit) {
        onNextDraw = onDisplayed
        if (renderedContent === content) { invalidate(); return }
        renderedContent = content
        val next = content.toString()
        val previous = text.toString()
        val now = SystemClock.uptimeMillis()
        val styled = SpannableStringBuilder(content)
        if (animate && next.startsWith(previous) && ValueAnimator.areAnimatorsEnabled()) {
            settleText = true
            // Keep unfinished fades when a faster delta arrives, so completion never flashes the tail.
            (text as? Spanned)?.getSpans(0, previous.length, RevealTextSpan::class.java)?.forEach { span ->
                if (span.endsAt > now) styled.setSpan(span, (text as Spanned).getSpanStart(span), (text as Spanned).getSpanEnd(span), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val boundaries = android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(next) }
            var start = if (boundaries.isBoundary(previous.length)) previous.length else boundaries.preceding(previous.length)
            var time = maxOf(now, fadeUntil - 80)
            while (start < next.length) {
                val end = boundaries.following(start)
                if (end == android.icu.text.BreakIterator.DONE) break
                styled.setSpan(RevealTextSpan(time), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = end
                time += 16
            }
            fadeUntil = maxOf(fadeUntil, time + 80)
        } else {
            fadeUntil = 0
            settleText = false
        }
        markwon.setParsedMarkdown(this, styled)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val now = SystemClock.uptimeMillis()
        if (settleText && now >= fadeUntil) {
            settleText = false
            // Rebuild selectable text's layout/cache once the fade ends, including heading metrics.
            // Publish completion only after this full, static layout has actually drawn.
            setText(SpannableStringBuilder(renderedContent), android.widget.TextView.BufferType.SPANNABLE)
            requestLayout()
            invalidate()
            // Draw this frame too: returning here blanks existing paragraphs at every stream pause.
        }
        (text as? Spannable)?.let { content ->
            content.getSpans(0, content.length, RevealTextSpan::class.java).forEach { span ->
                // Native selectable text caches each line's drawing. Removing finished fades refreshes it.
                if (span.endsAt <= now) content.removeSpan(span)
            }
        }
        super.onDraw(canvas)
        if (now < fadeUntil) postInvalidateOnAnimation()
        else onNextDraw?.let { callback -> onNextDraw = null; post { callback() } }
    }
}

internal fun previewDocument(source: String, height: Int = 300): String {
    val style = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0}svg,img,video{max-width:100%;height:auto}audio{width:100%}</style>"
    val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(source)
    val responsive = if (head != null) source.replaceRange(head.range.last + 1, head.range.last + 1, style) else style + source
    val escaped = responsive.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob: https:; media-src data: blob: https:; frame-src 'self'; connect-src 'none'; form-action 'none'; base-uri 'none'"><style>html,body{margin:0;background:transparent;font:16px sans-serif}iframe{display:block;border:0;width:100%;height:${height}px}svg,img,video{max-width:100%;height:auto}audio{width:100%}</style></head><body><iframe sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="$escaped"></iframe></body></html>"""
}

@Composable
internal fun InlinePreview(source: String) {
    CodePreview(source, if (source.trimStart().startsWith("<svg", true)) "svg" else "html", initialPreview = true)
}

@Composable
internal fun CodePreview(source: String, language: String, initialPreview: Boolean = false, onDisplayed: () -> Unit = {}) {
    val kind = language.lowercase(Locale.ROOT)
    val canPreview = kind in setOf("md", "markdown", "html", "svg", "json")
    var preview by remember(source, kind) { mutableStateOf(initialPreview) }
    val context = LocalContext.current
    val formatted = remember(source, kind) { if (kind == "json") runCatching {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), json.parseToJsonElement(source))
    }.getOrElse { source } else source }
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("content-preview-frame"), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { uiText(R.string.content_plaintext, "Text") }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (canPreview) {
                    TextButton(onClick = { preview = false }) { Text(uiText(R.string.view_source, "View source")) }
                    TextButton(onClick = { preview = true }) { Text(uiText(R.string.preview_content, "Preview")) }
                }
                IconButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("source", source)) }) {
                    Icon(Icons.Outlined.ContentCopy, uiText(R.string.copy_reply, "Copy reply"), Modifier.size(18.dp))
                }
            }
            if (kind in setOf("md", "markdown")) MarkdownBody(source,
                modifier = if (preview) Modifier else Modifier.testTag("content-source"), plainText = !preview, onDisplayed = { onDisplayed() })
            else if (preview && kind in setOf("html", "svg")) WebPreview(source)
            else {
                SelectionContainer { Text(if (preview) formatted else source, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("content-source")) }
                SideEffect { onDisplayed() }
            }
        }
    }
}

@Composable
private fun WebPreview(source: String) {
    val aspect = remember(source) {
        Regex("<svg\\b[^>]*\\bviewBox\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)
            ?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == 4 && it.all(Float::isFinite) && it[2] > 0 && it[3] > 0 }?.let { it[2] / it[3] }
    }
    Column(Modifier.fillMaxWidth().testTag("artifact-preview")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = aspect?.let { (maxWidth / it).coerceIn(100.dp, 600.dp) } ?: 300.dp
            val html = remember(source, height) { previewDocument(source, height.value.toInt()) }
            AndroidView(factory = { context -> WebView(context).apply {
                settings.javaScriptEnabled = true // Isolated iframe; no native bridge or origin privileges.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val scheme = request?.url?.scheme
                        return if (scheme in setOf("https", "data", "blob", "about")) null else WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                    }
                }
            } }, update = { view ->
                if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("https://preview.invalid/", html, "text/html", "UTF-8", null) }
            }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxWidth().height(height))
        }
    }
}

@Composable
private fun ArtifactCard(path: String, label: String) {
    val context = LocalContext.current
    val reader = LocalArtifactReader.current
    val fileManager=LocalFileManager.current
    val scope = rememberCoroutineScope()
    val mime = remember(path) { artifactMime(path) }
    val remoteUrl = runCatching { URI(path) }.getOrNull()?.scheme == "https"
    var load by remember(path) { mutableStateOf(mime.startsWith("image/") && !remoteUrl) }
    var bytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    val filename = remember(path, mime) {
        if (path.startsWith("data:")) "attachment.${mime.substringAfterLast('/').substringBefore('+')}"
        else runCatching { URI(path).path?.substringAfterLast('/') }.getOrNull()?.take(128)?.takeIf(String::isNotBlank) ?: "attachment"
    }
    val name = label.ifBlank { filename }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { target ->
        val content = bytes
        if (target != null && content != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(target)?.use { it.write(content) } ?: error("Cannot save file") } }
            catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
        }
    }
    LaunchedEffect(path, load) {
        if (load) try {
            if (remoteUrl) {
                bytes = withContext(Dispatchers.IO) {
                    val connection = (java.net.URL(path).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
                    }
                    try {
                        check(connection.responseCode in 200..299) { "Download failed: HTTP ${connection.responseCode}" }
                        require(connection.contentLengthLong <= 64L * 1024 * 1024) { "Preview files must be at most 64 MB" }
                        connection.inputStream.use { it.readLimited(64 * 1024 * 1024) }
                            .also { require(it.size <= 64 * 1024 * 1024) { "Preview files must be at most 64 MB" } }
                    } finally { connection.disconnect() }
                }
            } else if (path.startsWith("data:")) {
                require(path.length <= 90 * 1024 * 1024 && path.substringBefore(',').endsWith(";base64"))
                bytes = Base64.decode(path.substringAfter(','), Base64.DEFAULT)
            } else {
                val localPath = if (path.startsWith("sandbox:") || path.startsWith("file:")) URI(path).path else path
                bytes = (reader ?: error("No file connection available"))(localPath)
            }
        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("artifact-card")) {
        val inlineRemote = remoteUrl && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))
        if (inlineRemote) MediaPreview(path, mime)
        else {
            TextButton(onClick = {
                if(fileManager!=null&&!remoteUrl&&!path.startsWith("data:")) fileManager(if(path.startsWith("sandbox:")||path.startsWith("file:"))URI(path).path else path)
                else {load=true;error=null}
            }) { Text(name) }
            if (load && bytes == null && error == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            bytes?.let { content ->
                when {
                    mime == "text/html" || mime == "image/svg+xml" -> InlinePreview(content.toString(Charsets.UTF_8))
                    mime == "text/markdown" -> CodePreview(content.toString(Charsets.UTF_8), "markdown")
                    mime.startsWith("text/") -> CodePreview(content.toString(Charsets.UTF_8), filename.substringAfterLast('.', "text"))
                    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") -> MediaPreview("data:$mime;base64," + Base64.encodeToString(content, Base64.NO_WRAP), mime)
                    mime == "application/pdf" -> PdfPreview(content)
                }
                TextButton(onClick = { exporter.launch(filename) }) { Text(uiText(R.string.save_attachment, "Save attachment")) }
            }
            if (remoteUrl) TextButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(path))) }.onFailure { error = it.message } }) { Text(uiText(R.string.open_attachment, "Open attachment")) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MediaPreview(url: String, mime: String) {
    val escaped = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    val tag = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else "img"
    InlinePreview("<style>body{margin:0}img,video{max-width:100%;max-height:290px}audio{width:100%}</style><$tag src=\"$escaped\" controls playsinline referrerpolicy=\"no-referrer\" ${if (tag == "img") "alt=\"Image\"" else ""}>")
}

@Composable
internal fun FileContentPreview(name: String, bytes: ByteArray, text: String?) {
    val mime=artifactMime(name)
    when {
        text!=null -> CodePreview(text, if(mime=="text/markdown") "markdown" else name.substringAfterLast('.',"text"),initialPreview=mime=="text/markdown")
        mime=="application/pdf" -> PdfPreview(bytes)
        mime.startsWith("image/") -> MediaPreview("data:$mime;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP),mime)
        else -> Text(uiText(R.string.file_binary,"Binary file; export to open in another app."))
    }
}

@Composable
private fun PdfPreview(bytes: ByteArray) {
    val context = LocalContext.current
    var page by remember(bytes) { mutableIntStateOf(0) }
    var count by remember(bytes) { mutableIntStateOf(0) }
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bytes, page) {
        val rendered = withContext(Dispatchers.IO) {
            val file = File.createTempFile("preview-", ".pdf", context.cacheDir)
            try {
                file.writeBytes(bytes)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(page).use { sheet ->
                        Bitmap.createBitmap(1080, (1080L * sheet.height / sheet.width).toInt().coerceIn(1, 4096), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            sheet.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } to renderer.pageCount
                    }
                } }
            } catch (_: Exception) { null to 0 } finally { file.delete() }
        }
        bitmap = rendered.first
        count = rendered.second
    }
    bitmap?.let { Image(it.asImageBitmap(), "PDF ${page + 1}/$count", Modifier.fillMaxWidth()) }
    Row {
        TextButton(onClick = { page-- }, enabled = page > 0) { Text("‹") }
        Text("${page + 1}/$count")
        TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text("›") }
    }
}
