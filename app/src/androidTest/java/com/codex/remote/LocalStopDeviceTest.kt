package com.codex.remote

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.data.api.OpenAiClient
import com.codex.remote.data.store.*
import com.codex.remote.data.rpc.*
import com.codex.remote.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.io.*

@RunWith(AndroidJUnit4::class)
class LocalStopDeviceTest {
    @Test fun workspaceStopFindsMissingTurnAndClosesRuntimeBeforeThreadExists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val input = PipedReader(); val server = PipedWriter(input); val output = StringWriter()
        var closed = false
        val transport = object : AppServerTransport {
            override val reader = input.buffered(); override val writer = output.buffered()
            override val errorReader = StringReader("").buffered()
            override val remotePlatform = RemotePlatform.POSIX
            override val codexVersion = "0.162.0"; override val local = true
            override fun close() { closed = true; server.close() }
        }
        val consumed = mutableSetOf<String>()
        suspend fun request(method: String): JsonObject = withTimeout(4_000) {
            while (true) {
                val found = output.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
                    .firstOrNull { it["method"]?.jsonPrimitive?.content == method && it["id"].toString() !in consumed }
                if (found != null) { consumed += found["id"].toString(); return@withTimeout found }
                delay(10)
            }
            error("unreachable")
        }
        fun reply(req: JsonObject, result: String) { server.write("{\"id\":${req["id"]},\"result\":$result}\n"); server.flush() }
        val client = CodexRpcClient(transport)
        val init = async { client.initialize() }; reply(request("initialize"), "{}"); init.await()
        val vm = withContext(Dispatchers.Main) { AppViewModel(app, autoRestore = false) }
        val lifecycle = ViewModelStore().apply { put("workspace-stop", vm) }
        fun field(name: String) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("rpc").set(vm, client)
        @Suppress("UNCHECKED_CAST") val state = field("_state").get(vm) as MutableStateFlow<AppUiState>
        val connection = SavedConnection(name = "Stop check", host = "localhost", username = "termux", authType = AuthType.PASSWORD, localAccess = "standard")
        try {
            withContext(Dispatchers.Main) {
                state.value = AppUiState(activeConnection = connection, selectedThreadId = "offline-stop", selectedProjectPath = "/tmp", isTurnRunning = true)
                vm.interruptTurn()
                assertFalse(vm.state.value.isTurnRunning)
                assertTrue(vm.state.value.stopRequested)
            }
            reply(request("thread/read"), """{"thread":{"turns":[{"id":"active-stop","status":"inProgress"}]}}""")
            val interrupt = request("turn/interrupt")
            assertEquals("active-stop", interrupt["params"]!!.jsonObject["turnId"]!!.jsonPrimitive.content)
            assertFalse("No need to await interrupt acknowledgement for UI stop", vm.state.value.isTurnRunning)
            reply(interrupt, "{}"); delay(80)
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(selectedThreadId = null, isTurnRunning = true, stopRequested = false)
                vm.interruptTurn()
                assertFalse(vm.state.value.isTurnRunning)
            }
            withTimeout(4_000) { while (!closed) delay(10) }
            assertEquals(ConnectionStatus.DISCONNECTED, vm.state.value.connectionStatus)
            assertTrue(vm.state.value.stopRequested)
        } finally { withContext(Dispatchers.Main) { lifecycle.clear() }; client.close() }
    }

    @Test fun dailyQueueSteerAndStopIgnoreLateRepliesAndKeepQueuedMessages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ApiStore(app)
        val before = store.providers()
        val provider = ApiProvider("stop-check", "Check", "https://example.com/v1", store.encryptKey("offline-check"),
            models = listOf(ApiModel("check-model")), selectedModel = "check-model")
        val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        val prompts = CopyOnWriteArrayList<String>()
        val fake = object : OpenAiClient() {
            override suspend fun stream(provider: ApiProvider, key: String, messages: List<LocalMessage>, prefix: JsonArray?, onDelta: (String) -> Unit) {
                val gate = CompletableDeferred<Unit>(); gates += gate
                prompts += messages.last().text
                onDelta("partial-${messages.last().text}")
                // Simulate a socket that delivers data after cancellation and unwinds after a newer request.
                withContext(NonCancellable) { gate.await(); onDelta("-late") }
            }
        }
        store.saveProviders(before + provider)
        val vm = LocalViewModel(app, fake)
        val lifecycle = ViewModelStore().apply { put("stop-check", vm) }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(8_000) { while (!condition()) delay(20) }
        suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        try {
            waitFor { vm.state.value.providers.any { it.id == provider.id } }
            onMain { vm.provider(provider.id); vm.newConversation(); vm.draft("first"); vm.send() }
            waitFor { gates.size == 1 && vm.state.value.selected!!.messages.last().text.isNotEmpty() }
            onMain { vm.draft("queued"); vm.send() }
            assertEquals("queued", vm.state.value.selected!!.queuedMessages.single().text)
            onMain { vm.draft("correction"); vm.send(steer = true) }
            waitFor { gates.size == 2 }
            assertEquals(listOf("first", "correction"), prompts.toList())
            gates[0].complete(Unit); delay(150)
            assertTrue("Old completion must not clear the new running request", vm.state.value.sending)
            assertEquals("partial-first", vm.state.value.selected!!.messages.first { it.role == "assistant" }.text)
            gates[1].complete(Unit)
            waitFor { gates.size == 3 }
            assertEquals("queued", prompts.last())
            onMain { vm.draft("kept-after-stop"); vm.send(); vm.stop() }
            assertFalse("Stop is synchronous, without waiting for socket cleanup", vm.state.value.sending)
            assertTrue(vm.state.value.selected!!.queuePaused)
            assertEquals("kept-after-stop", vm.state.value.selected!!.queuedMessages.single().text)
            gates[2].complete(Unit); delay(150)
            assertFalse(vm.state.value.sending)
            assertEquals(3, prompts.size)
            assertFalse(vm.state.value.selected!!.messages.last().text.endsWith("-late"))
            onMain { vm.steerQueuedMessage(vm.state.value.selected!!.queuedMessages.single().id) }
            waitFor { gates.size == 4 }
            gates[3].complete(Unit)
            waitFor { !vm.state.value.sending }
            assertEquals("kept-after-stop", prompts.last())
            assertTrue(vm.state.value.selected!!.queuedMessages.isEmpty())
        } finally {
            gates.forEach { it.complete(Unit) }
            onMain { lifecycle.clear() }
            store.saveProviders(before)
        }
    }
}
