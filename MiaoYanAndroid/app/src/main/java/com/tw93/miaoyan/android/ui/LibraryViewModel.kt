package com.tw93.miaoyan.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.AttachmentImporter
import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.data.ContentUriAttachmentSource
import com.tw93.miaoyan.android.data.LibraryRepository
import com.tw93.miaoyan.android.data.LibraryRepositoryProvider
import com.tw93.miaoyan.android.git.ActiveDraftRegistry
import com.tw93.miaoyan.android.git.GitConflictChoice
import com.tw93.miaoyan.android.git.GitConflictDetails
import com.tw93.miaoyan.android.git.GitCredentials
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncCoordinator
import com.tw93.miaoyan.android.git.GitSyncException
import com.tw93.miaoyan.android.git.GitSyncPreferences
import com.tw93.miaoyan.android.git.GitSyncRefreshEvents
import com.tw93.miaoyan.android.git.GitSyncScheduler
import com.tw93.miaoyan.android.git.KeystoreCredentialStore
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.typesetting.MarkdownFormatter
import com.tw93.miaoyan.android.typesetting.TypesettingRequest
import com.tw93.miaoyan.android.typesetting.TypesettingResultGuard
import com.tw93.miaoyan.android.typesetting.WebViewMarkdownFormatter
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val notes: List<LibraryNote> = emptyList(),
    val searchResults: List<LibraryNote> = emptyList(),
    val pinnedPaths: Set<String> = emptySet(),
    val trash: List<TrashedNote> = emptyList(),
    val query: String = "",
    val selected: OpenNote? = null,
    val draft: String = "",
    val preview: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val mutating: Boolean = false,
    val formatting: Boolean = false,
    val attaching: Boolean = false,
    val attachmentPickerOpen: Boolean = false,
    val syncing: Boolean = false,
    val dirty: Boolean = false,
    val draftRevision: Long = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val message: String? = null,
    val gitConfig: GitSyncConfig? = null,
    val gitUsername: String = "",
    val hasGitCredentials: Boolean = false,
    val gitConflict: GitConflictDetails? = null,
) {
    val visibleNotes: List<LibraryNote>
        get() = if (query.isBlank()) notes else searchResults
}

