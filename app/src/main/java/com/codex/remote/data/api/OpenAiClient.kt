package com.codex.remote.data.api

import com.codex.remote.R
import com.codex.remote.ui.uiText

import com.codex.remote.data.store.ApiModel
import com.codex.remote.data.store.ApiModelTest
import com.codex.remote.data.store.ApiProtocol
import com.codex.remote.data.store.ApiProvider
import com.codex.remote.data.store.LocalMessage
import com.codex.remote.data.store.validateApiBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class OpenAiClient {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun models(provider: ApiProvider, key: String): List<ApiModel> = withContext(Dispatchers.IO) {
        val connection = open(provider, key, "models")
        try {
            checkResponse(connection)
            parseModels(json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject)
        } finally { connection.disconnect() }
    }

    open suspend fun testModel(provider: ApiProvider, key: String): ApiModelTest {
        val body = probeBody(provider)
        val path = if (provider.protocol == ApiProtocol.RESPONSES) "responses" else "chat/completions"
        val response = try { requestJson(provider, key, path, body) }
        catch (error: ApiHttpException) {
            if (provider.protocol != ApiProtocol.CHAT_COMPLETIONS || !error.legacyLimit) throw error
            requestJson(provider, key, path, JsonObject(body.filterKeys { it != "max_completion_tokens" } + ("max_tokens" to JsonPrimitive(64))))
        }
        return probeResult(response)
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

    suspend fun testImage(provider: ApiProvider, key: String, image: String): String {
        val body=JsonObject(requestBody(provider.copy(effort=""),listOf(LocalMessage(role="user",
            text="Read the six digits shown in the image. Reply with those six digits only.",
            attachments=listOf(com.codex.remote.data.store.LocalAttachment(name="capability-check.png",kind="image",data=image))))) +
            mapOf("stream" to JsonPrimitive(false), (if(provider.protocol==ApiProtocol.RESPONSES) "max_output_tokens" else "max_completion_tokens") to JsonPrimitive(1024)))
        return responseText(requestJson(provider,key,if(provider.protocol==ApiProtocol.RESPONSES) "responses" else "chat/completions",body))
    }

    private suspend fun requestJson(provider: ApiProvider, key: String, path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = open(provider, key, path)
            continuation.invokeOnCancellation { CoroutineScope(Dispatchers.IO).launch { connection.disconnect() } }
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

    open suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray? = null, onDelta: (String) -> Unit) = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        suspendCancellableCoroutine<Unit> { continuation ->
        val connection = open(provider, key, if (provider.protocol == ApiProtocol.RESPONSES) "responses" else "chat/completions")
        // Disconnect blocked reads immediately on cancellation, instead of waiting for the socket timeout.
        continuation.invokeOnCancellation { CoroutineScope(Dispatchers.IO).launch { connection.disconnect() } }
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
        val status = connection.responseCode
        if (status in 200..299) return
        val error = runCatching { json.parseToJsonElement(connection.errorStream?.bufferedReader()?.use { reader ->
            val buffer = CharArray(4096)
            val count = reader.read(buffer)
            if (count > 0) String(buffer, 0, count) else ""
        }.orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        // Only use upstream text to detect this parameter; never display credentials echoed by a relay.
        throw ApiHttpException(status, status == 400 && message.contains("max_completion_tokens") &&
            (message.contains("unsupported", true) || message.contains("not supported", true) || message.contains("unknown", true)))
    }

    private class ApiHttpException(status: Int, val legacyLimit: Boolean) : IllegalArgumentException(
        uiText(R.string.api_http_error, "API HTTP %1\$d; check endpoint, model and credentials", status))

    companion object {
        internal fun probeResult(response: JsonObject): ApiModelTest {
            check((response["error"] == null || response["error"] == JsonNull) && response["status"]?.jsonPrimitive?.contentOrNull != "failed") { uiText(R.string.model_probe_failed, "Upstream failed; check the model and API type") }
            return if (responseText(response).isNotBlank()) ApiModelTest("passed", uiText(R.string.model_probe_success, "Request succeeded with a text reply"))
            else ApiModelTest("inconclusive", uiText(R.string.model_probe_empty, "Request accepted without text; retry or check the model type"))
        }
        internal fun probeBody(provider: ApiProvider): JsonObject = JsonObject(
            requestBody(provider.copy(effort = ""), listOf(LocalMessage(role = "user", text = "Reply only OK."))) +
                mapOf("stream" to JsonPrimitive(false), (if (provider.protocol == ApiProtocol.RESPONSES) "max_output_tokens" else "max_completion_tokens") to JsonPrimitive(64)))
        internal fun parseModels(response: JsonObject): List<ApiModel> = response["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val efforts = (model["supported_reasoning_efforts"] ?: model["reasoning_efforts"]) as? JsonArray
            ApiModel(id, efforts.orEmpty().mapNotNull { effort ->
                (effort as? JsonPrimitive)?.contentOrNull ?: (effort as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            }.distinct(), effortsReported = efforts != null)
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
                messages.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }.forEach { message -> add(buildJsonObject {
                    put("role", message.role)
                    if (message.attachments.isEmpty()) put("content", message.text) else put("content", buildJsonArray {
                        val responses = provider.protocol == ApiProtocol.RESPONSES
                        val textType = if (responses) "input_text" else "text"
                        if (message.text.isNotBlank()) add(buildJsonObject { put("type", textType); put("text", message.text) })
                        message.attachments.forEach { attachment -> add(buildJsonObject {
                            when (attachment.kind) {
                                "text" -> { put("type", textType); put("text", "${attachment.name}\n${attachment.data}") }
                                "image" -> {
                                    require(attachment.data.startsWith("data:image/"))
                                    put("type", if (responses) "input_image" else "image_url")
                                    if (responses) put("image_url", attachment.data) else put("image_url", buildJsonObject { put("url", attachment.data) })
                                }
                                "file" -> {
                                    require(responses && attachment.data.startsWith("data:application/pdf;base64,")) { uiText(R.string.unsupported_attachment, "Choose an image, UTF-8 text file, or a PDF with Responses API") }
                                    put("type", "input_file"); put("filename", attachment.name); put("file_data", attachment.data)
                                }
                                else -> error("Unsupported attachment")
                            }
                        }) }
                    })
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
