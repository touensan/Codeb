package com.codex.remote.data.rpc

import com.codex.remote.domain.RemotePlatform
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class QueueRpcTest {
    @Test fun queueIsServerPersistedAndSeparateFromSteering()=runBlocking {
        val input=PipedReader();val server=PipedWriter(input);val output=StringWriter()
        val transport=object:AppServerTransport {
            override val reader=input.buffered();override val writer=output.buffered()
            override val errorReader=StringReader("").buffered();override val remotePlatform=RemotePlatform.POSIX
            override val codexVersion="0.162.0";override fun close(){server.close()}
        }
        val consumed=mutableSetOf<String>()
        suspend fun request(method:String):JsonObject=withTimeout(2000) {
            while(true) {
                val found=output.toString().lineSequence().filter {it.isNotBlank()}.map {Json.parseToJsonElement(it).jsonObject}.firstOrNull {it["method"]?.jsonPrimitive?.content==method && it["id"].toString() !in consumed}
                if(found!=null){consumed+=found["id"].toString();return@withTimeout found}
                delay(10)
            }
            error("unreachable")
        }
        fun reply(req:JsonObject,result:String){server.write("{\"id\":${req["id"]},\"result\":$result}\n");server.flush()}
        CodexRpcClient(transport).use { client ->
            val init=async {client.initialize()};reply(request("initialize"),"{}");init.await()
            val stop=async {client.interruptActiveTurn("thread-a")}
            reply(request("thread/read"),"""{"thread":{"turns":[{"id":"old","status":"completed"},{"id":"actual-active","status":"inProgress"}]}}""")
            val interrupt=request("turn/interrupt")
            assertEquals("actual-active",interrupt["params"]!!.jsonObject["turnId"]!!.jsonPrimitive.content)
            reply(interrupt,"{}");assertEquals("actual-active",stop.await())
            val idleStop=async {client.interruptActiveTurn("thread-a")}
            reply(request("thread/read"),"""{"thread":{"turns":[{"id":"old","status":"completed"}]}}""")
            assertNull(idleStop.await())
            val enqueue=async {client.queueMessage("thread-a","after current task",emptyList(),emptyList())}
            val add=request("thread/queue/add");val params=add["params"]!!.jsonObject
            assertEquals("thread-a",params["threadId"]!!.jsonPrimitive.content)
            assertTrue(params["clientUserMessageId"]!!.jsonPrimitive.content.isNotBlank())
            assertFalse(output.toString().contains("turn/start"));assertFalse(output.toString().contains("turn/steer"))
            reply(add,"{}");enqueue.await()
            val list=async {client.queuedMessages("thread-a")}
            reply(request("thread/queue/list"),"""{"data":[{"id":"q1","input":[{"type":"text","text":"after current task"}]}]}""")
            assertEquals(listOf("q1" to "after current task"),list.await())
            val delete=async {client.deleteQueuedMessage("thread-a","q1")}
            val remove=request("thread/queue/delete");assertEquals("q1",remove["params"]!!.jsonObject["queuedSubmissionId"]!!.jsonPrimitive.content)
            reply(remove,"{}");delete.await()
            val promotion=async {client.steerQueuedMessage("thread-a","q2")}
            reply(request("thread/queue/list"),"""{"data":[{"id":"q2","input":[{"type":"text","text":"correct the task"}]}]}""")
            reply(request("thread/read"),"""{"thread":{"turns":[{"id":"active-turn","status":"inProgress"}]}}""")
            reply(request("thread/queue/delete"),"""{"deleted":true}""")
            val steer=request("turn/steer")
            assertEquals("active-turn",steer["params"]!!.jsonObject["expectedTurnId"]!!.jsonPrimitive.content)
            assertTrue(steer.toString().contains("correct the task"))
            reply(steer,"{}");promotion.await()
            val rejected=async {runCatching {client.steerQueuedMessage("thread-a","q3")}}
            reply(request("thread/queue/list"),"""{"data":[{"id":"q3","input":[{"type":"text","text":"keep on failure"}]}]}""")
            reply(request("thread/read"),"""{"thread":{"turns":[{"id":"active-turn","status":"inProgress"}]}}""")
            reply(request("thread/queue/delete"),"""{"deleted":true}""")
            val failed=request("turn/steer")
            server.write("{\"id\":${failed["id"]},\"error\":{\"code\":-32600,\"message\":\"Turn already completed\"}}\n");server.flush()
            val restored=request("thread/queue/add");assertTrue(restored.toString().contains("keep on failure"))
            reply(restored,"{}");assertTrue(rejected.await().isFailure)

        }
    }
}
