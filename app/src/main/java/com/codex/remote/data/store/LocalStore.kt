package com.codex.remote.data.store

import android.content.Context
import android.util.AtomicFile
import com.codex.remote.R
import com.codex.remote.ui.uiText
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RemoteThreadSession
import com.codex.remote.domain.mergeTimelineHistory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class RemoteIndex(
    val threads: List<RemoteThread> = emptyList(),
    val selectedThreadId: String? = null,
    val selectedProjectPath: String? = null,
)

class LocalStore(context: Context) {
    companion object {
        private val storageLock = Any()
        @Volatile private var generation = 0L
    }
    // ponytail: atomic files avoid a new database dependency; large opened histories
    // are rewritten in O(n), under one process lock. Switch to indexed SQLite rows
    // if write contention or history size warrants it.
    val cacheRoot = File(context.filesDir, "local-cache")
    val workspaceRoot = File(context.filesDir, "workspaces")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val cacheGeneration: Long get() = generation
    fun <T> locked(block: () -> T): T = synchronized(storageLock, block)

    fun index(host: String): RemoteIndex? = read(file(host, "index"), RemoteIndex.serializer())
    fun session(host: String, thread: String): RemoteThreadSession? =
        read(file(host, thread), RemoteThreadSession.serializer())

    fun saveRemote(state: AppUiState, expectedGeneration: Long = cacheGeneration) = synchronized(storageLock) {
        if (expectedGeneration != generation) return@synchronized
        val host = state.activeConnection?.id ?: return@synchronized
        write(file(host, "index"), RemoteIndex.serializer(), RemoteIndex(
            state.threads, state.selectedThreadId, state.selectedProjectPath,
        ))
        val thread = state.selectedThreadId ?: return@synchronized
        if (state.timeline.isEmpty() && state.isBusy) return@synchronized
        val old = session(host, thread)
        write(file(host, thread), RemoteThreadSession.serializer(), RemoteThreadSession(
            timeline = mergeTimelineHistory(old?.timeline.orEmpty(), state.timeline),
            model = state.selectedModel,
            reasoningEffort = state.selectedReasoningEffort,
            serviceTier = state.selectedServiceTier,
            cwd = state.selectedProjectPath.orEmpty(),
            olderHistoryCursor = state.olderHistoryCursor,
        ))
    }

    fun draft(host: String, thread: String): String = synchronized(storageLock) {
        val target = file(host, "draft-$thread")
        if (!target.exists() && !File(target.path + ".bak").exists()) ""
        else AtomicFile(target).openRead().use { it.bufferedReader().readText() }
    }

    fun saveDraft(host: String, thread: String, text: String) = atomicWrite(file(host, "draft-$thread"), text.toByteArray())

    fun clearCache() = synchronized(storageLock) {
        check(!cacheRoot.exists() || cacheRoot.deleteRecursively()) { uiText(R.string.cache_clear_failed, "Could not clear cache") }
        generation++
    }

    fun cacheBytes(): Long = cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun file(host: String, key: String) = File(File(cacheRoot, "remote/${safeKey(host)}"), "${safeKey(key)}.json")

    fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? = synchronized(storageLock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) null
        else AtomicFile(file).openRead().use { json.decodeFromString(serializer, it.bufferedReader().readText()) }
    }

    fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        atomicWrite(file, json.encodeToString(serializer, value).toByteArray())

    fun atomicWrite(file: File, bytes: ByteArray) = synchronized(storageLock) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { uiText(R.string.storage_directory_failed, "Cannot create storage directory") }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}

internal fun safeKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
