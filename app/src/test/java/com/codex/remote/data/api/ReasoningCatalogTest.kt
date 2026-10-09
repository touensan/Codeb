package com.codex.remote.data.api

import com.codex.remote.data.store.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReasoningCatalogTest {
    @Test fun customRawEffortIsSentUntranslatedThroughBothProtocols() {
        val custom = provider("vendor-model").copy(effort = "vendor-deep.v2")
        assertEquals("vendor-deep.v2", OpenAiClient.requestBody(custom, emptyList()).jsonObject["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("vendor-deep.v2", OpenAiClient.requestBody(custom.copy(protocol = ApiProtocol.RESPONSES), emptyList()).jsonObject["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
    private val catalog = Json.decodeFromString<ReasoningCatalog>(File("src/main/assets/model-reasoning-catalog.json").readText().removePrefix("\uFEFF")).validate()
    private fun provider(model: String) = ApiProvider(name = "test", baseUrl = "https://example.com/v1", encryptedKey = "", selectedModel = model)

    @Test fun fillsKnownAndUnknownModelsWithMediumAndPreservesConversationChoice() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), provider("openai/GPT-6-Luna").resolved(catalog).models.single().efforts)
        assertEquals("medium", provider("custom").resolved(catalog).effort)
        val conversation = LocalConversation(model = "gpt-6-luna", effort = "high")
        assertEquals("gpt-6-luna", provider("gpt-6-astra").resolved(catalog, conversation).selectedModel)
        assertEquals("high", provider("gpt-6-astra").resolved(catalog, conversation).effort)
    }

    @Test fun respectsManualLevelsNonReasoningAndProviderExceptions() {
        assertEquals("max", provider("gpt-6-luna").copy(models = listOf(ApiModel("gpt-6-luna", listOf("max")))).resolved(catalog).effort)
        assertEquals("", provider("gpt-4o").resolved(catalog).effort)
        assertEquals("", provider("custom").copy(models = listOf(ApiModel("custom", effortsReported = true))).resolved(catalog).effort)
        assertEquals("high", provider("deepseek-flash").copy(baseUrl = "https://api.deepseek.com/v1").resolved(catalog).effort)
        assertEquals("medium", provider("deepseek-flash").resolved(catalog).effort)
        assertEquals(listOf("none", "low", "medium", "high"), catalog.rule("gpt-5.1-2025-11-13", "https://example.com").efforts)
    }

    @Test fun rejectsInvalidUpdateAndBoundedReads() {
        assertTrue(runCatching { catalog.copy(schemaVersion = 2).validate() }.isFailure)
        assertTrue(runCatching { catalog.copy(revision = 0).validate() }.isFailure)
        assertTrue(runCatching { catalog.copy(fallback = catalog.fallback.copy(defaultEffort = "ultra")).validate() }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readLimited(2))
        assertTrue(runCatching { byteArrayOf(1, 2, 3).inputStream().readLimited(2) }.isFailure)
    }

    @Test fun ultraIsKeptAndSentThroughBothProtocolsWhileReportedLevelsTakePrecedence() {
        for (model in listOf("openai/GPT-6.1-Sol", "gpt-6-astra", "gpt-6-sol", "gpt-5.6-sol", "gpt-5.6-terra")) {
            val configured = provider(model).resolved(catalog, LocalConversation(model = model, effort = "ultra"))
            assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra"), configured.models.single().efforts)
            assertEquals("ultra", configured.effort)
            val messages = listOf(LocalMessage(role = "user", text = "hello"))
            assertEquals("ultra", OpenAiClient.requestBody(configured, messages)["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals("ultra", OpenAiClient.requestBody(configured.copy(protocol = ApiProtocol.RESPONSES), messages)["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        }
        val limited = provider("gpt-6.1-sol").copy(models = listOf(ApiModel("gpt-6.1-sol", listOf("low", "medium"), effortsReported = true)), effort = "ultra").resolved(catalog)
        assertEquals(listOf("low", "medium"), limited.models.single().efforts)
        assertEquals("medium", limited.effort)
        assertFalse(provider("gpt-5.6-luna").resolved(catalog).models.single().efforts.contains("ultra"))
    }

    @Test fun attachmentsReachBothProtocolsAndFilesRequireResponses() {
        val message = LocalMessage(role = "user", text = "", attachments = listOf(
            LocalAttachment(name = "pic.png", kind = "image", data = "data:image/png;base64,AA=="),
            LocalAttachment(name = "code.kt", kind = "text", data = "fun main() {}")))
        val json = OpenAiClient.requestBody(provider("gpt-6-luna").resolved(catalog), listOf(message)).toString()
        assertTrue(json.contains("image_url") && json.contains("fun main() {}"))
        val pdf = message.copy(attachments = listOf(LocalAttachment(name = "report.pdf", kind = "file", data = "data:application/pdf;base64,AA==")))
        assertTrue(runCatching { OpenAiClient.requestBody(provider("gpt-6-luna"), listOf(pdf)) }.isFailure)
        assertTrue(OpenAiClient.requestBody(provider("gpt-6-luna").copy(protocol = ApiProtocol.RESPONSES), listOf(pdf)).toString().contains("input_file"))
    }
}
