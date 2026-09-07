package com.tw93.miaoyan.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.IndexedLibraryRepository
import com.tw93.miaoyan.android.data.LibraryRepository
import com.tw93.miaoyan.android.data.LocalLibraryRepository
import com.tw93.miaoyan.android.data.LocalPinStore
import com.tw93.miaoyan.android.data.index.RoomLibrarySearchIndex
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.typesetting.MarkdownFormatter
import com.tw93.miaoyan.android.typesetting.TypesettingRequest
import com.tw93.miaoyan.android.typesetting.TypesettingResultGuard
import com.tw93.miaoyan.android.typesetting.WebViewMarkdownFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val notes: List<LibraryNote> = emptyList(),
    val searchResults: List<LibraryNote> = emptyList(),
    val pinnedPaths: Set<String> = emptySet(),
    val trash: List<TrashedNote> = emptyList(),
    val showingTrash: Boolean = false,
    val query: String = "",
    val selected: OpenNote? = null,
    val draft: String = "",
    val preview: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val mutating: Boolean = false,
    val formatting: Boolean = false,
    val dirty: Boolean = false,
    val draftRevision: Long = 0,
    val message: String? = null,
) {
    val visibleNotes: List<LibraryNote>
        get() = if (query.isBlank()) notes else searchResults
}

class LibraryViewModel @JvmOverloads constructor(
    application: Application,
    private val markdownFormatter: MarkdownFormatter = WebViewMarkdownFormatter(application),
) : AndroidViewModel(application) {
    private val repository: LibraryRepository = IndexedLibraryRepository(
        canonical = LocalLibraryRepository(application),
        index = RoomLibrarySearchIndex(application),
        pins = LocalPinStore(application),
    )
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var formatJob: Job? = null
    private var lastDraftRevision = 0L

    init {
        reload()
    }

    fun reload() {
        if (mutableState.value.loading || mutableState.value.mutating) return
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

    fun showTrash(show: Boolean) {
        searchJob?.cancel()
        mutableState.update { it.copy(showingTrash = show, query = "", searchResults = emptyList()) }
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
                    mutableState.update {
                        it.copy(
                            selected = opened,
                            draft = opened.text,
                            dirty = false,
                            preview = false,
                            loading = false,
                            formatting = false,
                            draftRevision = nextDraftRevision(),
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
                            showingTrash = false,
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
                            showingTrash = false,
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
            if (text == current.draft) return@update current
            current.copy(
                draft = text,
                dirty = text != selected.text,
                draftRevision = nextDraftRevision(),
            )
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
        if (snapshot.preview || snapshot.formatting || snapshot.loading || snapshot.saving || snapshot.mutating) return
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
            formatJob = null
        }
    }

    fun closeNote() {
        formatJob?.cancel()
        formatJob = null
        mutableState.update {
            it.copy(
                selected = null,
                draft = "",
                dirty = false,
                preview = false,
                formatting = false,
                draftRevision = nextDraftRevision(),
                message = null,
            )
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
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

    private fun isBusy(): Boolean = mutableState.value.run { loading || saving || mutating || formatting }

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
        super.onCleared()
    }

    private companion object {
        const val SearchDebounceMillis = 120L
    }
}
