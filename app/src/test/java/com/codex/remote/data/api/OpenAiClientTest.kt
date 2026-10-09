package com.codex.remote.data.api

import com.codex.remote.data.store.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAiClientTest {
    private fun provider(protocol: ApiProtocol) = ApiProvider(name = "test", baseUrl = "https://example.com/v1", encryptedKey = "", protocol = protocol, selectedModel = "custom-model", effort = "high")

    @Test fun modelProbesUseTheExactIdAndBoundedNonStreamingRequests() {
        ApiProtocol.entries.forEach { protocol ->
            val body = OpenAiClient.probeBody(provider(protocol))
            assertEquals("custom-model", body["model"]!!.jsonPrimitive.content)
            assertFalse(body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(64, body[if (protocol == ApiProtocol.RESPONSES) "max_output_tokens" else "max_completion_tokens"]!!.jsonPrimitive.int)
            assertFalse(body.containsKey("reasoning"))
            assertFalse(body.containsKey("reasoning_effort"))
        }
    }

    @Test fun modelProbeAcceptsNullErrorButDoesNotInventATextReply() {
        fun result(source: String) = OpenAiClient.probeResult(Json.parseToJsonElement(source).jsonObject).outcome
        assertEquals("passed", result("""{"error":null,"status":"completed","output":[{"content":[{"type":"output_text","text":"OK"}]}]}"""))
        assertEquals("passed", result("""{"choices":[{"message":{"content":"OK"}}]}"""))
        assertEquals("inconclusive", result("""{"error":null,"status":"incomplete","output":[]}"""))
        assertThrows(IllegalStateException::class.java) { result("""{"error":{"message":"secret must not be displayed"}}""") }
    }

    @Test fun requestProtocolsKeepCustomModelAndSeparateReasoningFields() {
        val messages = listOf(LocalMessage(role = "user", text = "hello"))
        val chat = OpenAiClient.requestBody(provider(ApiProtocol.CHAT_COMPLETIONS), messages)
        assertEquals("custom-model", chat["model"]!!.jsonPrimitive.content)
        assertEquals("high", chat["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(chat.containsKey("messages"))
        assertFalse(chat.containsKey("input"))
        val prefix = Json.parseToJsonElement("""[{"type":"compaction","encrypted_content":"opaque"}]""").jsonArray
        val responses = OpenAiClient.requestBody(provider(ApiProtocol.RESPONSES), messages, prefix)
        assertEquals("high", responses["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(responses["store"]!!.jsonPrimitive.boolean)
        assertEquals(prefix[0], responses["input"]!!.jsonArray[0])
        assertEquals(2, responses["input"]!!.jsonArray.size)
    }

    @Test fun modelCapabilitiesAreNotGuessedFromTheirNames() {
        val models = OpenAiClient.parseModels(Json.parseToJsonElement("""{"data":[{"id":"gpt-something"},{"id":"custom","supported_reasoning_efforts":["low",{"value":"high"},"low"]}]}""").jsonObject)
        assertTrue(models.first { it.id == "gpt-something" }.efforts.isEmpty())
        assertEquals(listOf("low", "high"), models.first { it.id == "custom" }.efforts)
    }

    @Test fun bothStreamingFormatsDecodeTextWithoutLeakingOtherFields() {
        assertEquals("你好", OpenAiClient.streamDelta(Json.parseToJsonElement("""{"choices":[{"delta":{"content":"你好"}}]}""").jsonObject))
        assertEquals("hello", OpenAiClient.streamDelta(Json.parseToJsonElement("""{"type":"response.output_text.delta","delta":"hello"}""").jsonObject))
        assertEquals("", OpenAiClient.streamDelta(Json.parseToJsonElement("""{"type":"response.reasoning.delta","delta":"private"}""").jsonObject))
    }

    @Test fun endpointValidationRejectsPlaintextAndEmbeddedCredentials() {
        assertEquals("https://example.com/v1", validateApiBaseUrl(" https://example.com/v1/ "))
        listOf("http://example.com/v1", "https://name:secret@example.com/v1", "https://example.com/v1?api_key=secret", "file:///tmp/a").forEach {
            assertThrows(Exception::class.java) { validateApiBaseUrl(it) }
        }
    }
}
