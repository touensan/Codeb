package com.codex.remote.data.api

import android.content.Context
import com.codex.remote.data.store.LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@Serializable
data class CloudAbout(val name: String = "白云Codeb", val description: String = "", val website: String = "https://codeb.baiyun.si")
@Serializable
data class CloudUpdate(val enabled: Boolean = false, val applicationId: String = "", val versionCode: Int = 1, val versionName: String = "", val notes: String = "", val url: String = "", val sha256: String = "") {
    fun available(applicationId: String, installedCode: Int) = enabled && this.applicationId == applicationId && versionCode > installedCode && safeCloudLink(url)
}
@Serializable
data class CloudRecommendation(val id: String, val title: String, val description: String = "", val url: String)
@Serializable
data class CloudConfig(val schemaVersion: Int = 1, val revision: Int = 1, val reasoningRevision: Int = 0, val about: CloudAbout = CloudAbout(), val update: CloudUpdate = CloudUpdate(), val recommendationsEnabled: Boolean = false, val recommendations: List<CloudRecommendation> = emptyList()) {
    fun validate(): CloudConfig = apply {
        require(schemaVersion == 1 && revision > 0)
        require(about.name.length in 1..80 && about.description.length <= 2000 && (about.website.isEmpty() || safeCloudLink(about.website)))
        require(update.versionCode > 0 && update.versionName.length <= 80 && update.notes.length <= 10000)
        require(update.url.isEmpty() || safeCloudLink(update.url))
        require(!update.enabled || update.url.isNotEmpty())
        require(update.sha256.isEmpty() || update.sha256.matches(Regex("[A-Fa-f0-9]{64}")))
        require(recommendations.size <= 30 && recommendations.distinctBy { it.id }.size == recommendations.size)
        require(recommendations.all { it.id.length in 1..64 && it.title.length in 1..100 && it.description.length <= 1000 && safeCloudLink(it.url) })
    }
}

internal fun safeCloudLink(value: String): Boolean = value.length <= 1000 && runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

class CloudConfigStore(context: Context) {
    private val file = File(context.filesDir, "cloud-config.json")
    private val storage = LocalStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    fun cached(): CloudConfig = runCatching {
        storage.read(file, CloudConfig.serializer())?.validate()?.let { config ->
            if (System.currentTimeMillis() - file.lastModified() in 0..86_400_000) config else config.copy(recommendationsEnabled = false)
        }
    }.getOrNull() ?: CloudConfig()

    fun refresh(force: Boolean = false): CloudConfig {
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() in 0..300_000) return cached()
        val connection = URL(CONFIG_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val config = json.decodeFromString<CloudConfig>(connection.inputStream.use { it.readLimited(262_144) }.toString(Charsets.UTF_8)).validate()
            require(config.revision >= cached().revision) { "Older cloud configuration" }
            storage.write(file, CloudConfig.serializer(), config)
            return config
        } finally { connection.disconnect() }
    }
    companion object { const val CONFIG_URL = "https://codeb.baiyun.si/api/client/config" }
}
