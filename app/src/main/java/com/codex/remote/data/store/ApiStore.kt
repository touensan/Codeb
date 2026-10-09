package com.codex.remote.data.store

import com.codex.remote.R
import com.codex.remote.ui.uiText

import android.content.Context
import com.codex.remote.data.security.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.JsonArray

@Serializable
enum class ApiProtocol { CHAT_COMPLETIONS, RESPONSES }

@Serializable
data class ApiModel(val id: String, val efforts: List<String> = emptyList(), val effortsReported: Boolean = false, val test: ApiModelTest? = null)

@Serializable
data class ApiModelTest(val outcome: String, val detail: String, val checkedAt: Long = System.currentTimeMillis())

@Serializable
data class ApiProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val encryptedKey: String,
    val protocol: ApiProtocol = ApiProtocol.CHAT_COMPLETIONS,
    val models: List<ApiModel> = emptyList(),
    val selectedModel: String = "",
    val effort: String = "",
)

@Serializable
data class LocalMessage(val id: String = UUID.randomUUID().toString(), val role: String, val text: String,
    val attachments: List<LocalAttachment> = emptyList(), val completedAt: Long? = null)

@Serializable
data class LocalAttachment(val id: String = UUID.randomUUID().toString(), val name: String, val kind: String, val data: String)

@Serializable
data class LocalConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = uiText(R.string.ui_259, "New chat"),
    val workspace: Boolean = false,
    val providerId: String = "",
    val model: String = "",
    val effort: String? = null,
    val messages: List<LocalMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = updatedAt,
    val archived: Boolean = false,
    val draft: String = "",
    val attachments: List<LocalAttachment> = emptyList(),
    val compactedInput: JsonArray? = null,
    val compactedCount: Int = 0,
    val compactedProvider: String = "",
    val compactedModel: String = "",
    val revision: Long = 0,
)

class ApiStore(context: Context) {
    private val storage = LocalStore(context)
    private val providersFile = File(context.filesDir, "api-providers.json")
    private val cipher = SecretCipher()
    fun providers(): List<ApiProvider> = storage.read(providersFile, ListSerializer(ApiProvider.serializer())).orEmpty()
    fun saveProviders(providers: List<ApiProvider>) = storage.write(providersFile, ListSerializer(ApiProvider.serializer()), providers)
    fun encryptKey(key: String): String = cipher.encrypt(key)
    fun key(provider: ApiProvider): String = cipher.decrypt(provider.encryptedKey)

    fun conversationFile(id: String, workspace: Boolean): File =
        if (workspace) File(File(storage.workspaceRoot, safeKey(id)), "conversation.json")
        else File(File(storage.cacheRoot, "chats"), "${safeKey(id)}.json")

    fun save(conversation: LocalConversation, expectedGeneration: Long = storage.cacheGeneration) = storage.locked {
        if (!conversation.workspace && expectedGeneration != storage.cacheGeneration) return@locked
        val file = conversationFile(conversation.id, conversation.workspace)
        val existing = storage.read(file, LocalConversation.serializer())
        if (existing == null && conversation.messages.isEmpty() && conversation.draft.isBlank() && conversation.attachments.isEmpty() && !hasArtifacts(conversation)) return@locked
        if (existing != null && existing.revision > conversation.revision) return@locked
        storage.write(file, LocalConversation.serializer(), conversation)
    }

    fun conversation(id: String, workspace: Boolean): LocalConversation? =
        storage.read(conversationFile(id, workspace), LocalConversation.serializer())

    private fun hasArtifacts(conversation: LocalConversation): Boolean = conversation.workspace &&
        File(File(storage.workspaceRoot, safeKey(conversation.id)), "files").walkTopDown().any { it.isFile }

    fun list(workspace: Boolean): List<LocalConversation> {
        val root = if (workspace) storage.workspaceRoot else File(storage.cacheRoot, "chats")
        if (!root.exists()) return emptyList()
        return root.walkTopDown().filter { it.isFile }
            .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }
            .filter { it.name == "conversation.json" || !workspace && it.extension == "json" }.distinctBy { it.path }
            .mapNotNull { storage.read(it, LocalConversation.serializer()) }
            .filter { conversation -> conversation.messages.any { it.role == "user" && (it.text.isNotBlank() || it.attachments.isNotEmpty()) } || hasArtifacts(conversation) }
            .map { conversation ->
                val firstPrompt = conversation.messages.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text
                if (firstPrompt != null && conversation.title in setOf("New chat", "新聊天", "新しいチャット")) conversation.copy(title = firstPrompt.trim().take(80)) else conversation
            }.toList().sortedByDescending { it.updatedAt }
    }

    fun artifacts(id: String): File = File(File(storage.workspaceRoot, safeKey(id)), "files").apply {
        check(isDirectory || mkdirs()) { uiText(R.string.ui_343, "Cannot create workspace") }
    }

    fun promote(conversation: LocalConversation): LocalConversation {
        val promoted = conversation.copy(workspace = true)
        save(promoted)
        // Keep the cached original until the user explicitly clears it.
        return promoted
    }
}

internal fun validateApiBaseUrl(raw: String): String {
    val uri = URI(raw.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        uiText(R.string.ui_344, "Use an HTTPS base URL without credentials, query or fragment")
    }
    return uri.toASCIIString().trimEnd('/')
}
