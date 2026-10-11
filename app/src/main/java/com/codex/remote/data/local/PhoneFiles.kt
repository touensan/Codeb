package com.codex.remote.data.local

import android.content.Context
import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.api.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class PhoneFile(val name: String, val path: String, val directory: Boolean, val link: Boolean = false,
    val size: Long = 0, val modified: Long = 0, val mode: String = "")

internal fun fileRevision(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun fileChild(parent: String, name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray().size <= 240 && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid file name" }
    return parent.trimEnd('/') + "/" + name
}

class PhoneFiles(private val context: Context, val root: String, private val client: CodexRpcClient? = null) {
    private val script by lazy { context.assets.open("codeb-files.cjs").bufferedReader().use { it.readText() } }
    private fun disk(path: String): File {
        val file = File(path)
        val base = File(root).canonicalPath
        require(file.canonicalPath == base || file.canonicalPath.startsWith("$base/")) { "Outside this file workspace" }
        return file
    }
    private fun record(file: File) = PhoneFile(file.name, file.path, file.isDirectory,
        Files.isSymbolicLink(file.toPath()), file.length(), file.lastModified())
    private suspend fun remote(action: String, path: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        check(client != null)
        val payload = JsonObject(mapOf("action" to JsonPrimitive(action), "path" to JsonPrimitive(path)) + extra)
        val result = client.request("command/exec", buildJsonObject {
            put("command", buildJsonArray { add("/data/data/com.termux/files/usr/bin/node"); add("-e"); add(script); add(payload.toString()) })
            put("cwd", root); put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
            put("outputBytesCap", 2 * 1024 * 1024)
        })
        check(result["exitCode"]?.jsonPrimitive?.int == 0) {
            result["stderr"]?.jsonPrimitive?.content?.lineSequence()?.firstOrNull { it.startsWith("Error:") } ?: "File operation failed"
        }
        return Json.parseToJsonElement(result["stdout"]!!.jsonPrimitive.content).jsonObject
    }
    suspend fun list(path: String): List<PhoneFile> = withContext(Dispatchers.IO) {
        client?.sshFiles?.let {return@withContext it.listFiles(path).sortedWith(compareBy<PhoneFile> {file -> !file.directory}.thenBy {file -> file.name.lowercase()})}
        val entries = if (client == null) disk(path).listFiles()?.also { require(it.size <= 5000) { "Directory exceeds 5000 entries" } }?.map(::record)
            ?: error("Cannot read directory") else remote("list", path)["entries"]!!.jsonArray.map { value ->
            val e=value.jsonObject
            PhoneFile(e["name"]!!.jsonPrimitive.content,e["path"]!!.jsonPrimitive.content,e["directory"]!!.jsonPrimitive.boolean,
                e["link"]!!.jsonPrimitive.boolean,e["size"]!!.jsonPrimitive.long,e["modified"]!!.jsonPrimitive.double.toLong(),e["mode"]!!.jsonPrimitive.content)
        }
        entries.sortedWith(compareBy<PhoneFile> { !it.directory }.thenBy { it.name.lowercase() })
    }
    suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        if (client != null) client.readArtifact(path, "/").also {require(it.size<=18*1024*1024) {"Preview is limited to 18 MB"}} else {
            val file=disk(path); require(file.isFile && file.length() <= 18 * 1024 * 1024) { "Preview is limited to 18 MB" }
            file.inputStream().use { it.readLimited(18 * 1024 * 1024) }.also { require(it.size <= 18 * 1024 * 1024) }
        }
    }
    suspend fun save(path: String, bytes: ByteArray, revision: String?) = withContext(Dispatchers.IO) {
        require(bytes.size <= (if(revision==null)18 else 1) * 1024 * 1024) { "New files are limited to 18 MB; text editing to 1 MB" }
        client?.sshFiles?.let {it.changeFile("save",path,bytes,revision);return@withContext}
        if (client != null) {
            val staging = fileChild(File(path).parent!!, ".codeb-${UUID.randomUUID()}.tmp")
            try {
                client.request("fs/writeFile", buildJsonObject { put("path", staging); put("dataBase64", Base64.getEncoder().encodeToString(bytes)) })
                remote("commit", path, mapOf("staging" to JsonPrimitive(staging), "revision" to (revision?.let(::JsonPrimitive) ?: JsonNull)))
            } finally { runCatching { remote("delete", staging) } }
        } else {
            val file=disk(path)
            require(!Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.isFile)) { "Not an editable regular file" }
            check(if (revision == null) !file.exists() else file.exists() && fileRevision(read(path)) == revision) { "File changed elsewhere; reopen before saving" }
            val staging=File.createTempFile(".codeb-", ".tmp", file.parentFile)
            try {
                staging.outputStream().use { it.write(bytes); it.fd.sync() }
                if (revision == null) Files.move(staging.toPath(), file.toPath())
                else Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { staging.delete() }
        }
    }
    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) { if(client?.sshFiles!=null)client.sshFiles!!.changeFile("mkdir",path) else if (client != null) remote("mkdir",path) else check(disk(path).mkdir()) { "Folder exists or cannot be created" }; Unit }
    suspend fun rename(path: String, name: String) = withContext(Dispatchers.IO) {
        val destination=fileChild(File(path).parent!!,name)
        client?.sshFiles?.let {it.changeFile("rename",path,destination=destination);return@withContext}
        if(client!=null)remote("rename",path,mapOf("destination" to JsonPrimitive(destination)))
        else { require(!Files.isSymbolicLink(disk(path).toPath())); Files.move(disk(path).toPath(),disk(destination).toPath()) }; Unit
    }
    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        require(path.trimEnd('/') != root.trimEnd('/') && path != "/") { "Cannot remove workspace root" }
        client?.sshFiles?.let {it.changeFile("delete",path);return@withContext}
        if(client!=null)remote("delete",path) else Files.delete(disk(path).toPath()); Unit
    }
}
