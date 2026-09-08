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
import com.tw93.miaoyan.android.data.FolderMutationResult
import com.tw93.miaoyan.android.git.ActiveDraftRegistry
import com.tw93.miaoyan.android.git.GitConflictChoice
import com.tw93.miaoyan.android.git.GitConflictDetails
import com.tw93.miaoyan.android.git.GitConflictKind
import com.tw93.miaoyan.android.git.GitCredentials
import com.tw93.miaoyan.android.git.GitSyncConfig
import com.tw93.miaoyan.android.git.GitSyncCoordinator
import com.tw93.miaoyan.android.git.GitSyncDiagnostics
import com.tw93.miaoyan.android.git.GitSyncException
import com.tw93.miaoyan.android.git.GitSyncPreferences
import com.tw93.miaoyan.android.git.GitSyncRefreshEvents
import com.tw93.miaoyan.android.git.GitSyncScheduler
import com.tw93.miaoyan.android.git.GitSyncStatus
import com.tw93.miaoyan.android.git.KeystoreCredentialStore
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.LibraryItemKind
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.typesetting.MarkdownFormatter
import com.tw93.miaoyan.android.typesetting.MarkdownFormattingException
import com.tw93.miaoyan.android.typesetting.TypesettingRequest
import com.tw93.miaoyan.android.typesetting.TypesettingResultGuard
import com.tw93.miaoyan.android.typesetting.WebViewMarkdownFormatter
import java.io.File
import kotlinx.coroutines.CancellationException
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
    val currentFolder: LibraryFolder = RootLibraryFolder,
    val folders: List<LibraryFolder> = emptyList(),
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
    val messageTone: UiMessageTone = UiMessageTone.Info,
    val gitConfig: GitSyncConfig? = null,
    val gitUsername: String = "",
    val hasGitCredentials: Boolean = false,
    val gitConflict: GitConflictDetails? = null,
    val gitSyncStatus: GitSyncStatus = GitSyncStatus(),
) {
    val visibleNotes: List<LibraryNote>
        get() = (if (query.isBlank()) notes else searchResults)
            .sortedByDescending { it.relativePath in pinnedPaths }

    val hasValidGitSetup: Boolean
        get() = hasGitCredentials && gitConfig?.let { config ->
            runCatching { config.validated() }.isSuccess
        } == true

    val visibleFolders: List<LibraryFolder>
        get() = if (query.isBlank()) folders else emptyList()
}

enum class UiMessageTone { Info, Success, Error }

