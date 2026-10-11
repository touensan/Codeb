package com.codex.remote.data.rpc

import com.codex.remote.domain.RemotePlatform
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable

interface AppServerTransport : Closeable {
    val reader: BufferedReader
    val writer: BufferedWriter
    val errorReader: BufferedReader
    val remotePlatform: RemotePlatform
    val codexVersion: String
    val local: Boolean get() = false
}
