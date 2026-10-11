package com.codex.remote.data.rpc

import com.codex.remote.data.local.localProof
import com.codex.remote.data.local.shellQuote
import com.codex.remote.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalRuntimeTest {
    @Test fun existingProvidersStayEnabledAndFileNamesStayWithinTheirDirectory() {
        val provider=Json.decodeFromString<com.codex.remote.data.store.ApiProvider>("""{"name":"existing","baseUrl":"https://example.com/v1","encryptedKey":"cipher"}""")
        assertTrue(provider.enabled)
        assertFalse(Json.decodeFromString<com.codex.remote.data.store.ApiProvider>(Json.encodeToString(com.codex.remote.data.store.ApiProvider.serializer(),provider.copy(enabled=false))).enabled)
        assertEquals("/work/说明.md",com.codex.remote.data.local.fileChild("/work/","说明.md"))
        for(name in listOf("", ".", "..", "a/b", "a\\b", "a\n", "a".repeat(241))) {
            assertThrows(IllegalArgumentException::class.java) {com.codex.remote.data.local.fileChild("/work",name)}
        }
    }
    @Test fun localRequestsKeepAndroidIdentityAndDoNotRequestLinuxSandbox() {
        val original = buildJsonObject { put("cwd", "/work"); put("permissions", ":workspace"); put("approvalPolicy", "never") }
        for (method in listOf("thread/start", "thread/resume", "thread/fork", "turn/start")) {
            val result = localRuntimeParams(method, original)
            assertFalse(result.containsKey("permissions"))
            assertEquals("on-request", result["approvalPolicy"]!!.jsonPrimitive.content)
            assertEquals("/work", result["cwd"]!!.jsonPrimitive.content)
            if (method == "turn/start") assertEquals("dangerFullAccess", result["sandboxPolicy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            else assertEquals("danger-full-access", result["sandbox"]!!.jsonPrimitive.content)
        }
        assertSame(original, localRuntimeParams("fs/readFile", original))
        assertEquals("never", original["approvalPolicy"]!!.jsonPrimitive.content)
    }
    @Test fun legacyConnectionsRemainRemoteAndShellArgumentsStayLiteral() {
        val old = """{"name":"existing","host":"example.com","username":"user","authType":"PASSWORD"}"""
        val saved = Json.decodeFromString<SavedConnection>(old)
        assertNull(saved.localAccess)
        assertEquals("'a'\\''b\n\$(id)'", shellQuote("a'b\n\$(id)"))
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", localProof(ByteArray(20) { 0x0b }, "Hi There"))
    }
}
