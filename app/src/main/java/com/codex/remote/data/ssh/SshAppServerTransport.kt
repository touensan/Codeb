package com.codex.remote.data.ssh

import android.content.Context
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionSecrets
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class HostKeyChangedException(
    expected: String,
    actual: String,
) : SecurityException("SSH 主机密钥已变更。已保存 $expected，当前为 $actual")

class UnknownHostKeyException(val fingerprint: String) :
    SecurityException("首次连接需要确认 SSH 主机指纹：$fingerprint")

class RemoteCodexUnavailableException(message: String) : IllegalStateException(message)

class ActiveSshTransport internal constructor(
    private val ssh: SSHClient,
    private val session: Session,
    private val command: Session.Command,
    val fingerprint: String,
    override val remotePlatform: RemotePlatform,
    override val codexVersion: String,
) : com.codex.remote.data.rpc.AppServerTransport {
    private val input = command.inputStream.buffered()
    private val protocol = buildString {
        while(length < 64) { val c=input.read(); check(c>=0) { "Codex connection closed before setup" }; if(c==10)break; append(c.toChar()) }
    }.trim()
    private val shared = when(protocol) {
        "CODEB_WS_V1" -> SharedCodexSocket(input,command.outputStream)
        "CODEB_JSONL_V1" -> null
        else -> error("Unexpected Codex transport response: $protocol")
    }
    override val reader: BufferedReader = shared?.reader ?: BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    override val writer: BufferedWriter = shared?.writer ?: BufferedWriter(OutputStreamWriter(command.outputStream, Charsets.UTF_8))
    override val errorReader: BufferedReader = BufferedReader(InputStreamReader(command.errorStream, Charsets.UTF_8))

    suspend fun listFiles(path: String) = withContext(Dispatchers.IO) {
        ssh.newSFTPClient().use { sftp ->
            sftp.sftpEngine.timeoutMs=20_000
            sftp.ls(path).also {require(it.size<=5000)}.filter {it.name!="."&&it.name!=".."}.map { item ->
                com.codex.remote.data.local.PhoneFile(item.name,item.path,item.isDirectory,
                    item.attributes.type==net.schmizz.sshj.sftp.FileMode.Type.SYMLINK,item.attributes.size,item.attributes.mtime*1000,item.attributes.mode.toString())
            }
        }
    }

    suspend fun changeFile(action:String,path:String,bytes:ByteArray=byteArrayOf(),revision:String?=null,destination:String="") = withContext(Dispatchers.IO) {
        require(path.isNotBlank()&&path.none {it.isISOControl()})
        ssh.newSFTPClient().use { sftp ->
            sftp.sftpEngine.timeoutMs=20_000
            when(action) {
                "mkdir" -> sftp.mkdir(path)
                "rename" -> sftp.rename(path,destination)
                "delete" -> if(sftp.lstat(path).type==net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)sftp.rmdir(path) else sftp.rm(path)
                "save" -> {
                    val old=sftp.statExistence(path)
                    require(old==null || sftp.lstat(path).type==net.schmizz.sshj.sftp.FileMode.Type.REGULAR) {"Not an editable regular file"}
                    check(if(revision==null)old==null else old!=null&&com.codex.remote.data.local.fileRevision(readArtifact(path,"/"))==revision) {"File changed elsewhere; reopen before saving"}
                    val stage=path+".codeb-"+java.util.UUID.randomUUID()+".tmp"
                    try {
                        sftp.open(stage,setOf(net.schmizz.sshj.sftp.OpenMode.WRITE,net.schmizz.sshj.sftp.OpenMode.CREAT,net.schmizz.sshj.sftp.OpenMode.EXCL)).use { file ->
                            var offset=0;while(offset<bytes.size){val size=minOf(32768,bytes.size-offset);file.write(offset.toLong(),bytes,offset,size);offset+=size}
                        }
                        sftp.chmod(stage,old?.mode?.permissionsMask ?: 384)
                        if(old!=null) {sftp.chown(stage,old.uid);sftp.chgrp(stage,old.gid)}
                        if(revision==null)sftp.rename(stage,path)
                        else {
                            check(com.codex.remote.data.local.fileRevision(readArtifact(path,"/"))==revision) {"File changed elsewhere; reopen before saving"}
                            sftp.rename(stage,path,setOf(net.schmizz.sshj.sftp.RenameFlags.OVERWRITE,net.schmizz.sshj.sftp.RenameFlags.ATOMIC))
                        }
                    } finally {runCatching {sftp.rm(stage)}}
                }
            }
        }
    }

    suspend fun readArtifact(path: String, root: String): ByteArray = withContext(Dispatchers.IO) {
        require(root.isNotBlank() && path.isNotBlank() && '\u0000' !in path)
        ssh.newSFTPClient().use { sftp ->
            sftp.sftpEngine.timeoutMs = 20_000
            val base = sftp.canonicalize(root).trimEnd('/')
            val absolute = sftp.canonicalize(if (path.startsWith('/')) path else "$base/$path")
            require(absolute.startsWith("$base/")) { "File must be inside the conversation workspace" }
            // ponytail: previews are capped at 64 MB; larger videos need ranged SFTP streaming.
            val size = sftp.stat(absolute).size
            require(size in 0..64L * 1024 * 1024) { "Preview files must be at most 64 MB" }
            sftp.open(absolute).use { file ->
                val bytes = ByteArray(size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = file.read(offset.toLong(), bytes, offset, minOf(32768, bytes.size - offset))
                    check(count > 0) { "File changed while loading; try again" }
                    offset += count
                }
                bytes
            }
        }
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { command.close() }
        runCatching { session.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }
}

class SshAppServerTransportFactory(private val context: Context) {
    suspend fun open(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
    ): ActiveSshTransport = withContext(Dispatchers.IO) {
        var observedFingerprint = ""
        val ssh = authenticatedClient(connection, secrets) { observedFingerprint = it }
        try {
            val remotePlatform = resolvePlatform(ssh, connection.platform)
            val codexVersion = readCodexVersion(ssh, remotePlatform)
            ssh.timeout = 30_000
            ssh.connection.keepAlive.keepAliveInterval = 15
            val session = ssh.startSession()
            try {
                val command = session.exec(appServerCommand(remotePlatform))
                ActiveSshTransport(
                    ssh = ssh,
                    session = session,
                    command = command,
                    fingerprint = observedFingerprint,
                    remotePlatform = remotePlatform,
                    codexVersion = codexVersion,
                ).also {ssh.timeout=0}
            } catch (error: Throwable) {
                runCatching { session.close() }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            throw error
        }
    }

    private fun authenticatedClient(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
        onFingerprint: (String) -> Unit,
    ): SSHClient {
        val ssh = SSHClient(androidCompatibleSshConfig())
        var unknownFingerprint: String? = null
        var changedFingerprint: String? = null
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val actual = sha256Fingerprint(key)
                onFingerprint(actual)
                val expected = connection.hostKeyFingerprint
                if (expected.isBlank()) {
                    unknownFingerprint = actual
                    return false
                }
                if (expected != actual) {
                    changedFingerprint = actual
                    return false
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        try {
            ssh.connect(connection.host, connection.port)
            when (connection.authType) {
                AuthType.PASSWORD -> ssh.authPassword(connection.username, secrets.password)
                AuthType.PRIVATE_KEY -> authenticatePrivateKey(ssh, connection.username, secrets)
            }
            return ssh
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            unknownFingerprint?.let { throw UnknownHostKeyException(it) }
            changedFingerprint?.let { throw HostKeyChangedException(connection.hostKeyFingerprint, it) }
            throw error
        }
    }

    private fun authenticatePrivateKey(
        ssh: SSHClient,
        username: String,
        secrets: ConnectionSecrets,
    ) {
        val keyFile = File.createTempFile("codex_remote_", ".key", context.cacheDir)
        try {
            keyFile.writeText(secrets.privateKey, Charsets.UTF_8)
            val provider = if (secrets.passphrase.isBlank()) {
                ssh.loadKeys(keyFile.absolutePath)
            } else {
                ssh.loadKeys(keyFile.absolutePath, secrets.passphrase.toCharArray())
            }
            ssh.authPublickey(username, provider)
        } finally {
            keyFile.writeText("")
            keyFile.delete()
        }
    }

    private fun sha256Fingerprint(key: PublicKey): String {
        val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun resolvePlatform(ssh: SSHClient, configured: RemotePlatform): RemotePlatform {
        if (configured != RemotePlatform.AUTO) return configured
        val probe = runCommand(ssh, "printf '__CODEX_POSIX__'")
        return if (probe.exitStatus == 0 && probe.stdout.contains("__CODEX_POSIX__")) {
            RemotePlatform.POSIX
        } else {
            RemotePlatform.WINDOWS
        }
    }

    private fun readCodexVersion(ssh: SSHClient, platform: RemotePlatform): String {
        val probe = runCommand(ssh, codexVersionCommand(platform))
        val version = probe.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("codex-cli ") || it.startsWith("codex ") }
        if (probe.exitStatus != 0 || version == null) {
            val detail = probe.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                ?: probe.stdout.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "codex --version 未返回版本"
            throw RemoteCodexUnavailableException(
                "远端登录 shell 找不到可用的 Codex CLI。请先在远端运行 codex --version 并完成安装。$detail",
            )
        }
        return version.substringAfter(' ').trim()
    }

    private fun runCommand(ssh: SSHClient, commandLine: String): ProbeResult {
        val session = ssh.startSession()
        return try {
            val command = session.exec(commandLine)
            try {
                command.join(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (command.isOpen) {
                    throw RemoteCodexUnavailableException("远端 Codex 预检超时")
                }
                ProbeResult(
                    exitStatus = command.exitStatus ?: -1,
                    stdout = command.inputStream.bufferedReader(Charsets.UTF_8).readText(),
                    stderr = command.errorStream.bufferedReader(Charsets.UTF_8).readText(),
                )
            } finally {
                runCatching { command.close() }
            }
        } finally {
            runCatching { session.close() }
        }
    }

    private data class ProbeResult(
        val exitStatus: Int,
        val stdout: String,
        val stderr: String,
    )

    companion object {
        private const val PROBE_TIMEOUT_SECONDS = 15L
    }
}

internal fun codexVersionCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex --version }\""
}

internal fun appServerCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX ->
        "exec \"\${SHELL:-/bin/sh}\" -lc 'if codex app-server proxy --help >/dev/null 2>&1; then codex app-server daemon start >&2 && { echo CODEB_WS_V1; exec codex app-server proxy; }; else echo CODEB_JSONL_V1; exec codex app-server --listen stdio://; fi'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server proxy --help *>\$null; if (\$LASTEXITCODE -eq 0) { codex app-server daemon start 1>&2; if (\$LASTEXITCODE -eq 0) { Write-Output CODEB_WS_V1; codex app-server proxy } } else { Write-Output CODEB_JSONL_V1; codex app-server --listen stdio:// } }\""
}

internal fun androidCompatibleSshConfig() = DefaultSecurityProviderConfig().apply {
    keyExchangeFactories = keyExchangeFactories.filterNot {
        it.name.contains("curve25519", ignoreCase = true)
    }
}
