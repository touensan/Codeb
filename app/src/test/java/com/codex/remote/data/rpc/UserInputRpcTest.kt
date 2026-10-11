package com.codex.remote.data.rpc

import com.codex.remote.resolveApproval
import com.codex.remote.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class UserInputRpcTest {
    @Test fun questionsAndAnswersPreserveProtocolIdsOnLocalAndRemoteTransports() = runBlocking {
        for (local in listOf(false, true)) for (id in listOf(JsonPrimitive("0"), JsonPrimitive(0))) {
            val input = PipedReader(); val server = PipedWriter(input); val output = StringWriter()
            val transport = object : AppServerTransport {
                override val reader = input.buffered()
                override val writer = output.buffered()
                override val errorReader = StringReader("").buffered()
                override val remotePlatform = RemotePlatform.POSIX
                override val codexVersion = "test"
                override val local = local
                override fun close() { server.close() }
            }
            CodexRpcClient(transport).use { client ->
                val init = async { client.initialize() }
                withTimeout(2000) { while (!output.toString().contains("initialize")) delay(10) }
                server.write("""{"id":1,"result":{}}""" + "\n"); server.flush(); init.await()
                for (method in listOf("item/started", "item/completed")) {
                    val compact = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it is AppServerEvent.ItemUpsert } as AppServerEvent.ItemUpsert }
                    server.write("""{"method":"$method","params":{"threadId":"thread","turnId":"turn","item":{"type":"contextCompaction","id":"compact"}}}""" + "\n"); server.flush()
                    assertEquals(if (method == "item/started") "inProgress" else "completed", withTimeout(2000) { compact.await().item.status })
                }
                val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it is AppServerEvent.Approval } as AppServerEvent.Approval }
                server.write("""{"id":$id,"method":"item/tool/requestUserInput","params":{"threadId":"thread","isBlocking":false,"questions":[{"id":"choice","header":"Choose","question":"Which?","options":[{"label":"A","description":"Details"}]},{"id":"note","header":"Note","question":"Explain","isSecret":true,"options":null}]}}""" + "\n")
                server.flush()
                val approval = withTimeout(2000) { event.await().request }
                assertEquals(listOf("Details"), approval.questions[0].optionDescriptions)
                assertTrue(approval.questions[1].isSecret)
                client.respondToApproval(approval, "accept", mapOf("choice" to listOf("A"), "note" to listOf("custom answer")))
                val reply = output.toString().lineSequence().first { it.contains("custom answer") }.let { Json.parseToJsonElement(it).jsonObject }
                assertEquals(id, reply["id"])
                assertEquals("custom answer", reply["result"]!!.jsonObject["answers"]!!.jsonObject["note"]!!.jsonObject["answers"]!!.jsonArray[0].jsonPrimitive.content)
                val resolved = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it is AppServerEvent.ApprovalResolved } }
                server.write("""{"method":"serverRequest/resolved","params":{"threadId":"thread","requestId":$id}}""" + "\n"); server.flush()
                assertEquals(id.toString(), (withTimeout(2000) { resolved.await() } as AppServerEvent.ApprovalResolved).originalId)
            }
        }
    }

    @Test fun resolvingOneQuestionKeepsOtherPendingQuestionsAndAttachmentsHaveActualPaths() {
        val first = ApprovalRequest("0", ApprovalKind.USER_INPUT, "", "", "", originalId = "0")
        val second = first.copy(originalId = "\"0\"")
        val state = AppUiState(pendingApproval = first, queuedApprovals = listOf(second)).resolveApproval("0", "0")
        assertEquals(second, state.pendingApproval)
        assertTrue(state.queuedApprovals.isEmpty())
        val image = ComposerImageAttachment(displayName = "photo.png", mimeType = "image/png", dataUrl = "data:image/png;base64,YQ==", uploadedPath = "/project/photo.png")
        val file = ComposerImageAttachment(displayName = "document.pdf", mimeType = "application/pdf", dataUrl = "", uploadedPath = "/project/document.pdf")
        val inputs = CodexRpcClient.turnSteerParams("thread", "turn", "", emptyList(), listOf(image, file))["input"]!!.jsonArray
        assertEquals(1, inputs.count { it.jsonObject["type"]!!.jsonPrimitive.content == "localImage" })
        assertEquals("/project/photo.png", inputs.first { it.jsonObject["type"]!!.jsonPrimitive.content == "localImage" }.jsonObject["path"]!!.jsonPrimitive.content)
        assertTrue(inputs.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("/project/document.pdf") == true })
    }
}
