package com.tw93.miaoyan.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tw93.miaoyan.android.data.LibraryPreferences
import com.tw93.miaoyan.android.data.SafLibraryRepository
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.OpenNote
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val rootUri: Uri? = null,
    val libraryName: String = "MiaoYan",
    val notes: List<LibraryNote> = emptyList(),
    val query: String = "",
    val selected: OpenNote? = null,
    val draft: String = "",
    val preview: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val message: String? = null,
) {
    val visibleNotes: List<LibraryNote>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return notes
            return notes.filter {
                it.displayName.contains(needle, ignoreCase = true) ||
                    it.relativePath.contains(needle, ignoreCase = true)
            }
        }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = LibraryPreferences(application)
    private val repository = SafLibraryRepository(application)
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.rootUri.distinctUntilChanged().collectLatest { uri ->
                if (uri == null) {
                    mutableState.value = LibraryUiState()
                } else {
                    load(uri)
                }
            }
        }
    }

    fun selectRoot(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                repository.retainPermission(uri)
                preferences.setRoot(uri)
            }.onFailure(::showError)
        }
    }

    fun reload() {
        mutableState.value.rootUri?.let(::load)
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun openNote(note: LibraryNote) {
        if (mutableState.value.loading || mutableState.value.saving) return
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
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false) }
                    showError(error)
                }
        }
    }

    fun updateDraft(text: String) {
        mutableState.update { current ->
            val selected = current.selected ?: return@update current
            current.copy(draft = text, dirty = text != selected.text)
        }
    }

    fun setPreview(enabled: Boolean) {
        mutableState.update { it.copy(preview = enabled) }
    }

    fun save(onSaved: (() -> Unit)? = null) {
        val snapshot = mutableState.value.selected ?: return
        val draft = mutableState.value.draft
        if (mutableState.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, message = null) }
            runCatching { repository.save(snapshot, draft) }
                .onSuccess { saved ->
                    mutableState.update { current ->
                        if (current.selected?.note?.uri != snapshot.note.uri) {
                            current.copy(saving = false)
                        } else if (current.draft == draft) {
                            current.copy(selected = saved, draft = saved.text, dirty = false, saving = false)
                        } else {
                            current.copy(selected = saved, dirty = current.draft != saved.text, saving = false)
                        }
                    }
                    onSaved?.invoke()
                    mutableState.value.rootUri?.let(::refreshAfterSave)
                }
                .onFailure { error ->
                    mutableState.update { it.copy(saving = false) }
                    showError(error)
                }
        }
    }

    fun closeNote() {
        mutableState.update {
            it.copy(selected = null, draft = "", dirty = false, preview = false, message = null)
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun load(uri: Uri) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(rootUri = uri, loading = true, selected = null, dirty = false, message = null)
            }
            runCatching { repository.libraryName(uri) to repository.scan(uri) }
                .onSuccess { (name, notes) ->
                    mutableState.update { it.copy(libraryName = name, notes = notes, loading = false) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(loading = false, notes = emptyList()) }
                    showError(error)
                }
        }
    }

    private fun refreshAfterSave(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.scan(uri) }.onSuccess { notes ->
                mutableState.update { current -> current.copy(notes = notes) }
            }
        }
    }

    private fun showError(error: Throwable) {
        mutableState.update {
            it.copy(message = error.message ?: "Android could not access the selected library.")
        }
    }
}
