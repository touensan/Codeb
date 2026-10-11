package com.codex.remote.data.ssh

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedCodexSocketTest {
    private fun checkSocket(server: (InputStream,OutputStream)->Unit, client:(SharedCodexSocket)->Unit) {
        val fromClient=PipedInputStream(65536);val toServer=PipedOutputStream(fromClient)
        val fromServer=PipedInputStream(65536);val toClient=PipedOutputStream(fromServer)
        val executor=Executors.newSingleThreadExecutor()
        val task=executor.submit {
            val headers=fromClient.bufferedReader().let { reader -> buildList { while(true){val line=reader.readLine();if(line.isBlank())break;add(line)} } }
            val key=headers.first {it.startsWith("Sec-WebSocket-Key:")}.substringAfter(':').trim()
            val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            toClient.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray());toClient.flush()
            try {server(fromClient,toClient)} finally {toClient.close()}
        }
        try {try {client(SharedCodexSocket(fromServer,toServer))} finally {task.get(5,TimeUnit.SECONDS)}}
        finally {fromClient.close();fromServer.close();executor.shutdownNow()}
    }
    private fun frame(input:InputStream):Pair<Int,ByteArray> {
        val op=input.read() and 15;val n=input.read();assertTrue(n and 128 != 0)
        var length=(n and 127).toLong()
        if(length==126L)length=((input.read() shl 8) or input.read()).toLong()
        if(length==127L){length=0;repeat(8){length=(length shl 8) or input.read().toLong()}}
        val mask=input.readNBytes(4);val data=input.readNBytes(length.toInt())
        return op to ByteArray(data.size){(data[it].toInt() xor mask[it%4].toInt()).toByte()}
    }
    @Test(timeout=10000) fun maskedJsonMessagesFragmentedRepliesAndPingRoundTrip() {
        val payload="中".repeat(24000)
        checkSocket({input,output ->
            assertEquals(payload,frame(input).second.toString(Charsets.UTF_8))
            output.write(byteArrayOf(1,2,123,34,0x89.toByte(),1,65,0x80.toByte(),5,120,34,58,49,125));output.flush()
            val pong=frame(input);assertEquals(10,pong.first);assertArrayEquals(byteArrayOf(65),pong.second)
            output.write(byteArrayOf(0x88.toByte(),0));output.flush();assertEquals(8,frame(input).first)
        },{socket -> socket.writer.write(payload);socket.writer.newLine();socket.writer.flush();assertEquals("{\"x\":1}",socket.reader.readLine());assertNull(socket.reader.readLine())})
    }
    @Test(timeout=10000) fun rejectsOversizedFramesBeforeAllocatingPayload() {
        checkSocket({_,output ->output.write(byteArrayOf(0x81.toByte(),127,0,0,0,0,2,0,0,1));output.flush()}, {socket ->
            try {socket.reader.readLine();fail("Oversized frame accepted")} catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("size"))}
        })
    }
    @Test(timeout=10000) fun rejectsUnverifiedUpgrade() {
        try {SharedCodexSocket(ByteArrayInputStream("HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong\r\n\r\n".toByteArray()),ByteArrayOutputStream());fail("Bad handshake accepted")}
        catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("handshake"))}
    }
}
