package com.codex.remote.data.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.codex.remote.R
import com.codex.remote.ui.uiText
import com.codex.remote.data.rpc.AppServerTransport
import com.codex.remote.data.store.ApiProtocol
import com.codex.remote.data.store.ApiStore
import com.codex.remote.data.store.validateApiBaseUrl
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val PREFIX = "/data/data/com.termux/files/usr"

internal fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun localProof(hash: ByteArray, nonce: String): String = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(hash, "HmacSHA256")); doFinal(nonce.toByteArray(Charsets.UTF_8)).hex()
}

class TermuxTransport private constructor(
    private val socket: Socket,
    override val reader: BufferedReader,
    override val writer: BufferedWriter,
    override val codexVersion: String,
    val cwd: String,
    val uid: Int,
    val provider: com.codex.remote.data.store.ApiProvider,
    private val releaseBinding: () -> Unit,
) : AppServerTransport {
    override val local = true
    override val remotePlatform = RemotePlatform.POSIX
    override val errorReader = StringReader("").buffered()
    // Close the socket first: a blocked JSONL read must wake before streams are closed.
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.shutdownOutput() }; runCatching { socket.close() }; releaseBinding()
    }

    companion object {
        suspend fun open(context: Context, connection: SavedConnection): TermuxTransport = withContext(Dispatchers.IO) {
            check(ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                uiText(R.string.local_termux_permission, "Allow Codeb to run commands in Termux first.")
            }
            val access = connection.localAccess
            require(access == "standard" || access == "root")
            val store = ApiStore(context)
            val storedProvider = store.providers().firstOrNull { it.id == connection.localProviderId && it.enabled }
                ?: error(uiText(R.string.local_select_provider, "Select a Responses API provider."))
            val preferred=context.getSharedPreferences("phone-work",0).getString("model:${storedProvider.id}",storedProvider.selectedModel)
            val provider=storedProvider.copy(selectedModel=preferred?.takeIf { id->storedProvider.models.any {it.id==id} } ?: storedProvider.selectedModel)
            require(provider.protocol == ApiProtocol.RESPONSES && provider.selectedModel.isNotBlank()) {
                uiText(R.string.local_select_provider, "Select a Responses API provider.")
            }
            val base = validateApiBaseUrl(provider.baseUrl)
            val random = SecureRandom()
            val token = ByteArray(32).also(random::nextBytes).hex()
            val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            val session = ByteArray(16).also(random::nextBytes).hex()
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val options = buildJsonObject {
                put("mode", access); put("session", session); put("port", port); put("tokenHash", hash.hex())
            }
            val runtime = context.assets.open("codeb-runtime.cjs").use { it.readBytes() }
            val revision = MessageDigest.getInstance("SHA-256").digest(runtime).hex().take(16)
            val directory = "$TERMUX_HOME/.local/share/codeb"
            val launcher = "$directory/launcher-$revision.cjs"
            val encoded = Base64.getEncoder().encodeToString(runtime)
            val config = Base64.getEncoder().encodeToString(options.toString().toByteArray(Charsets.UTF_8))
            // No provider key or raw capability token is put in an Intent or command line.
            val command = "umask 077; mkdir -p ${shellQuote(directory)}; " +
                "printf %s ${shellQuote(encoded)} | $PREFIX/bin/base64 -d > ${shellQuote("$launcher.$session")}; " +
                "mv ${shellQuote("$launcher.$session")} ${shellQuote(launcher)}; " +
                "exec $PREFIX/bin/node ${shellQuote(launcher)} serve ${shellQuote(config)} > ${shellQuote("$directory/session-$session.log")} 2>&1"
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            // Native binding keeps the runtime at its client's importance while Codeb uses it.
            // A foreground Termux notification alone does not prevent every OEM's UID freezer.
            val owner = context.applicationContext
            val serviceBinder = java.util.concurrent.atomic.AtomicReference<IBinder>()
            val binding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { serviceBinder.set(service) }
                override fun onServiceDisconnected(name: ComponentName) { serviceBinder.set(null) }
            }
            check(owner.bindService(Intent().setComponent(intent.component), binding, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                uiText(R.string.local_termux_setup, "Install Termux and Node.js, and enable allow-external-apps=true in Termux settings.")
            }
            // ponytail: a native Binder heartbeat thaws OEM-frozen Termux without Root or
            // changing global freezer settings. It lasts only for this runtime connection.
            val heartbeat = CoroutineScope(Dispatchers.IO).launch {
                while (true) { runCatching { serviceBinder.get()?.pingBinder() }; delay(2_000) }
            }
            val releaseBinding = { heartbeat.cancel(); runCatching { owner.unbindService(binding) }; Unit }
            var connected: Socket? = null
            try {
                try { owner.startService(intent) } catch (error: Exception) {
                    throw IllegalStateException(uiText(R.string.local_termux_setup, "Install Termux and Node.js, and enable allow-external-apps=true in Termux settings."), error)
                }
                withTimeout(180_000) {
                    while (connected == null) {
                        currentCoroutineContext().ensureActive()
                        val candidate = Socket()
                        try { candidate.connect(InetSocketAddress("127.0.0.1", port), 500); connected = candidate }
                        catch (_: IOException) { candidate.close(); delay(350) }
                    }
                }
                val socket = connected!!
                socket.soTimeout = 25_000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                fun send(value: JsonObject) { writer.write(value.toString()); writer.newLine(); writer.flush() }
                fun receive(): JsonObject {
                    val line = StringBuilder()
                    while (line.length < 4096) { val ch = reader.read(); check(ch >= 0) { "Local runtime disconnected" }; if (ch == 10) return Json.parseToJsonElement(line.toString()).jsonObject; line.append(ch.toChar()) }
                    error("Invalid local runtime handshake")
                }
                val nonce = ByteArray(32).also(random::nextBytes).hex()
                send(buildJsonObject { put("nonce", nonce) })
                val proof = receive()["proof"]?.jsonPrimitive?.content.orEmpty()
                check(MessageDigest.isEqual(proof.toByteArray(), localProof(hash, nonce).toByteArray())) { "Local runtime identity mismatch" }
                send(buildJsonObject { put("token", token); put("baseUrl", base); put("model", provider.selectedModel); put("apiKey", store.key(provider)) })
                val ready = receive()
                val uid = ready["uid"]?.jsonPrimitive?.int ?: error("Runtime identity missing")
                check((uid == 0) == (access == "root") && ready["ready"]?.jsonPrimitive?.boolean == true) { "Root manager did not grant the requested identity" }
                socket.soTimeout = 0
                TermuxTransport(socket, reader, writer, ready["version"]!!.jsonPrimitive.content, ready["cwd"]!!.jsonPrimitive.content, uid, provider, releaseBinding)
            } catch (error: Throwable) { connected?.close(); releaseBinding(); throw error }
        }
    }
}
