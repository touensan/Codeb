package com.codex.remote.data.diagnostics

import android.content.Context
import com.codex.remote.BuildConfig
import com.codex.remote.data.store.LocalStore
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

object DebugApi {
    const val PORT = 18765
    @Volatile private var server: ServerSocket? = null
    @Volatile var state: () -> JsonObject = { buildJsonObject { put("ready", true) } }
    private var context: Context? = null
    private var logFile: File? = null
    var token = ""
        private set

    fun attach(application: Context) {
        context = application.applicationContext
        logFile = File(LocalStore(application).cacheRoot, "diagnostics.jsonl")
        if (BuildConfig.DEBUG && application.getSharedPreferences("diagnostics", 0).getBoolean("enabled", false)) enable(true)
    }
    fun enabled(): Boolean = server != null

    @Synchronized
    fun enable(value: Boolean) {
        require(BuildConfig.DEBUG) { "Diagnostics API is unavailable in release builds" }
        val application = context ?: return
        server?.close()
        server = null
        application.getSharedPreferences("diagnostics", 0).edit().putBoolean("enabled", value).apply()
        if (!value) {
            token = ""
            File(application.filesDir, "debug-api.token").delete()
            return
        }
        token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        LocalStore(application).atomicWrite(File(application.filesDir, "debug-api.token"), token.toByteArray())
        val listener = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        server = listener
        record("debug_api_started")
        thread(name = "codex-diagnostics", isDaemon = true) {
            while (!listener.isClosed) try {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte < 0) break
                        require(header.length < 8_192) { "Header too large" }
                        header.append(byte.toChar())
                    }
                    val lines = header.toString().split("\r\n")
                    val request = lines.firstOrNull().orEmpty().split(' ')
                    val auth = lines.drop(1).firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim().orEmpty()
                    val authorized = MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())
                    val (status, body) = when {
                        !authorized -> 401 to "{\"error\":\"unauthorized\"}"
                        request.firstOrNull() != "GET" -> 405 to "{\"error\":\"read_only_api\"}"
                        request.getOrNull(1) == "/health" -> 200 to "{\"ok\":true,\"schemaVersion\":1}"
                        request.getOrNull(1) == "/state" -> 200 to state().toString()
                        request.getOrNull(1) == "/logs" -> 200 to logs().toString()
                        else -> 404 to "{\"error\":\"not_found\"}"
                    }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (_: Exception) { /* An invalid local request must not stop the listener. */ }
        }
    }

    @Synchronized
    fun record(code: String, error: Throwable? = null) {
        val file = logFile ?: return
        val entry = buildJsonObject {
            put("time", System.currentTimeMillis())
            put("event", code)
            if (error != null) {
                put("exception", error.javaClass.name)
                put("stack", buildJsonArray { error.stackTrace.take(12).forEach { add(it.toString()) } })
            }
        }
        try { file.parentFile!!.mkdirs(); file.appendText("$entry\n") }
        catch (_: Exception) { /* Diagnostics cannot turn disk exhaustion into an app crash. */ }
    }

    @Synchronized
    private fun logs(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("events", buildJsonArray {
            val file = logFile?.takeIf { it.exists() } ?: return@buildJsonArray
            RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - 65_536).coerceAtLeast(0)
                input.seek(start)
                val bytes = ByteArray((input.length() - start).toInt())
                input.readFully(bytes)
                bytes.toString(Charsets.UTF_8).lineSequence().drop(if (start > 0) 1 else 0)
                    .filter { it.isNotBlank() }.forEach { runCatching { Json.parseToJsonElement(it) }.getOrNull()?.let(::add) }
            }
        })
    }
}