private val RootLibraryFolder = LibraryFolder(
    id = "app-private://libraries/default",
    relativePath = "",
    displayName = "MiaoYan",
)

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
    private val manualReloadOrchestrator = ManualReloadOrchestrator(
        saveDirtyDraft = ::saveDirtyDraftForSync,
        syncGit = { syncCoordinator.sync() },
        refreshLibrary = ::refreshLibrary,
    )

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
            gitPreferences.syncStatus.distinctUntilChanged().collectLatest { syncStatus ->
                mutableState.update { it.copy(gitSyncStatus = syncStatus) }
            }
        }
        viewModelScope.launch {
            GitSyncRefreshEvents.events.collectLatest {
                runCatching { refreshLibrary() }.onFailure(::showError)
            }
        }
    }

    fun reload() {
        if (isBusy()) return
        mutableState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val route = if (syncCoordinator.hasValidConfigurationAndCredentials()) {
                ManualReloadRoute.Git
            } else {
                ManualReloadRoute.Local
            }
            if (route == ManualReloadRoute.Git) {
                mutableState.update { it.copy(loading = false, syncing = true) }
            }
            val dirty = mutableState.value.dirty
            val attempt = try {
                manualReloadOrchestrator.run(route, dirty)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            finishManualReload(route, attempt)
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query, searchResults = emptyList()) }
        refreshSearch()
    }

    fun openNote(note: LibraryNote) {
        if (isBusy()) return
        if (mutableState.value.dirty && mutableState.value.selected?.note?.relativePath != note.relativePath) {
            showDraftGuardMessage()
            return
        }
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
                            selectionStart = 0,
                            selectionEnd = 0,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false) }
                    showError(error)
                }
        }
    }

    fun openFolder(folder: LibraryFolder) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, query = "", searchResults = emptyList(), message = null) }
            runCatching { repository.listDirectory(folder.relativePath) }
                .onSuccess { listing ->
                    mutableState.update {
                        it.copy(
                            currentFolder = listing.currentFolder,
                            folders = listing.folders,
                            notes = listing.notes,
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false) }
                    showError(error)
                }
        }
    }

    fun openFolderPath(relativePath: String) {
        openFolder(
            LibraryFolder(
                id = relativePath.ifEmpty { RootLibraryFolder.id },
                relativePath = relativePath,
                displayName = relativePath.substringAfterLast('/').ifEmpty { "MiaoYan" },
            ),
        )
    }

    fun navigateUp() {
        val current = mutableState.value.currentFolder.relativePath
        if (current.isEmpty()) return
        openFolderPath(current.substringBeforeLast('/', ""))
    }

    fun createNote(name: String) {
        if (isBusy()) return
        if (mutableState.value.dirty) {
            showDraftGuardMessage()
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            val folderPath = mutableState.value.currentFolder.relativePath
            runCatching { repository.createNote(folderPath, name) }
                .onSuccess { opened ->
                    ActiveDraftRegistry.update(opened.note.relativePath, false)
                    repository.scan()
                    val listing = repository.listDirectory(folderPath)
                    mutableState.update {
                        it.copy(
                            currentFolder = listing.currentFolder,
                            folders = listing.folders,
                            notes = listing.notes,
                            selected = opened,
                            draft = opened.text,
                            dirty = false,
                            preview = false,
                            formatting = false,
                            draftRevision = nextDraftRevision(),
                            mutating = false,
                            selectionStart = 0,
                            selectionEnd = 0,
                        )
                    }
                    refreshSearch()
                    markLocalChanges()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun createFolder(name: String) {
        if (isBusy()) return
        viewModelScope.launch {
            val parentPath = mutableState.value.currentFolder.relativePath
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching {
                repository.createFolder(parentPath, name)
                repository.listDirectory(parentPath)
            }.onSuccess { listing ->
                mutableState.update {
                    it.copy(folders = listing.folders, notes = listing.notes, mutating = false)
                }
                markLocalChanges()
            }.onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun renameNote(note: LibraryNote, name: String) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.rename(note, name) }
                .onSuccess { renamed ->
                    val listing = repository.listDirectory(mutableState.value.currentFolder.relativePath)
                    mutableState.update { state ->
                        val selected = if (state.selected?.note?.relativePath == note.relativePath) {
                            state.selected.copy(note = renamed)
                        } else {
                            state.selected
                        }
                        state.copy(
                            folders = listing.folders,
                            notes = listing.notes,
                            selected = selected,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                        )
                    }
                    updateActiveDraftRegistry()
                    refreshSearch()
                    if (renamed.relativePath != note.relativePath) markLocalChanges()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun renameFolder(folder: LibraryFolder, name: String) {
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.renameFolder(folder, name) }
                .onSuccess { mutation ->
                    val current = mutableState.value
                    val remappedCurrentPath = LibraryViewModelFolderPolicy.remapPath(
                        current.currentFolder.relativePath,
                        mutation,
                    )
                    val listing = repository.listDirectory(remappedCurrentPath)
                    mutableState.update { state ->
                        val selected = LibraryViewModelFolderPolicy.remapOpenNote(state.selected, mutation)
                        state.copy(
                            currentFolder = listing.currentFolder,
                            folders = listing.folders,
                            notes = listing.notes,
                            selected = selected,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                        )
                    }
                    updateActiveDraftRegistry()
                    refreshSearch()
                    if (mutation.newRelativePath != mutation.oldRelativePath) markLocalChanges()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun moveToTrash(note: LibraryNote) {
        if (isBusy()) return
        val before = mutableState.value
        if (before.dirty && before.selected?.note?.relativePath == note.relativePath) {
            showDraftGuardMessage()
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.moveToTrash(note) }
                .onSuccess {
                    repository.scan()
                    val listing = repository.listDirectory(mutableState.value.currentFolder.relativePath)
                    val trash = repository.listTrash()
                    val selectedWasRemoved = mutableState.value.selected?.note?.relativePath == note.relativePath
                    mutableState.update {
                        it.copy(
                            folders = listing.folders,
                            notes = listing.notes,
                            trash = trash,
                            selected = if (selectedWasRemoved) null else it.selected,
                            draft = if (selectedWasRemoved) "" else it.draft,
                            dirty = if (selectedWasRemoved) false else it.dirty,
                            preview = if (selectedWasRemoved) false else it.preview,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            draftRevision = if (selectedWasRemoved) nextDraftRevision() else it.draftRevision,
                        )
                    }
                    updateActiveDraftRegistry()
                    refreshSearch()
                    markLocalChanges()
                }
                .onFailure { error -> finishFailedMutation(error) }
        }
    }

    fun moveFolderToTrash(folder: LibraryFolder) {
        if (isBusy()) return
        val current = mutableState.value
        if (!LibraryViewModelFolderPolicy.canTrashFolder(current.selected, current.dirty, folder.relativePath)) {
            mutableState.update {
                it.copy(message = getApplication<Application>().getString(R.string.folder_save_before_trash))
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(mutating = true, message = null) }
            runCatching { repository.moveFolderToTrash(folder) }
                .onSuccess {
                    repository.scan()
                    val state = mutableState.value
                    val currentPath = if (
                        LibraryViewModelFolderPolicy.isWithin(state.currentFolder.relativePath, folder.relativePath)
                    ) {
                        folder.relativePath.substringBeforeLast('/', "")
                    } else {
                        state.currentFolder.relativePath
                    }
                    val listing = repository.listDirectory(currentPath)
                    val selectedInside = state.selected?.note?.relativePath?.let { path ->
                        LibraryViewModelFolderPolicy.isWithin(path, folder.relativePath)
                    } == true
                    mutableState.update {
                        it.copy(
                            currentFolder = listing.currentFolder,
                            folders = listing.folders,
                            notes = listing.notes,
                            trash = repository.listTrash(),
                            selected = if (selectedInside) null else it.selected,
                            draft = if (selectedInside) "" else it.draft,
                            dirty = if (selectedInside) false else it.dirty,
                            preview = if (selectedInside) false else it.preview,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            draftRevision = if (selectedInside) nextDraftRevision() else it.draftRevision,
                        )
                    }
                    updateActiveDraftRegistry()
                    refreshSearch()
                    markLocalChanges()
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
                    repository.scan()
                    val currentPath = mutableState.value.currentFolder.relativePath
                    val listing = repository.listDirectory(currentPath)
                    val trash = repository.listTrash()
                    val message = if (result.restoredToRoot) {
                        getApplication<Application>().getString(R.string.restored_to_root)
                    } else {
                        getApplication<Application>().getString(
                            if (result.kind == LibraryItemKind.FOLDER) R.string.folder_restored else R.string.note_restored,
                        )
                    }
                    mutableState.update {
                        it.copy(
                            folders = listing.folders,
                            notes = listing.notes,
                            trash = trash,
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            message = message,
                        )
                    }
                    refreshSearch()
                    markLocalChanges()
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
                    val listing = repository.listDirectory(mutableState.value.currentFolder.relativePath)
                    mutableState.update {
                        it.copy(
                            currentFolder = listing.currentFolder,
                            folders = listing.folders,
                            notes = listing.notes,
                            trash = repository.listTrash(),
                            pinnedPaths = loadPinnedPaths(),
                            mutating = false,
                            message = getApplication<Application>().getString(R.string.imported_files, result.fileCount),
                        )
                    }
                    refreshSearch()
                    if (result.fileCount > 0) markLocalChanges()
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
                        markLocalChanges()
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
        val contentChanged = draft != snapshot.text
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
                    repository.scan()
                    val listing = repository.listDirectory(mutableState.value.currentFolder.relativePath)
                    mutableState.update { it.copy(folders = listing.folders, notes = listing.notes) }
                    refreshSearch()
                    if (contentChanged) markLocalChanges()
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

    fun syncNow() = reload()

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

    fun keepLocalUnrelatedGitHistory() {
        val conflict = mutableState.value.gitConflict
            ?.takeIf { it.kind == GitConflictKind.UnrelatedHistory }
            ?: return
        if (isBusy()) return
        viewModelScope.launch {
            runCatching { syncCoordinator.keepLocalUnrelatedHistory(conflict) }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            gitConflict = null,
                            message = getApplication<Application>().getString(
                                R.string.git_unrelated_kept_local,
                            ),
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    private suspend fun finishGitAttempt(attempt: Result<*>) {
        val refreshFailure = runCatching { refreshLibrary() }.exceptionOrNull()
        val error = (attempt.exceptionOrNull() ?: refreshFailure)?.let(GitSyncDiagnostics::mapForUser)
        if (error == null) {
            mutableState.update {
                it.copy(
                    syncing = false,
                        gitConflict = null,
                        messageTone = UiMessageTone.Success,
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

    private suspend fun refreshLibrary() {
        val before = mutableState.value
        val ownerPath = before.selected?.note?.relativePath
        val ownerRevision = before.draftRevision
        val notes = repository.scan()
        val listing = runCatching { repository.listDirectory(before.currentFolder.relativePath) }
            .getOrElse { repository.listDirectory("") }
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
                current.copy(
                    currentFolder = listing.currentFolder,
                    folders = listing.folders,
                    notes = listing.notes,
                    trash = trash,
                    pinnedPaths = pinnedPaths,
                )
            } else {
                val cursor = reopened?.text?.length ?: 0
                current.copy(
                    currentFolder = listing.currentFolder,
                    folders = listing.folders,
                    notes = listing.notes,
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

    private suspend fun saveDirtyDraftForSync() {
        val beforeSave = mutableState.value
        if (!beforeSave.dirty) return
        val snapshot = beforeSave.selected
            ?: throw GitSyncException.Storage(
                getApplication<Application>().getString(R.string.git_draft_owner_missing),
            )
        val draft = beforeSave.draft
        val saved = repository.save(snapshot, draft)
        mutableState.update { current ->
            if (current.selected?.note?.id != snapshot.note.id) {
                current
            } else if (current.draft == draft) {
                current.copy(selected = saved, draft = saved.text, dirty = false)
            } else {
                current.copy(selected = saved, dirty = current.draft != saved.text)
            }
        }
        updateActiveDraftRegistry()
        markLocalChanges(throwOnPersistenceFailure = true)
        if (mutableState.value.dirty) {
            throw GitSyncException.Storage(
                getApplication<Application>().getString(R.string.git_draft_changed_during_save),
            )
        }
    }

    private fun finishManualReload(route: ManualReloadRoute, attempt: Result<Unit>) {
        val error = attempt.exceptionOrNull()?.let { failure ->
            if (route == ManualReloadRoute.Git) GitSyncDiagnostics.mapForUser(failure) else failure
        }
        mutableState.update { current ->
            current.copy(
                loading = false,
                syncing = false,
                gitConflict = when {
                    error is GitSyncException.Conflict -> error.details ?: current.gitConflict
                    error == null && route == ManualReloadRoute.Git -> null
                    else -> current.gitConflict
                },
                message = if (error == null && route == ManualReloadRoute.Git) {
                    getApplication<Application>().getString(R.string.git_sync_complete)
                } else {
                    current.message
                },
                messageTone = if (error == null && route == ManualReloadRoute.Git) {
                    UiMessageTone.Success
                } else {
                    current.messageTone
                },
            )
        }
        if (error != null) showError(error)
    }

    private suspend fun markLocalChanges(throwOnPersistenceFailure: Boolean = false) {
        mutableState.update { current ->
            current.copy(gitSyncStatus = current.gitSyncStatus.afterLocalChange())
        }
        try {
            gitPreferences.markLocalChanges()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val wrapped = GitSyncException.Storage(
                getApplication<Application>().getString(R.string.git_backup_status_save_failed),
                error,
            )
            if (throwOnPersistenceFailure) throw wrapped
            showError(wrapped)
        }
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
            loadLibrarySnapshot(mutableState.value.currentFolder.relativePath)
        }.getOrNull()
        mutableState.update {
            it.copy(
                currentFolder = refreshed?.currentFolder ?: it.currentFolder,
                folders = refreshed?.folders ?: it.folders,
                notes = refreshed?.notes ?: it.notes,
                trash = refreshed?.trash ?: it.trash,
                pinnedPaths = refreshed?.pinnedPaths ?: it.pinnedPaths,
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

    private suspend fun loadLibrarySnapshot(preferredFolderPath: String): LibrarySnapshot {
        repository.scan()
        val listing = runCatching { repository.listDirectory(preferredFolderPath) }
            .getOrElse { repository.listDirectory("") }
        return LibrarySnapshot(
            currentFolder = listing.currentFolder,
            folders = listing.folders,
            notes = listing.notes,
            trash = repository.listTrash(),
            pinnedPaths = loadPinnedPaths(),
        )
    }

    private fun showError(error: Throwable) {
        GitSyncDiagnostics.rethrowIfFatal(error)
        val userMessage = when (error) {
            is GitSyncException,
            is MarkdownFormattingException,
            -> error.message
            else -> null
        }
        mutableState.update {
            it.copy(
                message = userMessage
                    ?: getApplication<Application>().getString(R.string.private_library_error),
                messageTone = UiMessageTone.Error,
            )
        }
    }

    private fun showDraftGuardMessage() {
        mutableState.update {
            it.copy(message = getApplication<Application>().getString(R.string.save_before_library_mutation))
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
        const val SearchDebounceMillis = 300L
    }
}

private data class LibrarySnapshot(
    val currentFolder: LibraryFolder,
    val folders: List<LibraryFolder>,
    val notes: List<LibraryNote>,
    val trash: List<TrashedNote>,
    val pinnedPaths: Set<String>,
)

internal object LibraryViewModelFolderPolicy {
    fun isWithin(path: String, folderPath: String): Boolean =
        path == folderPath || path.startsWith("$folderPath/")

    fun remapPath(path: String, mutation: FolderMutationResult): String {
        val destination = mutation.newRelativePath ?: return path
        return when {
            path == mutation.oldRelativePath -> destination
            path.startsWith("${mutation.oldRelativePath}/") -> destination + path.removePrefix(mutation.oldRelativePath)
            else -> path
        }
    }

    fun remapOpenNote(openNote: OpenNote?, mutation: FolderMutationResult): OpenNote? {
        val opened = openNote ?: return null
        val newPath = remapPath(opened.note.relativePath, mutation)
        if (newPath == opened.note.relativePath) return opened
        return opened.copy(
            note = opened.note.copy(id = newPath, relativePath = newPath),
        )
    }

    fun canTrashFolder(openNote: OpenNote?, dirty: Boolean, folderPath: String): Boolean =
        !dirty || openNote?.note?.relativePath?.let { !isWithin(it, folderPath) } != false
}