class LibraryViewModel @JvmOverloads constructor(
    application: Application,
    private val markdownFormatter: MarkdownFormatter = WebViewMarkdownFormatter(application),
) : AndroidViewModel(application) {
    private val repository: LibraryRepository = LibraryRepositoryProvider.get(application)
    private val gitPreferences = GitSyncPreferences(application)
    private val credentialStore = KeystoreCredentialStore(application)
    private val syncCoordinator = GitSyncCoordinator(
        context = application,
        repository = repository,
        publishRefreshEvents = false,
    )
    private val attachmentImporter = AttachmentImporter()
    private val libraryRoot = File(application.filesDir, "libraries/default")
    private val contentResolver = application.contentResolver
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var formatJob: Job? = null
    private var lastDraftRevision = 0L
    private var pendingAttachmentRequest: AttachmentRequest? = null

    init {
        reload()
        viewModelScope.launch {
            gitPreferences.config.distinctUntilChanged().collectLatest { config ->
                val credentials = config?.let { value ->
                    runCatching { credentialStore.load(value.repositoryUrl) }.getOrNull()
                }
                mutableState.update {
                    it.copy(
                        gitConfig = config,
                        gitUsername = credentials?.username.orEmpty(),
                        hasGitCredentials = credentials != null,
                    )
                }
            }
        }
        viewModelScope.launch {
            gitPreferences.pendingConflict.distinctUntilChanged().collectLatest { conflict ->
                mutableState.update { it.copy(gitConflict = conflict) }
            }
        }
        viewModelScope.launch {
            GitSyncRefreshEvents.events.collectLatest {
                runCatching { refreshAfterGit() }.onFailure(::showError)
            }
        }
    }

    fun reload() {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            runCatching {
                Triple(repository.scan(), repository.listTrash(), loadPinnedPaths())
            }.onSuccess { (notes, trash, pinnedPaths) ->
                    mutableState.update {
                        it.copy(
                            notes = notes,
                            trash = trash,
                            pinnedPaths = pinnedPaths,
                            loading = false,
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false) }
                    showError(error)
                }
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query, searchResults = emptyList()) }
        refreshSearch()
    }

    fun openNote(note: LibraryNote) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            runCatching { repository.open(note) }
                .onSuccess { opened ->
                    ActiveDraftRegistry.update(opened.note.relativePath, false)
                    mutableState.update {
                        it.copy(
                            selected = opened,
                            draft = opened.text,
                            dirty = false,
                            preview = false,
                            loading = false,
                            formatting = false,
                            draftRevision = nextDraftRevision(),
                            selectionStart = opened.text.length,
                            selectionEnd = opened.text.length,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false) }
                    showError(error)
                }
        }
    }

    fun createNote(name: String) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.createRootNote(name) }
                .onSuccess { opened ->
                    ActiveDraftRegistry.update(opened.note.relativePath, false)
                    val notes = repository.scan()
                    mutableState.update {
                        it.copy(
                            notes = notes,
                            selected = opened,
                            draft = opened.text,
                            dirty = false,
                            preview = false,
                            formatting = false,
                            draftRevision = nextDraftRevision(),
                            mutating = false,
                            selectionStart = opened.text.length,
                            selectionEnd = opened.text.length,
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun renameNote(note: LibraryNote, name: String) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.rename(note, name) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(
                            notes = repository.scan(),
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun moveToTrash(note: LibraryNote) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.moveToTrash(note) }
                .onSuccess {
                    val (notes, trash) = repository.scan() to repository.listTrash()
                    mutableState.update {
                        it.copy(
                            notes = notes,
                            trash = trash,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun restore(trashed: TrashedNote) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.restore(trashed) }
                .onSuccess { result ->
                    val (notes, trash) = repository.scan() to repository.listTrash()
                    val message = if (result.restoredToRoot) {
                        getApplication<Application>().getString(R.string.restored_to_root)
                    } else {
                        getApplication<Application>().getString(R.string.note_restored)
                    }
                    mutableState.update {
                        it.copy(
                            notes = notes,
                            trash = trash,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            message = message,
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun permanentlyDelete(trashed: TrashedNote) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching {
                repository.permanentlyDelete(trashed)
                repository.listTrash()
            }.onSuccess { trash ->
                mutableState.update {
                    it.copy(
                        trash = trash,
                        mutating = false,
                        message = getApplication<Application>().getString(R.string.deleted_permanently),
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(mutating = false) }
                showError(error)
            }
        }
    }

    fun importFrom(treeUri: Uri) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.importFrom(treeUri) }
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(
                            notes = repository.scan(),
                            trash = repository.listTrash(),
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            message = getApplication<Application>().getString(R.string.imported_files, result.fileCount),
                        )
                    }
                    refreshSearch()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun exportTo(treeUri: Uri) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.exportTo(treeUri) }
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(
                            mutating = false,
                            message = getApplication<Application>().getString(R.string.exported_files, result.fileCount),
                        )
                    }
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun togglePinned(note: LibraryNote) {
        if (isBusy()) return
        viewModelScope.launch {
            val pinned = note.relativePath in mutableState.value.pinnedPaths
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching {
                repository.setPinned(note.relativePath, !pinned)
                loadPinnedPaths()
            }.onSuccess { pinnedPaths ->
                mutableState.update { it.copy(pinnedPaths = pinnedPaths, mutating = false) }
            }.onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun updateDraft(text: String) {
        mutableState.update { current ->
            val selected = current.selected ?: return@update current
            if (current.draft == text) return@update current
            val dirty = text != selected.text
            ActiveDraftRegistry.update(selected.note.relativePath, dirty)
            current.copy(
                draft = text,
                dirty = dirty,
                draftRevision = nextDraftRevision(),
                selectionStart = current.selectionStart.coerceIn(0, text.length),
                selectionEnd = current.selectionEnd.coerceIn(0, text.length),
            )
        }
    }

    fun updateSelection(start: Int, end: Int) {
        mutableState.update { current ->
            if (current.selected == null) return@update current
            current.copy(
                selectionStart = start.coerceIn(0, current.draft.length),
                selectionEnd = end.coerceIn(0, current.draft.length),
            )
        }
    }

    fun prepareAttachment(kind: AttachmentKind): AttachmentRequest? {
        val current = mutableState.value
        val note = current.selected?.note ?: return null
        if (current.preview || isBusy() || current.attachmentPickerOpen || pendingAttachmentRequest != null) return null
        val request = AttachmentInsertionPolicy.request(
            DraftSnapshot(
                ownerNoteId = note.relativePath,
                revision = current.draftRevision,
                text = current.draft,
                selectionStart = current.selectionStart,
                selectionEnd = current.selectionEnd,
            ),
            kind,
        )
        pendingAttachmentRequest = request
        ActiveDraftRegistry.update(note.relativePath, true)
        mutableState.value = current.copy(attachmentPickerOpen = true)
        return request
    }

    fun finishAttachmentPicker(kind: AttachmentKind, uri: Uri?) {
        val request = pendingAttachmentRequest
        pendingAttachmentRequest = null
        mutableState.update { it.copy(attachmentPickerOpen = false) }
        if (uri == null) {
            updateActiveDraftRegistry()
            return
        }
        if (request == null || request.kind != kind) {
            mutableState.update {
                it.copy(message = getApplication<Application>().getString(R.string.attachment_stale))
            }
            updateActiveDraftRegistry()
            return
        }
        insertAttachment(request, uri)
    }

    private fun insertAttachment(request: AttachmentRequest, uri: Uri) {
        if (mutableState.value.attaching) return
        viewModelScope.launch {
            mutableState.update { it.copy(attaching = true, message = null) }
            runCatching {
                attachmentImporter.import(
                    libraryRoot = libraryRoot,
                    noteRelativePath = request.ownerNoteId,
                    kind = request.kind,
                    source = ContentUriAttachmentSource(contentResolver, uri),
                )
            }.onSuccess { attachment ->
                val current = mutableState.value
                val selected = current.selected
                val insertion = selected?.let {
                    AttachmentInsertionPolicy.apply(
                        current = DraftSnapshot(
                            ownerNoteId = it.note.relativePath,
                            revision = current.draftRevision,
                            text = current.draft,
                            selectionStart = current.selectionStart,
                            selectionEnd = current.selectionEnd,
                        ),
                        request = request,
                        markdown = attachment.markdown,
                    )
                } ?: AttachmentInsertionResult.Stale
                when (insertion) {
                    is AttachmentInsertionResult.Applied -> {
                        mutableState.value = current.copy(
                            draft = insertion.text,
                            dirty = insertion.text != selected?.text,
                            draftRevision = nextDraftRevision(),
                            selectionStart = insertion.cursor,
                            selectionEnd = insertion.cursor,
                            attaching = false,
                        )
                        ActiveDraftRegistry.update(request.ownerNoteId, true)
                    }
                    AttachmentInsertionResult.Stale -> {
                        runCatching {
                            attachmentImporter.discard(libraryRoot, request.ownerNoteId, attachment)
                        }
                        updateActiveDraftRegistry()
                        mutableState.update {
                            it.copy(
                                attaching = false,
                                message = getApplication<Application>().getString(R.string.attachment_stale),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                mutableState.update { it.copy(attaching = false) }
                updateActiveDraftRegistry()
                showError(error)
            }
        }
    }

    fun setPreview(enabled: Boolean) {
        mutableState.update { it.copy(preview = enabled) }
    }

    fun save(onSaved: (() -> Unit)? = null) {
        val snapshot = mutableState.value.selected ?: return
        val draft = mutableState.value.draft
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, message = null) }
            runCatching { repository.save(snapshot, draft) }
                .onSuccess { saved ->
                    mutableState.update { current ->
                        if (current.selected?.note?.id != snapshot.note.id) {
                            current.copy(saving = false)
                        } else if (current.draft == draft) {
                            current.copy(selected = saved, draft = saved.text, dirty = false, saving = false)
                        } else {
                            current.copy(selected = saved, dirty = current.draft != saved.text, saving = false)
                        }
                    }
                    updateActiveDraftRegistry()
                    onSaved?.invoke()
                    mutableState.update { it.copy(notes = repository.scan()) }
                    refreshSearch()
                }
                .onFailure { error ->
                    mutableState.update { it.copy(saving = false) }
                    showError(error)
                }
        }
    }

    fun typesetDraft() {
        val snapshot = mutableState.value
        val selected = snapshot.selected ?: return
        if (
            snapshot.preview || snapshot.formatting || snapshot.loading || snapshot.saving || snapshot.mutating ||
            snapshot.attaching || snapshot.attachmentPickerOpen || snapshot.syncing
        ) {
            return
        }
        val request = TypesettingRequest(
            ownerNoteId = selected.note.id,
            draftRevision = snapshot.draftRevision,
            markdown = snapshot.draft,
        )

        mutableState.update { current ->
            if (TypesettingResultGuard.canApply(request, current.selected?.note?.id, current.draftRevision)) {
                current.copy(formatting = true, message = null)
            } else {
                current
            }
        }
        if (mutableState.value.formatting) {
            ActiveDraftRegistry.update(selected.note.relativePath, true)
        }
        formatJob = viewModelScope.launch {
            runCatching { markdownFormatter.format(request.markdown) }
                .onSuccess { formatted ->
                    mutableState.update { current ->
                        if (!TypesettingResultGuard.canApply(
                                request,
                                current.selected?.note?.id,
                                current.draftRevision,
                            )
                        ) {
                            current.clearFormattingFor(request)
                        } else {
                            val opened = current.selected ?: return@update current
                            current.copy(
                                draft = formatted,
                                dirty = formatted != opened.text,
                                formatting = false,
                                draftRevision = nextDraftRevision(),
                                selectionStart = current.selectionStart.coerceIn(0, formatted.length),
                                selectionEnd = current.selectionEnd.coerceIn(0, formatted.length),
                                message = getApplication<Application>().getString(R.string.typesetting_succeeded),
                            )
                        }
                    }
                }
                .onFailure {
                    mutableState.update { current ->
                        if (TypesettingResultGuard.canApply(
                                request,
                                current.selected?.note?.id,
                                current.draftRevision,
                            )
                        ) {
                            current.copy(
                                formatting = false,
                                message = getApplication<Application>().getString(R.string.typesetting_failed),
                            )
                        } else {
                            current.clearFormattingFor(request)
                        }
                    }
                }
            updateActiveDraftRegistry()
            formatJob = null
        }
    }

    fun closeNote() {
        formatJob?.cancel()
        formatJob = null
        ActiveDraftRegistry.update(null, false)
        mutableState.update {
            it.copy(
                selected = null,
                draft = "",
                dirty = false,
                preview = false,
                formatting = false,
                draftRevision = nextDraftRevision(),
                attaching = false,
                selectionStart = 0,
                selectionEnd = 0,
                message = null,
            )
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun saveGitSettings(config: GitSyncConfig, username: String, newToken: String) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching {
                val validatedConfig = config.validated()
                val existing = if (newToken.isBlank()) {
                    credentialStore.load(validatedConfig.repositoryUrl)
                } else {
                    null
                }
                val credentials = if (newToken.isNotBlank()) {
                    GitCredentials(username, newToken)
                } else {
                    existing?.copy(username = username)
                        ?: throw GitSyncException.Configuration(
                            "Enter a personal access token for this repository URL.",
                        )
                }.validated()
                credentialStore.save(validatedConfig.repositoryUrl, credentials)
                gitPreferences.save(validatedConfig)
                gitPreferences.setPendingConflict(null)
                GitSyncScheduler.updatePeriodic(
                    getApplication(),
                    validatedConfig.periodicEnabled,
                )
                validatedConfig to credentials
            }.onSuccess { (validatedConfig, credentials) ->
                mutableState.update {
                    it.copy(
                        mutating = false,
                        gitConfig = validatedConfig,
                        gitUsername = credentials.username,
                        hasGitCredentials = true,
                        gitConflict = null,
                        message = getApplication<Application>().getString(R.string.git_settings_saved),
                    )
                }
            }.onFailure { error ->
                mutableState.update { it.copy(mutating = false) }
                showError(error)
            }
        }
    }

    fun syncNow() {
        if (isBusy()) return
        if (mutableState.value.dirty) {
            mutableState.update {
                it.copy(message = getApplication<Application>().getString(R.string.git_save_before_sync))
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(syncing = true, message = null) }
            finishGitAttempt(runCatching { syncCoordinator.sync() })
        }
    }

    fun resolveGitConflict(choices: Map<String, GitConflictChoice>) {
        val conflict = mutableState.value.gitConflict ?: return
        if (isBusy()) return
        if (mutableState.value.dirty) {
            mutableState.update {
                it.copy(message = getApplication<Application>().getString(R.string.git_save_before_sync))
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(syncing = true, message = null) }
            finishGitAttempt(runCatching { syncCoordinator.resolve(conflict, choices) })
        }
    }

    private suspend fun finishGitAttempt(attempt: Result<*>) {
        val refreshFailure = runCatching { refreshAfterGit() }.exceptionOrNull()
        val error = attempt.exceptionOrNull() ?: refreshFailure
        if (error == null) {
            mutableState.update {
                it.copy(
                    syncing = false,
                    gitConflict = null,
                    message = getApplication<Application>().getString(R.string.git_sync_complete),
                )
            }
        } else {
            mutableState.update { current ->
                current.copy(
                    syncing = false,
                    gitConflict = (error as? GitSyncException.Conflict)?.details ?: current.gitConflict,
                )
            }
            showError(error)
        }
    }

    private suspend fun refreshAfterGit() {
        val before = mutableState.value
        val ownerPath = before.selected?.note?.relativePath
        val ownerRevision = before.draftRevision
        val notes = repository.scan()
        val trash = repository.listTrash()
        val pinnedPaths = loadPinnedPaths()
        val reopened = if (before.dirty || before.formatting || before.attaching || before.attachmentPickerOpen) {
            null
        } else {
            ownerPath
                ?.let { path -> notes.firstOrNull { it.relativePath == path } }
                ?.let { note -> repository.open(note) }
        }
        mutableState.update { current ->
            val sameEditor = current.selected?.note?.relativePath == ownerPath &&
                current.draftRevision == ownerRevision
            if (
                !sameEditor || current.dirty || current.formatting || current.attaching ||
                current.attachmentPickerOpen
            ) {
                current.copy(notes = notes, trash = trash, pinnedPaths = pinnedPaths)
            } else {
                val cursor = reopened?.text?.length ?: 0
                current.copy(
                    notes = notes,
                    trash = trash,
                    pinnedPaths = pinnedPaths,
                    selected = reopened,
                    draft = reopened?.text.orEmpty(),
                    dirty = false,
                    preview = if (reopened == null) false else current.preview,
                    draftRevision = nextDraftRevision(),
                    selectionStart = cursor,
                    selectionEnd = cursor,
                )
            }
        }
        updateActiveDraftRegistry()
        refreshSearch()
    }

    private fun refreshSearch() {
        searchJob?.cancel()
        val query = mutableState.value.query.trim()
        if (query.isEmpty()) {
            mutableState.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SearchDebounceMillis)
            runCatching { repository.search(query) }
                .onSuccess { results ->
                    mutableState.update { current ->
                        if (current.query.trim() == query) current.copy(searchResults = results) else current
                    }
                }
                .onFailure(::showError)
        }
    }

    private suspend fun finishFailedMutation(error: Throwable) {
        val refreshed = runCatching {
            Triple(repository.scan(), repository.listTrash(), loadPinnedPaths())
        }.getOrNull()
        mutableState.update {
            it.copy(
                notes = refreshed?.first ?: it.notes,
                trash = refreshed?.second ?: it.trash,
                pinnedPaths = refreshed?.third ?: it.pinnedPaths,
                mutating = false,
            )
        }
        refreshSearch()
        showError(error)
    }

    private fun isBusy(): Boolean = mutableState.value.run {
        loading || saving || mutating || formatting || attaching || attachmentPickerOpen || syncing
    }

    private fun updateActiveDraftRegistry() {
        val current = mutableState.value
        ActiveDraftRegistry.update(current.selected?.note?.relativePath, current.dirty)
    }

    private suspend fun loadPinnedPaths(): Set<String> =
        repository.pinnedNotes().mapTo(mutableSetOf(), LibraryNote::relativePath)

    private fun showError(error: Throwable) {
        mutableState.update {
            it.copy(
                message = error.message
                    ?: getApplication<Application>().getString(R.string.private_library_error),
            )
        }
    }

    private fun nextDraftRevision(): Long {
        lastDraftRevision += 1
        return lastDraftRevision
    }

    private fun LibraryUiState.clearFormattingFor(request: TypesettingRequest): LibraryUiState =
        if (selected?.note?.id == request.ownerNoteId) copy(formatting = false) else this

    override fun onCleared() {
        formatJob?.cancel()
        markdownFormatter.close()
        ActiveDraftRegistry.update(null, false)
        super.onCleared()
    }

    private companion object {
        const val SearchDebounceMillis = 120L
    }
}
