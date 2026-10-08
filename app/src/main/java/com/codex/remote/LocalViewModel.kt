package com.codex.remote

import com.codex.remote.R
import com.codex.remote.ui.uiText

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.remote.data.api.OpenAiClient
import com.codex.remote.data.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class LocalUiState(
    val providers: List<ApiProvider> = emptyList(),
    val providerId: String = "",
    val conversations: List<LocalConversation> = emptyList(),
    val selected: LocalConversation? = null,
    val files: List<File> = emptyList(),
    val sending: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val cacheBytes: Long = 0,
)

class LocalViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ApiStore(application)
    private val storage = LocalStore(application)
    private val client = OpenAiClient()
    private val preferences = application.getSharedPreferences("local-ui", 0)
    private val _state = MutableStateFlow(LocalUiState(providerId = preferences.getString("provider", "").orEmpty()))
    val state = _state.asStateFlow()
    private var requestJob: Job? = null
    private var workspace = false

    init {
        viewModelScope.launch {
            try {
                val providers = withContext(Dispatchers.IO) { store.providers() }
                updateState { it.copy(providers = providers, providerId = it.providerId.takeIf { id -> providers.any { p -> p.id == id } } ?: providers.firstOrNull()?.id.orEmpty()) }
                refresh()
            } catch (error: Exception) { failure(error) }
        }
        viewModelScope.launch {
            state.map { it.selected to storage.cacheGeneration }.distinctUntilChanged().collect { (conversation, generation) ->
                if (conversation != null) try { withContext(Dispatchers.IO) { store.save(conversation, generation) } }
                catch (error: Exception) { failure(error) }
                if (conversation != null) preferences.edit().putString(if (conversation.workspace) "selected-work" else "selected-chat", conversation.id).apply()
            }
        }
    }

    fun mode(isWorkspace: Boolean) {
        if (_state.value.sending) return
        if (workspace == isWorkspace) return
        preserveSelection()
        workspace = isWorkspace
        updateState { it.copy(selected = null, files = emptyList()) }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        try {
            val mode = workspace
            val conversations = withContext(Dispatchers.IO) { store.list(mode) }
            val bytes = withContext(Dispatchers.IO) { storage.cacheBytes() }
            if (workspace == mode) {
                updateState { state -> state.copy(conversations = conversations, cacheBytes = bytes,
                    selected = state.selected ?: conversations.firstOrNull { it.id == preferences.getString(if (mode) "selected-work" else "selected-chat", "") }) }
                loadFiles()
            }
        } catch (error: Exception) { failure(error) }
    }

    fun newConversation(title: String = "") {
        if (_state.value.sending) return
        preserveSelection()
        updateState { it.copy(selected = LocalConversation(title = title.ifBlank { uiText(R.string.ui_259, "New chat") }, workspace = workspace, providerId = it.providerId), files = emptyList(), error = null) }
    }

    fun open(conversation: LocalConversation) {
        if (_state.value.sending) return
        preserveSelection()
        updateState { it.copy(selected = conversation, providerId = conversation.providerId.ifBlank { it.providerId }, error = null) }
        loadFiles()
    }

    fun provider(id: String) {
        if (_state.value.sending) return
        preferences.edit().putString("provider", id).apply()
        updateState { it.copy(providerId = id, selected = it.selected?.copy(providerId = id)) }
    }

    fun saveProvider(original: ApiProvider?, name: String, baseUrl: String, key: String, protocol: ApiProtocol, model: String, efforts: String, effort: String, onSaved: () -> Unit) = viewModelScope.launch {
        try {
            require(name.isNotBlank()) { uiText(R.string.ui_289, "Provider name is required") }
            val url = validateApiBaseUrl(baseUrl)
            require(model.isNotBlank()) { uiText(R.string.ui_290, "Choose a model ID") }
            require(key.isNotBlank() || original?.encryptedKey?.isNotBlank() == true) { uiText(R.string.ui_291, "API key is required") }
            val saved = withContext(Dispatchers.IO) {
                ApiProvider(
                    id = original?.id ?: UUID.randomUUID().toString(), name = name.trim(), baseUrl = url,
                    encryptedKey = if (key.isNotBlank()) store.encryptKey(key) else original!!.encryptedKey,
                    protocol = protocol,
                    models = original?.models.orEmpty().filterNot { it.id == model.trim() } + ApiModel(model.trim(), efforts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()),
                    selectedModel = model.trim(), effort = effort.trim(),
                )
            }
            persistProviders(_state.value.providers.filterNot { it.id == saved.id } + saved)
            provider(saved.id)
            onSaved()
        } catch (error: Exception) { failure(error) }
    }

    fun deleteProvider(provider: ApiProvider) = viewModelScope.launch {
        if (_state.value.sending) return@launch
        try {
            val providers = _state.value.providers.filterNot { it.id == provider.id }
            persistProviders(providers)
            if (_state.value.providerId == provider.id) provider(providers.firstOrNull()?.id.orEmpty())
        } catch (error: Exception) { failure(error) }
    }

    fun models(provider: ApiProvider) = viewModelScope.launch {
        updateState { it.copy(loading = true, error = null) }
        try {
            val models = client.models(provider, withContext(Dispatchers.IO) { store.key(provider) })
            val merged = models.map { fetched ->
                if (fetched.efforts.isNotEmpty()) fetched else fetched.copy(efforts = provider.models.firstOrNull { it.id == fetched.id }?.efforts.orEmpty())
            } + provider.models.filter { custom -> models.none { it.id == custom.id } }
            persistProviders(_state.value.providers.map { if (it.id == provider.id) it.copy(models = merged) else it })
        } catch (error: Exception) { failure(error) }
        finally { updateState { it.copy(loading = false) } }
    }

    fun selectModel(model: String, effort: String = "") = viewModelScope.launch {
        if (_state.value.sending) return@launch
        try { persistProviders(_state.value.providers.map { if (it.id == _state.value.providerId) it.copy(selectedModel = model, effort = effort) else it }) }
        catch (error: Exception) { failure(error) }
    }

    private suspend fun persistProviders(providers: List<ApiProvider>) {
        withContext(Dispatchers.IO) { store.saveProviders(providers) }
        updateState { it.copy(providers = providers) }
    }

    fun draft(text: String) {
        if (_state.value.selected == null) newConversation()
        updateState { it.copy(selected = it.selected?.copy(draft = text)) }
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.sending) return
        val conversation = snapshot.selected ?: return
        val prompt = conversation.draft.trim()
        if (prompt.isEmpty()) return
        val provider = snapshot.providers.firstOrNull { it.id == snapshot.providerId } ?: run {
            updateState { it.copy(error = uiText(R.string.ui_292, "Add an API provider first")) }; return
        }
        val reply = LocalMessage(role = "assistant", text = "")
        val outgoing = conversation.copy(
            revision = conversation.revision + 1,
            title = if (conversation.messages.isEmpty()) prompt.take(80) else conversation.title,
            providerId = provider.id,
            messages = conversation.messages + LocalMessage(role = "user", text = prompt) + reply,
            updatedAt = System.currentTimeMillis(), draft = "",
        )
        updateState { it.copy(selected = outgoing, sending = true, error = null) }
        requestJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(outgoing) }
                val useCompact = outgoing.compactedProvider == provider.id && outgoing.compactedModel == provider.selectedModel && provider.protocol == ApiProtocol.RESPONSES
                val messages = outgoing.messages.dropLast(1).drop(if (useCompact) outgoing.compactedCount else 0)
                client.stream(provider, withContext(Dispatchers.IO) { store.key(provider) }, messages, if (useCompact) outgoing.compactedInput else null) { delta ->
                    updateState { state ->
                        if (state.selected?.id != outgoing.id) state else state.copy(selected = state.selected.copy(
                            messages = state.selected.messages.map { if (it.id == reply.id) it.copy(text = it.text + delta) else it },
                        ))
                    }
                }
            } catch (error: CancellationException) {
                updateState { it.copy(error = uiText(R.string.ui_350, "Stopped; partial reply was kept")) }
                throw error
            } catch (error: Exception) { failure(error) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    _state.value.selected?.takeIf { it.id == outgoing.id }?.let { saved ->
                        runCatching { store.save(saved) }.onFailure { failure(it) }
                    }
                }
                updateState { it.copy(sending = false) }
                refresh()
            }
        }
    }

    fun stop() { requestJob?.cancel() }
    fun compact() {
        val snapshot = _state.value
        val conversation = snapshot.selected ?: return
        val provider = snapshot.providers.firstOrNull { it.id == snapshot.providerId } ?: return
        if (snapshot.sending || conversation.messages.isEmpty()) return
        updateState { it.copy(sending = true, error = null) }
        requestJob = viewModelScope.launch {
            try {
                val useCompact = conversation.compactedProvider == provider.id && conversation.compactedModel == provider.selectedModel
                val output = client.compact(provider, withContext(Dispatchers.IO) { store.key(provider) },
                    conversation.messages.drop(if (useCompact) conversation.compactedCount else 0), if (useCompact) conversation.compactedInput else null)
                updateState { it.copy(selected = it.selected?.copy(compactedInput = output, compactedCount = conversation.messages.size, compactedProvider = provider.id, compactedModel = provider.selectedModel)) }
            } catch (error: Exception) { failure(error) }
            finally { updateState { it.copy(sending = false) } }
        }
    }

    fun generateImage() {
        val snapshot = _state.value
        val conversation = snapshot.selected?.takeIf { it.workspace && it.draft.isNotBlank() } ?: return
        val provider = snapshot.providers.firstOrNull { it.id == snapshot.providerId } ?: return
        if (snapshot.sending) return
        updateState { it.copy(sending = true, error = null) }
        requestJob = viewModelScope.launch {
            try {
                val images = client.generateImage(provider, withContext(Dispatchers.IO) { store.key(provider) }, conversation.draft)
                val names = withContext(Dispatchers.IO) {
                    images.map { bytes ->
                        val file = File(store.artifacts(conversation.id), "image-${UUID.randomUUID()}.png")
                        storage.atomicWrite(file, bytes)
                        file.name
                    }
                }
                updateState { it.copy(selected = it.selected?.copy(
                    messages = conversation.messages + LocalMessage(role = "user", text = conversation.draft) + LocalMessage(role = "assistant", text = names.joinToString("\n")),
                    draft = it.selected.draft.takeUnless { draft -> draft == conversation.draft }.orEmpty(), updatedAt = System.currentTimeMillis(),
                )) }
                loadFiles()
                refresh()
            } catch (error: Exception) { failure(error) }
            finally { updateState { it.copy(sending = false) } }
        }
    }
    fun dismissError() = updateState { it.copy(error = null) }

    fun archive(conversation: LocalConversation) = viewModelScope.launch {
        try {
            val latest = _state.value.selected?.takeIf { it.id == conversation.id } ?: conversation
            val saved = latest.copy(archived = !conversation.archived, revision = latest.revision + 1)
            withContext(Dispatchers.IO) { store.save(saved) }
            updateState { it.copy(selected = it.selected?.takeUnless { current -> current.id == saved.id }) }
            refresh()
        } catch (error: Exception) { failure(error) }
    }

    fun promote() = viewModelScope.launch {
        val conversation = _state.value.selected ?: return@launch
        if (_state.value.sending) return@launch
        try {
            val saved = withContext(Dispatchers.IO) { store.promote(conversation) }
            workspace = true
            updateState { it.copy(selected = saved) }
            loadFiles()
            refresh()
        } catch (error: Exception) { failure(error) }
    }

    fun clearCache() = viewModelScope.launch {
        if (_state.value.sending) return@launch
        try {
            // The workspaces directory is never part of this deletion.
            updateState { it.copy(selected = it.selected?.takeIf { c -> c.workspace }, cacheBytes = 0) }
            withContext(Dispatchers.IO) { storage.clearCache() }
            refresh()
        } catch (error: Exception) { failure(error) }
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        val conversation = _state.value.selected?.takeIf { it.workspace } ?: return@launch
        try {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "bin"
                val target = File(store.artifacts(conversation.id), "${UUID.randomUUID()}.$extension")
                try {
                    resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: error(uiText(R.string.ui_293, "Unable to read selected file"))
                } catch (error: Throwable) { target.delete(); throw error }
            }
            loadFiles()
        } catch (error: Exception) { failure(error) }
    }

    fun saveReply() = viewModelScope.launch {
        val conversation = _state.value.selected?.takeIf { it.workspace } ?: return@launch
        val message = conversation.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return@launch
        try {
            withContext(Dispatchers.IO) { storage.atomicWrite(File(store.artifacts(conversation.id), "reply-${message.id}.md"), message.text.toByteArray()) }
            loadFiles()
        } catch (error: Exception) { failure(error) }
    }

    fun export(file: File, target: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                require(file.canonicalPath.startsWith(storage.workspaceRoot.canonicalPath + File.separator))
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error(uiText(R.string.ui_294, "Unable to export file"))
            }
        } catch (error: Exception) { failure(error) }
    }

    private fun loadFiles() = viewModelScope.launch {
        val conversation = _state.value.selected ?: return@launch
        val files = if (conversation.workspace) withContext(Dispatchers.IO) { store.artifacts(conversation.id).listFiles()?.filter { it.isFile }.orEmpty() } else emptyList()
        if (_state.value.selected?.id == conversation.id) updateState { it.copy(files = files) }
    }

    private fun preserveSelection() {
        val snapshot = _state.value.selected ?: return
        val generation = storage.cacheGeneration
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(snapshot, generation) }.onFailure(::failure) }
    }

    private fun updateState(transform: (LocalUiState) -> LocalUiState) = _state.update { current ->
        val next = transform(current)
        if (next.selected != null && next.selected != current.selected) next.copy(selected = next.selected.copy(
            revision = maxOf(next.selected.revision, current.selected?.revision ?: 0) + 1,
        )) else next
    }

    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        com.codex.remote.data.diagnostics.DebugApi.record("local_error", error)
        updateState { it.copy(error = error.message ?: error.javaClass.simpleName) }
    }
}
