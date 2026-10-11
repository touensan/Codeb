package com.codex.remote.data.rpc

import com.codex.remote.domain.RemotePlatform
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class PhoneToolRpcTest {
    @Test fun dynamicToolReplyPreservesStringAndNumericRequestIds()=runBlocking {
        for(id in listOf(JsonPrimitive("0"),JsonPrimitive(0))) {
            val input=PipedReader();val server=PipedWriter(input);val output=StringWriter()
            val transport=object:AppServerTransport {
                override val reader=input.buffered()
                override val writer=output.buffered()
                override val errorReader=StringReader("").buffered()
                override val remotePlatform=RemotePlatform.POSIX
                override val codexVersion="test"
                override val local=true
                override fun close(){server.close()}
            }
            CodexRpcClient(transport) {buildJsonObject {put("success",true);put("contentItems",JsonArray(emptyList()))}}.use { client ->
                val initialization=async {client.initialize()}
                withTimeout(2000){while(!output.toString().contains("initialize"))delay(10)}
                server.write("""{"id":1,"result":{}}"""+"\n");server.flush();initialization.await()
                server.write(buildJsonObject {put("id",id);put("method","item/tool/call");put("params",buildJsonObject {put("tool","codeb_phone");put("arguments",buildJsonObject {put("action","observe")})})}.toString()+"\n");server.flush()
                withTimeout(2000){while(!output.toString().contains("\"success\""))delay(10)}
                val reply=output.toString().lineSequence().filter {it.contains("\"success\"")}.map {Json.parseToJsonElement(it).jsonObject}.first()
                assertEquals(id,reply["id"])
                assertTrue(reply["result"]!!.jsonObject["success"]!!.jsonPrimitive.boolean)
            }
        }
    }
}
