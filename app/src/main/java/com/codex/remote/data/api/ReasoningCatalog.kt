package com.codex.remote.data.api

import android.content.Context
import com.codex.remote.data.store.ApiModel
import com.codex.remote.data.store.ApiProvider
import com.codex.remote.data.store.LocalConversation
import com.codex.remote.data.store.LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@Serializable
data class ReasoningRule(
    val ids: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val providerHost: String? = null,
    val efforts: List<String>,
    val defaultEffort: String = "medium",
    val source: String = "",
)

@Serializable
data class ReasoningCatalog(val schemaVersion: Int, val fallback: ReasoningRule, val rules: List<ReasoningRule>, val revision: Int = 1) {
    fun validate(): ReasoningCatalog = apply {
        require(schemaVersion == 1 && revision > 0 && rules.size <= 500)
        (rules + fallback).forEach { rule ->
            require(rule.efforts.size <= 12 && rule.efforts.distinct() == rule.efforts)
            require(rule.efforts.all(::validEffortId))
            require(rule.efforts.isEmpty() || rule.defaultEffort in rule.efforts)
            require(rule.ids.all { it.isNotBlank() } && rule.prefixes.all { it.isNotBlank() })
        }
    }

    fun rule(model: String, baseUrl: String): ReasoningRule {
        val id = model.substringAfterLast('/').lowercase()
        val host = runCatching { URI(baseUrl).host }.getOrNull()
        val matches = rules.filter { rule -> (rule.providerHost == null || rule.providerHost.equals(host, true)) &&
            (rule.ids.any { it.equals(id, true) } || rule.prefixes.any { id.startsWith(it.lowercase()) }) }
        return matches.maxByOrNull { rule -> (if (rule.providerHost != null) 10_000 else 0) +
            (if (rule.ids.any { it.equals(id, true) }) 1_000 else rule.prefixes.filter { id.startsWith(it.lowercase()) }.maxOfOrNull { it.length } ?: 0) } ?: fallback
    }
}

internal fun ApiProvider.resolved(catalog: ReasoningCatalog, conversation: LocalConversation? = null): ApiProvider {
    val id = conversation?.model?.takeIf(String::isNotBlank) ?: selectedModel
    val effectiveModels = (models + listOfNotNull(id.takeIf(String::isNotBlank)?.let(::ApiModel))).distinctBy { it.id }
        .map { if (it.efforts.isNotEmpty() || it.effortsReported) it else it.copy(efforts = catalog.rule(it.id, baseUrl).efforts) }
    val options = effectiveModels.firstOrNull { it.id == id }?.efforts.orEmpty()
    val preferred = conversation?.effort ?: effort
    val selectedEffort = preferred.takeIf { it in options } ?: "medium".takeIf { it in options }
        ?: catalog.rule(id, baseUrl).defaultEffort.takeIf { it in options } ?: options.firstOrNull().orEmpty()
    return copy(models = effectiveModels, selectedModel = id, effort = selectedEffort)
}

class ReasoningCatalogStore(context: Context) {
    private val storage = LocalStore(context)
    private val file = File(context.filesDir, "model-reasoning-catalog.json")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(text: String) = json.decodeFromString<ReasoningCatalog>(text.removePrefix("\uFEFF")).validate()
    val bundled = context.assets.open("model-reasoning-catalog.json").bufferedReader().use { decode(it.readText()) }
    fun cached(): ReasoningCatalog = runCatching {
        storage.read(file, ReasoningCatalog.serializer())?.validate()?.takeIf { it.revision >= bundled.revision }
    }.getOrNull() ?: bundled

    fun refresh(force: Boolean = false): ReasoningCatalog {
        val cached = cached()
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() in 0..86_400_000) return cached
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            require(connection.responseCode == 200)
            val bytes = connection.inputStream.use { it.readLimited(262_144) }
            require(bytes.size <= 262_144)
            val catalog = decode(bytes.toString(Charsets.UTF_8))
            require(catalog.revision >= cached.revision)
            storage.write(file, ReasoningCatalog.serializer(), catalog)
            return catalog
        } finally { connection.disconnect() }
    }

    companion object {
        const val CATALOG_URL = "https://codeb.baiyun.si/api/client/reasoning"
    }
}

internal fun validEffortId(value: String): Boolean = value.length in 1..64 && value.all { it.code in 33..126 && (it.isLetterOrDigit() || it in "_-.:/") }

internal fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    require(output.size() <= limit) { "File exceeds size limit" }
    return output.toByteArray()
}
