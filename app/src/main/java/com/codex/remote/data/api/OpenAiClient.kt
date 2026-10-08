package com.codex.remote.data.api

import com.codex.remote.R
import com.codex.remote.ui.uiText

import com.codex.remote.data.store.ApiModel
import com.codex.remote.data.store.ApiProtocol
import com.codex.remote.data.store.ApiProvider
import com.codex.remote.data.store.LocalMessage
import com.codex.remote.data.store.validateApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun models(provider: ApiProvider, key: String): List<ApiModel> = withContext(Dispatchers.IO) {
        val connection = open(provider, key, "models")
        try {
            checkResponse(connection)
            parseModels(json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject)
        } finally { connection.disconnect() }
    }

    suspend fun compact(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?): JsonArray {
        require(provider.protocol == ApiProtocol.RESPONSES) { uiText(R.string.compaction_requires_responses, "This provider uses Chat Completions; native compaction requires Responses") }
        val body = requestBody(provider, messages, prefix).filterKeys { it != "stream" && it != "store" && it != "reasoning" }
        return requestJson(provider, key, "responses/compact", JsonObject(body))["output"]?.jsonArray
            ?: error(uiText(R.string.compaction_no_output, "Compaction returned no output; original history was kept"))
    }

    suspend fun generateImage(provider: ApiProvider, key: String, prompt: String): List<ByteArray> {
        val response = requestJson(provider, key, "images/generations", buildJsonObject {
            put("model", provider.selectedModel)
            put("prompt", prompt)
            put("n", 1)
        })
        return withContext(Dispatchers.IO) {
            response["data"]?.jsonArray.orEmpty().map { item ->
                val obj = item.jsonObject
                val encoded = obj["b64_json"]?.jsonPrimitive?.contentOrNull
                if (encoded != null) android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                else {
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: error(uiText(R.string.image_no_data, "Image response has no image data"))
                    require(java.net.URI(url).scheme == "https") { uiText(R.string.image_https_only, "Image URL must use HTTPS") }
                    val download = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 120_000 }
                    try { checkResponse(download); download.inputStream.use { it.readBytes() } }
                    finally { download.disconnect() }
                }
            }.also { require(it.isNotEmpty()) { uiText(R.string.image_empty, "No images returned") } }
        }
    }

    private suspend fun requestJson(provider: ApiProvider, key: String, path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = open(provider, key, path)
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                checkResponse(connection)
                val result = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
    }

    suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray? = null, onDelta: (String) -> Unit) = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        suspendCancellableCoroutine<Unit> { continuation ->
        val connection = open(provider, key, if (provider.protocol == ApiProtocol.RESPONSES) "responses" else "chat/completions")
        // Disconnect blocked reads immediately on cancellation, instead of waiting for the socket timeout.
        continuation.invokeOnCancellation { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            val payload = requestBody(provider, messages, prefix).toString().toByteArray()
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            checkResponse(connection)
            if (!connection.contentType.orEmpty().contains("text/event-stream")) {
                val response = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                onDelta(responseText(response))
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            var completed = false
            connection.inputStream.bufferedReader().use { reader ->
                val eventData = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        val data = eventData.toString()
                        eventData.clear()
                        if (data == "[DONE]") { completed = true; break }
                        if (data.isNotBlank()) {
                            val event = json.parseToJsonElement(data).jsonObject
                            val type = event["type"]?.jsonPrimitive?.content.orEmpty()
                            if (event["error"] != null || type == "error" || type == "response.failed" || type == "response.incomplete") {
                                error(uiText(R.string.stream_failed, "API stream failed"))
                            }
                            streamDelta(event).takeIf { it.isNotEmpty() }?.let(onDelta)
                            if (type == "response.completed" || event["choices"]?.jsonArray?.any {
                                (it as? JsonObject)?.get("finish_reason")?.let { reason -> reason != JsonNull } == true
                            } == true) completed = true
                            if (completed) break
                        }
                    } else if (line.startsWith("data:")) {
                        if (eventData.isNotEmpty()) eventData.append('\n')
                        eventData.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
            check(completed) { uiText(R.string.ui_349, "Stream disconnected before completion; partial reply was kept") }
            if (continuation.isActive) continuation.resume(Unit)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            connection.disconnect()
        }
        }
    }

    private fun open(provider: ApiProvider, key: String, path: String): HttpURLConnection =
        (URL("${validateApiBaseUrl(provider.baseUrl)}/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
        }

    private fun checkResponse(connection: HttpURLConnection) {
        require(connection.responseCode in 200..299) { uiText(R.string.api_http_error, "API HTTP %1\$d; check endpoint, model and credentials", connection.responseCode) }
    }

    companion object {
        internal fun parseModels(response: JsonObject): List<ApiModel> = response["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val efforts = (model["supported_reasoning_efforts"] ?: model["reasoning_efforts"]) as? JsonArray
            ApiModel(id, efforts.orEmpty().mapNotNull { effort ->
                (effort as? JsonPrimitive)?.contentOrNull ?: (effort as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            }.distinct())
        }.distinctBy { it.id }.sortedBy { it.id }

        internal fun requestBody(provider: ApiProvider, messages: List<LocalMessage>, prefix: JsonArray? = null): JsonObject = buildJsonObject {
            require(provider.selectedModel.isNotBlank()) { uiText(R.string.ui_290, "Choose a model ID") }
            put("model", provider.selectedModel)
            put("stream", true)
            if (provider.effort.isNotBlank()) {
                if (provider.protocol == ApiProtocol.RESPONSES) put("reasoning", buildJsonObject { put("effort", provider.effort) })
                else put("reasoning_effort", provider.effort)
            }
            put(if (provider.protocol == ApiProtocol.RESPONSES) "input" else "messages", buildJsonArray {
                if (provider.protocol == ApiProtocol.RESPONSES) prefix?.forEach { add(it) }
                messages.filter { it.text.isNotBlank() }.forEach { message -> add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.text)
                }) }
            })
            if (provider.protocol == ApiProtocol.RESPONSES) put("store", false)
        }

        internal fun streamDelta(event: JsonObject): String {
            if (event["type"]?.jsonPrimitive?.content == "response.output_text.delta") return event["delta"]?.jsonPrimitive?.content.orEmpty()
            return event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content")?.let {
                if (it is JsonPrimitive) it.contentOrNull.orEmpty() else ""
            }.orEmpty()
        }

        internal fun responseText(response: JsonObject): String =
            response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: response["output"]?.jsonArray.orEmpty().flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }
                    .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
    }
}
