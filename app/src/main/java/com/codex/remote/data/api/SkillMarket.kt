package com.codex.remote.data.api

import android.content.Context
import com.codex.remote.data.store.LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

@Serializable
data class MarketSkill(
    val id: String, val title: String, val description: String, val category: String,
    val version: String, val requirements: String, val sourceUrl: String, val license: String,
    val content: String, val sha256: String, val published: Boolean = true,
) {
    fun validate() = apply {
        require(id.length <= 63 && id.matches(Regex("codeb-[a-z0-9]+(?:-[a-z0-9]+)*")))
        require(title.length in 1..80 && description.length in 1..600 && category.length in 1..40)
        require(version.length <= 32 && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(requirements.length in 1..1000 && safeCloudLink(sourceUrl))
        require(license in listOf("MIT", "Apache-2.0", "CC0-1.0"))
        require(content.toByteArray().size in 1..65536 && '\u0000' !in content)
        require(content.startsWith("---\nname: $id\ndescription: ") && content.substringAfter("\ndescription: ").matches(Regex("[^\\n]+\\n---\\n[\\s\\S]*")))
        require(sha256 == skillDigest(content)) { "Skill checksum mismatch" }
    }
}

internal fun skillDigest(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

@Serializable
data class SkillCatalog(val schemaVersion: Int = 1, val revision: Int = 1, val items: List<MarketSkill> = emptyList()) {
    fun validate() = apply {
        require(schemaVersion == 1 && revision > 0 && items.size <= 100 && items.distinctBy { it.id }.size == items.size)
        items.forEach { it.validate(); require(it.published) }
    }
}

class SkillMarketStore(context: Context) {
    private val storage = LocalStore(context)
    private val file = File(context.filesDir, "skill-market.json")
    private val json = Json { ignoreUnknownKeys = true }
    fun cached(): SkillCatalog = runCatching { storage.read(file, SkillCatalog.serializer())?.validate() }.getOrNull() ?: SkillCatalog()
    fun refresh(): SkillCatalog {
        val connection = URL("https://codeb.baiyun.si/api/client/skills").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val data = json.decodeFromString<SkillCatalog>(connection.inputStream.use { it.readLimited(786432) }.toString(Charsets.UTF_8)).validate()
            require(data.revision >= cached().revision) { "Older skill catalog" }
            storage.write(file, SkillCatalog.serializer(), data)
            return data
        } finally { connection.disconnect() }
    }
}
