package com.codex.remote

import com.codex.remote.R
import com.codex.remote.ui.uiText

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.remote.data.api.OpenAiClient
import com.codex.remote.data.api.ReasoningCatalog
import com.codex.remote.data.api.ReasoningCatalogStore
import com.codex.remote.data.api.CloudConfig
import com.codex.remote.data.api.CloudConfigStore
import com.codex.remote.data.api.resolved
import com.codex.remote.data.api.readLimited
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val runningConversationIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val cacheBytes: Long = 0,
    val catalog: ReasoningCatalog? = null,
    val testingModels: Map<String, String> = emptyMap(),
    val loadingModelsFor: String? = null,
    val modelFetchResults: Map<String, String> = emptyMap(),
    val cloud: CloudConfig = CloudConfig(),
    val checkingCloud: Boolean = false,
    val cloudError: String? = null,
    val cloudCheckedAt: Long = 0,
) {
    val sending: Boolean get() = selected?.id in runningConversationIds
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class LocalViewModel @JvmOverloads constructor(application: Application, private val client: OpenAiClient = OpenAiClient()) : AndroidViewModel(application) {
    private val store = ApiStore(application)
    private val storage = LocalStore(application)
    private val catalogStore = ReasoningCatalogStore(application)
    private val cloudStore = CloudConfigStore(application)
    private val providerWrites = Mutex()
    private val preferences = application.getSharedPreferences("local-ui", 0)
    private val _state = MutableStateFlow(LocalUiState(providerId = preferences.getString("provider", "").orEmpty()))
    val state = _state.asStateFlow()
    suspend fun readArtifact(path: String): ByteArray {
        val conversation = _state.value.selected ?: error("Select a conversation first")
        require(conversation.workspace) { "This file is not in a saved workspace" }
        return withContext(Dispatchers.IO) {
        val root = store.artifacts(conversation.id).canonicalFile
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && file.isFile && file.length() in 1..64L * 1024 * 1024) { "File must be inside the workspace and at most 64 MB" }
        file.inputStream().use { it.readLimited(64 * 1024 * 1024) }
        }
    }
    private val requestJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val modelTests = mutableMapOf<String, Job>()
    private var workspace = false

    init {
        updateState { it.copy(cloud = cloudStore.cached()) }
        refreshCloud()
        updateState { it.copy(catalog = catalogStore.cached()) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { catalogStore.refresh() } }
                .onSuccess { catalog -> updateState { it.copy(catalog = catalog) } }
        }
        viewModelScope.launch {
            try {
                val providers = withContext(Dispatchers.IO) { store.providers() }
                updateState { it.copy(providers = providers, providerId = it.providerId.takeIf { id -> providers.any { p -> p.id == id && p.enabled } } ?: providers.firstOrNull { p -> p.enabled }?.id.orEmpty()) }
                refresh()
            } catch (error: Exception) { failure(error) }
        }
        viewModelScope.launch {
            state.map { snapshot -> (snapshot.conversations.filter { it.id in snapshot.runningConversationIds } + listOfNotNull(snapshot.selected)).distinctBy { it.id } to storage.cacheGeneration }
                .distinctUntilChanged().sample(200).collect { (conversations, generation) ->
                try { withContext(Dispatchers.IO) { conversations.forEach { store.save(it, generation) } } }
                catch (error: Exception) { failure(error) }
                val conversation = _state.value.selected
                if (conversation != null) preferences.edit()
                    .putString("selected", conversation.id).putBoolean("selected-workspace", conversation.workspace)
                    .putString(if (conversation.workspace) "selected-work" else "selected-chat", conversation.id).apply()
            }
        }
    }

    fun refreshCloud(force: Boolean = false) = viewModelScope.launch {
        if (_state.value.checkingCloud) return@launch
        updateState { it.copy(checkingCloud = true, cloudError = null) }
        try {
            val config = withContext(Dispatchers.IO) { cloudStore.refresh(force) }
            updateState { it.copy(cloud = config, checkingCloud = false, cloudCheckedAt = System.currentTimeMillis()) }
            if (config.reasoningRevision > (_state.value.catalog?.revision ?: 0)) {
                runCatching { withContext(Dispatchers.IO) { catalogStore.refresh(force = true) } }
                    .onSuccess { catalog -> updateState { it.copy(catalog = catalog) } }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            updateState { it.copy(checkingCloud = false, cloudError = error.message, cloud = cloudStore.cached()) }
        }
    }

    fun refresh() = viewModelScope.launch {
        try {
            val conversations = withContext(Dispatchers.IO) { (store.list(false) + store.list(true)).sortedByDescending { it.lastOpenedAt } }
            val diskIds = conversations.mapTo(mutableSetOf()) { it.id }
            val selected = withContext(Dispatchers.IO) {
                val id = preferences.getString("selected", null)
                (if (id != null) store.conversation(id, preferences.getBoolean("selected-workspace", false)) else
                    listOfNotNull(preferences.getString("selected-chat", null)?.let { store.conversation(it, false) },
                        preferences.getString("selected-work", null)?.let { store.conversation(it, true) }).maxByOrNull { it.updatedAt })
                    ?.takeUnless { it.archived } ?: conversations.firstOrNull { !it.archived }
            }
            val bytes = withContext(Dispatchers.IO) { storage.cacheBytes() }
            updateState { state -> state.copy(conversations = (conversations.filterNot { it.id in state.runningConversationIds }.map { disk ->
                state.conversations.firstOrNull { it.id == disk.id && it.revision > disk.revision } ?: disk
            } + state.conversations.filter { it.id in state.runningConversationIds } + listOfNotNull(state.selected?.takeIf { it.id !in diskIds && it.id !in state.runningConversationIds })).sortedByDescending { it.lastOpenedAt }, cacheBytes = bytes,
                selected = state.selected ?: selected?.let { saved -> (conversations.firstOrNull { it.id == saved.id } ?: saved).copy(
                    lastOpenedAt = maxOf(System.currentTimeMillis(), (conversations.firstOrNull()?.lastOpenedAt ?: 0L) + 1)) },
                providerId = if (state.selected == null) selected?.providerId?.takeIf(String::isNotBlank) ?: state.providerId else state.providerId) }
            loadFiles()
        } catch (error: Exception) { failure(error) }
    }

    fun newConversation(title: String = "") {
        preserveSelection()
        workspace = false
        updateState { it.copy(selected = LocalConversation(title = title.ifBlank { uiText(R.string.ui_259, "New chat") }, providerId = it.providerId, lastOpenedAt = nextOpenedAt()), files = emptyList(), error = null) }
    }

    fun conversationFiles(): com.codex.remote.data.local.PhoneFiles {
        if (_state.value.selected == null) newConversation()
        return com.codex.remote.data.local.PhoneFiles(getApplication(),store.artifacts(_state.value.selected!!.id).path)
    }

    fun open(conversation: LocalConversation) {
        preserveSelection()
        workspace = conversation.workspace
        updateState { state ->
            val latest = state.conversations.firstOrNull { it.id == conversation.id && it.revision > conversation.revision } ?: conversation
            state.copy(selected = latest.copy(lastOpenedAt = nextOpenedAt()), providerId = latest.providerId.ifBlank { state.providerId }, error = null)
        }
        loadFiles()
    }

    fun provider(id: String) {
        if (id.isNotEmpty() && _state.value.providers.none { it.id == id && it.enabled }) return
        preferences.edit().putString("provider", id).apply()
        updateState { it.copy(providerId = id, selected = it.selected?.copy(providerId = id, model = "", effort = null)) }
    }

    fun enableProvider(id: String, enabled: Boolean) = viewModelScope.launch {
        if (_state.value.sending) return@launch
        try {
            if (!enabled) {stopModelTests(id);com.codex.remote.data.local.PhoneControl.stop()}
            mutateProviders { all -> all.map { if (it.id == id) it.copy(enabled = enabled) else it } }
            if (!enabled && _state.value.providerId == id) provider(_state.value.providers.firstOrNull { it.enabled }?.id.orEmpty())
        } catch (error: Exception) { failure(error) }
    }

    fun saveProvider(original: ApiProvider?, name: String, baseUrl: String, key: String, protocol: ApiProtocol, models: List<ApiModel>, model: String, effort: String, onSaved: (ApiProvider) -> Unit) = viewModelScope.launch {
        try {
            updateState { it.copy(error = null) }
            require(name.isNotBlank()) { uiText(R.string.ui_289, "Provider name is required") }
            val url = validateApiBaseUrl(baseUrl)
            require(model.isNotBlank()) { uiText(R.string.ui_290, "Choose a model ID") }
            require(models.isNotEmpty() && models.all { it.id.isNotBlank() && it.id.length <= 256 && it.id.none(Char::isISOControl) } && models.map { it.id }.distinct().size == models.size && models.any { it.id == model }) { uiText(R.string.model_ids_invalid, "Invalid model IDs") }
            require(models.all { it.efforts.size <= 12 && it.efforts.distinct() == it.efforts && it.efforts.all { level -> com.codex.remote.data.api.validEffortId(level) } }) { uiText(R.string.model_ids_invalid, "Invalid model IDs") }
            require(key.isNotBlank() || original?.encryptedKey?.isNotBlank() == true) { uiText(R.string.ui_291, "API key is required") }
            val saved = withContext(Dispatchers.IO) {
                ApiProvider(
                    id = original?.id ?: UUID.randomUUID().toString(), name = name.trim(), baseUrl = url,
                    encryptedKey = if (key.isNotBlank()) store.encryptKey(key) else original!!.encryptedKey,
                    protocol = protocol,
                    models = models.map { configured -> configured.copy(test = configured.test.takeIf { original?.baseUrl == url && original.protocol == protocol && key.isBlank() }) },
                    selectedModel = model.trim(), effort = effort.trim(),
                    enabled = original?.enabled ?: true,
                )
            }
            mutateProviders { it.filterNot { p -> p.id == saved.id } + saved }
            com.codex.remote.data.local.PhoneControl.stop()
            if (_state.value.providerId != saved.id) provider(saved.id)
            onSaved(saved)
        } catch (error: Exception) { failure(error) }
    }

    suspend fun fetchModels(original: ApiProvider?, baseUrl: String, key: String, protocol: ApiProtocol): List<ApiModel> {
        require(key.isNotBlank() || original?.encryptedKey?.isNotBlank() == true) { uiText(R.string.ui_291, "API key is required") }
        val provider = ApiProvider(name = "", baseUrl = validateApiBaseUrl(baseUrl), encryptedKey = original?.encryptedKey.orEmpty(), protocol = protocol)
        val secret = if (key.isNotBlank()) key else withContext(Dispatchers.IO) { store.key(provider) }
        return client.models(provider, secret).also { require(it.isNotEmpty()) { uiText(R.string.model_fetch_empty, "Upstream returned no models; your list was kept") } }
    }

    fun testModels(provider: ApiProvider, ids: List<String> = provider.models.map { it.id }) {
        if (modelTests[provider.id]?.isActive == true) return
        modelTests[provider.id] = viewModelScope.launch {
            try {
                val secret = withContext(Dispatchers.IO) { store.key(provider) }
                ids.forEach { id ->
                    ensureActive()
                    updateState { it.copy(testingModels = it.testingModels + (provider.id to id)) }
                    val result = try { withTimeout(20_000) { client.testModel(provider.copy(selectedModel = id, effort = ""), secret) } }
                    catch (error: TimeoutCancellationException) { ApiModelTest("failed", uiText(R.string.model_probe_timeout, "Timed out after 20 seconds; retry is available")) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { ApiModelTest("failed", error.message ?: uiText(R.string.model_probe_unknown, "Request failed")) }
                    mutateProviders { providers -> providers.map { latest ->
                        if (latest.id != provider.id || latest.baseUrl != provider.baseUrl || latest.protocol != provider.protocol || latest.encryptedKey != provider.encryptedKey) latest
                        else latest.copy(models = latest.models.map { if (it.id == id) it.copy(test = result) else it })
                    } }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
            finally { updateState { it.copy(testingModels = it.testingModels - provider.id) } }
        }
    }

    fun stopModelTests(id: String) { modelTests[id]?.cancel() }

    fun deleteProvider(provider: ApiProvider) = viewModelScope.launch {
        if (_state.value.sending) return@launch
        stopModelTests(provider.id)
        try {
            val providers = _state.value.providers.filterNot { it.id == provider.id }
            mutateProviders { it.filterNot { p -> p.id == provider.id } }
            if (_state.value.providerId == provider.id) provider(providers.firstOrNull()?.id.orEmpty())
        } catch (error: Exception) { failure(error) }
    }

    fun models(provider: ApiProvider) = viewModelScope.launch {
        if (_state.value.loadingModelsFor != null) return@launch
        updateState { it.copy(loading = true, loadingModelsFor = provider.id, modelFetchResults = it.modelFetchResults - provider.id, error = null) }
        try {
            val models = withTimeout(20_000) { fetchModels(provider, provider.baseUrl, "", provider.protocol) }
            mutateProviders { providers -> providers.map { latest ->
                if (latest.id != provider.id) latest else latest.copy(models = models.map { fetched ->
                    val previous = latest.models.firstOrNull { it.id == fetched.id }
                    if (fetched.efforts.isNotEmpty() || fetched.effortsReported) fetched.copy(test = previous?.test) else previous ?: fetched
                } + latest.models.filter { custom -> models.none { it.id == custom.id } })
            } }
            updateState { it.copy(modelFetchResults = it.modelFetchResults + (provider.id to uiText(R.string.models_refreshed, "Fetched %1\$d models", models.size))) }
        } catch (error: TimeoutCancellationException) { updateState { it.copy(modelFetchResults = it.modelFetchResults + (provider.id to uiText(R.string.model_probe_timeout, "Timed out; retry is available"))) } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { updateState { it.copy(modelFetchResults = it.modelFetchResults + (provider.id to (error.message ?: uiText(R.string.model_probe_unknown, "Request failed")))) } }
        finally { updateState { it.copy(loading = false, loadingModelsFor = null) } }
    }

    fun selectModel(model: String, effort: String = "") {
        val providerId = _state.value.providerId
        val catalog = _state.value.catalog ?: return
        val provider = _state.value.providers.firstOrNull { it.id == providerId && it.enabled } ?: return
        if (provider.models.none { it.id == model } && _state.value.selected?.model != model) return
        val configured = provider.copy(selectedModel = model, effort = effort).resolved(catalog)
        if (_state.value.selected == null) newConversation()
        updateState { it.copy(selected = it.selected?.copy(model = model, effort = configured.effort)) }
        viewModelScope.launch {
            try { mutateProviders { providers -> providers.map { if (it.id == providerId) it.copy(selectedModel = model, effort = configured.effort) else it } } }
            catch (error: Exception) { failure(error) }
        }
    }

    private suspend fun mutateProviders(transform: (List<ApiProvider>) -> List<ApiProvider>) = providerWrites.withLock {
        val providers = transform(_state.value.providers)
        withContext(Dispatchers.IO) { store.saveProviders(providers) }
        updateState { it.copy(providers = providers) }
    }

    fun draft(text: String) {
        if (_state.value.selected == null) newConversation()
        updateState { it.copy(selected = it.selected?.copy(draft = text)) }
    }

    fun send(steer: Boolean = false) {
        val snapshot = _state.value
        val conversation = snapshot.selected ?: return
        val prompt = conversation.draft.trim()
        if (prompt.isEmpty() && conversation.attachments.isEmpty()) return
        val message = LocalMessage(role = "user", text = prompt, attachments = conversation.attachments)
        if (snapshot.sending && !steer) {
            updateConversation(conversation.id) { it.copy(queuedMessages = it.queuedMessages + message, draft = "", attachments = emptyList()) }
            preserveSelection()
            return
        }
        if (snapshot.sending) stop()
        startReply(conversation.id, message)
    }

    fun deleteQueuedMessage(id: String) {
        val conversation = _state.value.selected ?: return
        updateConversation(conversation.id) { it.copy(queuedMessages = it.queuedMessages.filterNot { message -> message.id == id }) }
        preserveSelection()
    }

    fun steerQueuedMessage(id: String) {
        val conversation = _state.value.selected ?: return
        val message = conversation.queuedMessages.firstOrNull { it.id == id } ?: return
        if (_state.value.sending) stop()
        startReply(conversation.id, message)
    }

    private fun startReply(id: String, message: LocalMessage) {
        val snapshot = _state.value
        val conversation = snapshot.selected?.takeIf { it.id == id } ?: snapshot.conversations.firstOrNull { it.id == id } ?: return
        if (id in snapshot.runningConversationIds) return
        val provider = snapshot.providers.firstOrNull { it.id == conversation.providerId.ifBlank { snapshot.providerId } && it.enabled }?.resolved(catalogStore.cached(), conversation) ?: run {
            updateState { it.copy(error = uiText(R.string.ui_292, "Add an API provider first")) }; return
        }
        val reply = LocalMessage(role = "assistant", text = "")
        val outgoing = conversation.copy(
            revision = conversation.revision + 1,
            title = if (conversation.messages.none { it.role == "user" }) message.text.ifBlank { message.attachments.first().name }.take(80) else conversation.title,
            providerId = provider.id,
            model = provider.selectedModel, effort = provider.effort,
            messages = conversation.messages.filterNot { it.role == "assistant" && it.text.isBlank() } + message + reply,
            updatedAt = System.currentTimeMillis(),
            draft = conversation.draft.takeUnless { it.trim() == message.text && conversation.attachments == message.attachments }.orEmpty(),
            attachments = if (conversation.attachments == message.attachments) emptyList() else conversation.attachments,
            queuedMessages = conversation.queuedMessages.filterNot { it.id == message.id }, queuePaused = false,
        )
        updateConversation(id) { outgoing }
        updateState { it.copy(runningConversationIds = it.runningConversationIds + id, error = null) }
        requestJobs[id] = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            var completed = false
            try {
                withContext(Dispatchers.IO) { store.save(outgoing) }
                val useCompact = outgoing.compactedProvider == provider.id && outgoing.compactedModel == provider.selectedModel && provider.protocol == ApiProtocol.RESPONSES
                val messages = outgoing.messages.dropLast(1).drop(if (useCompact) outgoing.compactedCount else 0)
                client.stream(provider, withContext(Dispatchers.IO) { store.key(provider) }, messages, if (useCompact) outgoing.compactedInput else null) { delta ->
                    if (owner.isActive && requestJobs[id] === owner) updateConversation(id) { it.copy(messages = it.messages.map { message -> if (message.id == reply.id) message.copy(text = message.text + delta) else message }) }
                }
                completed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { if (requestJobs[id] === owner) conversationFailure(id, error) }
            finally {
                if (requestJobs[id] === owner) {
                updateConversation(id) { it.copy(queuePaused = !completed, messages = it.messages.map { message ->
                    if (message.id == reply.id && message.text.isNotBlank()) message.copy(completedAt = System.currentTimeMillis()) else message
                }) }
                finishRequest(id, owner)
                val latest = _state.value.selected?.takeIf { it.id == id } ?: _state.value.conversations.firstOrNull { it.id == id }
                if (completed && latest?.queuePaused == false) latest.queuedMessages.firstOrNull()?.let { startReply(id, it) }
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        val conversation = _state.value.selected ?: return
        val job = requestJobs.remove(conversation.id)
        updateConversation(conversation.id) { it.copy(queuePaused = true, messages = it.messages.map { message ->
            if (message.role == "assistant" && message.completedAt == null && message.text.isNotBlank()) message.copy(completedAt = System.currentTimeMillis()) else message
        }) }
        updateState { it.copy(runningConversationIds = it.runningConversationIds - conversation.id, error = null) }
        // ponytail: old HTTP streams may unwind slowly; only the current job may publish or finish a reply.
        job?.cancel()
        preserveSelection()
    }
    fun compact() {
        val snapshot = _state.value
        val conversation = snapshot.selected ?: return
        val provider = snapshot.providers.firstOrNull { it.id == snapshot.providerId && it.enabled }?.resolved(catalogStore.cached(), conversation) ?: return
        if (snapshot.sending || conversation.messages.isEmpty()) return
        updateState { it.copy(runningConversationIds = it.runningConversationIds + conversation.id, error = null) }
        requestJobs[conversation.id] = viewModelScope.launch {
            try {
                val useCompact = conversation.compactedProvider == provider.id && conversation.compactedModel == provider.selectedModel
                val output = client.compact(provider, withContext(Dispatchers.IO) { store.key(provider) },
                    conversation.messages.drop(if (useCompact) conversation.compactedCount else 0), if (useCompact) conversation.compactedInput else null)
                updateConversation(conversation.id) { it.copy(compactedInput = output, compactedCount = conversation.messages.size, compactedProvider = provider.id, compactedModel = provider.selectedModel) }
            } catch (error: Exception) { conversationFailure(conversation.id, error) }
            finally { finishRequest(conversation.id) }
        }
    }

    fun generateImage() {
        val snapshot = _state.value
        val conversation = snapshot.selected?.takeIf { it.workspace && it.draft.isNotBlank() } ?: return
        val provider = snapshot.providers.firstOrNull { it.id == snapshot.providerId && it.enabled } ?: return
        if (snapshot.sending) return
        updateState { it.copy(runningConversationIds = it.runningConversationIds + conversation.id, error = null) }
        requestJobs[conversation.id] = viewModelScope.launch {
            try {
                val images = client.generateImage(provider, withContext(Dispatchers.IO) { store.key(provider) }, conversation.draft)
                val names = withContext(Dispatchers.IO) {
                    images.map { bytes ->
                        val file = File(store.artifacts(conversation.id), "image-${UUID.randomUUID()}.png")
                        storage.atomicWrite(file, bytes)
                        file.name
                    }
                }
                updateConversation(conversation.id) { it.copy(
                    title = if (conversation.messages.none { message -> message.role == "user" && message.text.isNotBlank() }) conversation.draft.trim().take(80) else conversation.title,
                    messages = conversation.messages + LocalMessage(role = "user", text = conversation.draft) + LocalMessage(role = "assistant", text = names.joinToString("\n") { name -> "![$name]($name)" }, completedAt = System.currentTimeMillis()),
                    draft = it.draft.takeUnless { draft -> draft == conversation.draft }.orEmpty(), updatedAt = System.currentTimeMillis(),
                ) }
                loadFiles()
                refresh()
            } catch (error: Exception) { conversationFailure(conversation.id, error) }
            finally { finishRequest(conversation.id) }
        }
    }
    fun dismissError() = updateState { it.copy(error = null) }

    fun removeAttachment(id: String) = updateState { it.copy(selected = it.selected?.copy(attachments = it.selected.attachments.filterNot { file -> file.id == id })) }

    fun attachFile(uri: Uri) = viewModelScope.launch {
        if (_state.value.sending) return@launch
        if (_state.value.selected == null) newConversation()
        val id = _state.value.selected?.id ?: return@launch
        try {
            require(_state.value.selected!!.attachments.size < 4) { uiText(R.string.attachment_limit, "Attach up to 4 files, 8 MB each") }
            val attachment = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.take(128) ?: "file"
                // ponytail: inline attachments are capped at 4 × 8 MB; use file-backed uploads if larger inputs are needed.
                val bytes = resolver.openInputStream(uri)?.use { it.readLimited(8 * 1024 * 1024) } ?: error(uiText(R.string.ui_293, "Unable to read selected file"))
                require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) { uiText(R.string.attachment_limit, "Attach up to 4 files, 8 MB each") }
                val mime = resolver.getType(uri).orEmpty()
                when {
                    mime.startsWith("image/") -> {
                        val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
                        require(info.outWidth > 0 && info.outHeight > 0 && info.outMimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { uiText(R.string.unsupported_attachment, "Choose an image, UTF-8 text file, or a PDF with Responses API") }
                        LocalAttachment(name = name, kind = "image", data = "data:${info.outMimeType};base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    }
                    mime == "application/pdf" -> {
                        require(bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" &&
                            _state.value.providers.firstOrNull { it.id == _state.value.providerId }?.protocol == ApiProtocol.RESPONSES) { uiText(R.string.unsupported_attachment, "Choose an image, UTF-8 text file, or a PDF with Responses API") }
                        LocalAttachment(name = name, kind = "file", data = "data:application/pdf;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    }
                    else -> {
                        require(bytes.size <= 1024 * 1024 && (mime.startsWith("text/") || name.substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "py", "html", "css", "log"))) { uiText(R.string.unsupported_attachment, "Choose an image, UTF-8 text file, or a PDF with Responses API") }
                        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        require('\u0000' !in text) { uiText(R.string.unsupported_attachment, "Choose an image, UTF-8 text file, or a PDF with Responses API") }
                        LocalAttachment(name = name, kind = "text", data = text)
                    }
                }
            }
            updateState { state -> if (state.selected?.id != id || state.selected.attachments.size >= 4) state else state.copy(selected = state.selected.copy(attachments = state.selected.attachments + attachment)) }
        } catch (error: Exception) { failure(error) }
    }

    fun archive(conversation: LocalConversation) = viewModelScope.launch {
        if (conversation.id in _state.value.runningConversationIds) return@launch
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
        if (_state.value.runningConversationIds.isNotEmpty()) return@launch
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
            withContext(Dispatchers.IO) { store.save(conversation) }
            loadFiles()
            refresh()
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

    private fun nextOpenedAt() = maxOf(System.currentTimeMillis(), (_state.value.conversations.maxOfOrNull { it.lastOpenedAt } ?: 0L) + 1)

    private fun updateConversation(id: String, transform: (LocalConversation) -> LocalConversation) = _state.update { current ->
        val old = current.selected?.takeIf { it.id == id } ?: current.conversations.firstOrNull { it.id == id } ?: return@update current
        val next = transform(old).copy(revision = old.revision + 1)
        current.copy(selected = if (current.selected?.id == id) next else current.selected,
            conversations = if (current.conversations.any { it.id == id }) current.conversations.map { if (it.id == id) next else it } else listOf(next) + current.conversations)
    }

    private suspend fun finishRequest(id: String, requestOwner: Job? = null) {
        val owner = requestOwner ?: currentCoroutineContext().job
        withContext(NonCancellable) {
            if (requestJobs[id] !== owner) return@withContext
            requestJobs.remove(id, owner)
            updateState { it.copy(runningConversationIds = it.runningConversationIds - id) }
            val saved = _state.value.selected?.takeIf { it.id == id } ?: _state.value.conversations.firstOrNull { it.id == id }
            if (saved != null) runCatching { withContext(Dispatchers.IO) { store.save(saved) } }.onFailure { conversationFailure(id, it) }
        }
    }

    private fun conversationFailure(id: String, error: Throwable) {
        if (error is CancellationException) throw error
        com.codex.remote.data.diagnostics.DebugApi.record("local_error", error)
        updateConversation(id) { conversation -> conversation.copy(messages = conversation.messages + LocalMessage(role = "assistant", text = error.message ?: error.javaClass.simpleName, completedAt = System.currentTimeMillis())) }
        if (_state.value.selected?.id == id) failure(error)
    }

    private fun updateState(transform: (LocalUiState) -> LocalUiState) = _state.update { current ->
        val next = transform(current)
        if (next.selected != null && next.selected != current.selected) next.copy(selected = next.selected.copy(
            revision = maxOf(next.selected.revision, current.selected?.revision ?: 0) + 1,
        )).let { revised -> revised.copy(conversations = (revised.conversations.filterNot { it.id == revised.selected!!.id } + revised.selected!!).sortedByDescending { it.lastOpenedAt }) } else next
    }

    private fun failure(error: Throwable) {
        if (error is CancellationException) throw error
        com.codex.remote.data.diagnostics.DebugApi.record("local_error", error)
        updateState { it.copy(error = error.message ?: error.javaClass.simpleName) }
    }
}
