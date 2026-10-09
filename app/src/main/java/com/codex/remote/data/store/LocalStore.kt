package com.codex.remote.data.store

import android.content.Context
import android.util.AtomicFile
import com.codex.remote.R
import com.codex.remote.ui.uiText
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ComposerDefaults
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
    val projectPaths: List<String> = emptyList(),
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
    private val composerPreferences = context.getSharedPreferences("composer-defaults", Context.MODE_PRIVATE)
    val cacheGeneration: Long get() = generation
    fun <T> locked(block: () -> T): T = synchronized(storageLock, block)

    fun composerDefaults() = ComposerDefaults(
        model = composerPreferences.getString("model", null),
        reasoningEffort = composerPreferences.getString("reasoning", null),
        serviceTier = composerPreferences.getString("tier", null),
        isConfigured = composerPreferences.contains("model"),
    )

    fun saveComposerDefaults(defaults: ComposerDefaults) {
        composerPreferences.edit().putString("model", defaults.model)
            .putString("reasoning", defaults.reasoningEffort).putString("tier", defaults.serviceTier).apply()
    }

    fun index(host: String): RemoteIndex? = read(file(host, "index"), RemoteIndex.serializer())
    fun session(host: String, thread: String): RemoteThreadSession? =
        read(file(host, thread), RemoteThreadSession.serializer())

    fun saveRemote(state: AppUiState, expectedGeneration: Long = cacheGeneration, saveIndex: Boolean = true) = synchronized(storageLock) {
        if (expectedGeneration != generation) return@synchronized
        val host = state.activeConnection?.id ?: return@synchronized
        if (saveIndex) write(file(host, "index"), RemoteIndex.serializer(), RemoteIndex(
            state.threads, state.selectedThreadId, state.selectedProjectPath, state.projects.map { it.path },
        ))
        val thread = state.selectedThreadId ?: return@synchronized
        if (state.timeline.isEmpty() && state.isBusy) return@synchronized
        val old = session(host, thread)
        val currentIds = state.timeline.mapTo(mutableSetOf(), { it.id })
        write(file(host, thread), RemoteThreadSession.serializer(), RemoteThreadSession(
            // Confirmed live items replace optimistic IDs; do not resurrect them from disk.
            timeline = mergeTimelineHistory(old?.timeline.orEmpty().filter { !it.id.startsWith("local-") || it.id in currentIds }, state.timeline),
            model = state.selectedModel,
            reasoningEffort = state.selectedReasoningEffort,
            serviceTier = state.selectedServiceTier,
            cwd = state.selectedProjectPath.orEmpty(),
            olderHistoryCursor = state.olderHistoryCursor,
            activeTurnId = state.activeTurnId,
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
