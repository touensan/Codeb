package com.codex.remote.data.ssh

import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// The official proxy is a byte tunnel to a WebSocket, not a JSONL server.
// ponytail: no extensions/compression; 32 MiB message ceiling, matching bounded history pages.
internal class SharedCodexSocket(private val input: InputStream, private val output: OutputStream) {
    private val random = SecureRandom()
    private val limit = 32 * 1024 * 1024
    init {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        output.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
        val header = ByteArrayOutputStream()
        while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
            check(header.size() < 16384) { "Oversized WebSocket handshake" }
            header.write(next())
        }
        val lines = header.toString("US-ASCII").split("\r\n")
        check(lines.first().startsWith("HTTP/1.1 101 ")) { "Shared Codex socket rejected the WebSocket handshake" }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        check(headers["sec-websocket-accept"] == expected && headers["upgrade"].equals("websocket", true) &&
            headers["connection"].orEmpty().split(',').any { it.trim().equals("upgrade",true) }) { "Invalid WebSocket handshake" }
    }
    private fun next(): Int = input.read().also { if (it < 0) throw EOFException("Shared Codex socket closed") }
    @Synchronized private fun send(opcode: Int, bytes: ByteArray) {
        require(bytes.size <= limit)
        output.write(0x80 or opcode)
        when {
            bytes.size < 126 -> output.write(0x80 or bytes.size)
            bytes.size <= 65535 -> { output.write(0x80 or 126); output.write(bytes.size ushr 8); output.write(bytes.size) }
            else -> { output.write(0x80 or 127); for (shift in 56 downTo 0 step 8) output.write((bytes.size.toLong() ushr shift).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(mask)
        val encoded = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
        output.write(encoded); output.flush()
    }
    private fun receive(): ByteArray? {
        val message = ByteArrayOutputStream()
        var fragmented = false
        while (true) {
            val first = next(); val second = next()
            check(first and 0x70 == 0 && second and 0x80 == 0) { "Unsupported WebSocket frame" }
            val opcode = first and 15; val final = first and 0x80 != 0
            var size = (second and 127).toLong()
            if (size == 126L) size = ((next() shl 8) or next()).toLong()
            else if (size == 127L) { size = 0; repeat(8) { size = (size shl 8) or next().toLong() } }
            check(size in 0..limit.toLong() && (opcode < 8 || final && size <= 125)) { "Invalid WebSocket frame size" }
            val bytes = ByteArray(size.toInt()); var offset = 0
            while (offset < bytes.size) { val n = input.read(bytes,offset,bytes.size-offset); if(n<=0)throw EOFException(); offset+=n }
            when(opcode) {
                8 -> { send(8, bytes); return null }
                9 -> send(10, bytes)
                10 -> Unit
                0,1 -> {
                    check((opcode == 0) == fragmented) { "Invalid WebSocket continuation" }
                    check(message.size().toLong() + size <= limit) { "Shared Codex message too large" }
                    message.write(bytes)
                    if(final) return message.toByteArray()
                    fragmented = true
                }
                else -> error("Unsupported WebSocket opcode")
            }
        }
    }
    val reader: BufferedReader = BufferedReader(InputStreamReader(object: InputStream() {
        private var current = ByteArrayInputStream(byteArrayOf())
        override fun read(): Int { val one=ByteArray(1); return if(read(one,0,1)<0)-1 else one[0].toInt() and 255 }
        override fun read(bytes:ByteArray, offset:Int, length:Int):Int {
            if(length==0)return 0
            if(current.available()==0) current=ByteArrayInputStream((receive() ?: return -1)+byteArrayOf(10))
            return current.read(bytes,offset,length)
        }
    }, Charsets.UTF_8))
    val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(object: OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(value:Int) {
            if(value==10) { if(pending.size()>0){val bytes=pending.toByteArray();send(1,if(bytes.lastOrNull()==13.toByte())bytes.copyOf(bytes.size-1) else bytes)};pending.reset() }
            else {check(pending.size()<limit);pending.write(value)}
        }
        override fun close() { runCatching {send(8,byteArrayOf())} }
    }, Charsets.UTF_8))
}
