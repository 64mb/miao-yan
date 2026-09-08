package com.tw93.miaoyan.android.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.AttachmentKind
import com.tw93.miaoyan.android.data.AppLanguage
import com.tw93.miaoyan.android.data.EDITOR_FONT_SIZES
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.data.ThemeMode
import com.tw93.miaoyan.android.data.LocalImagePolicy
import com.tw93.miaoyan.android.data.NameError
import com.tw93.miaoyan.android.data.NameResult
import com.tw93.miaoyan.android.data.NotePathPolicy
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.LibraryFolder
import com.tw93.miaoyan.android.model.LibraryItemKind
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.ui.editor.MarkdownEditorPalettes
import com.tw93.miaoyan.android.ui.editor.MarkdownSyntaxHighlighter
import com.tw93.miaoyan.android.ui.editor.MarkdownSyntaxPalette
import com.tw93.miaoyan.android.ui.presentation.AppPrivatePresentationImageHandler
import com.tw93.miaoyan.android.ui.presentation.ContinuousPreview
import com.tw93.miaoyan.android.ui.presentation.ContinuousPreviewPreparation
import com.tw93.miaoyan.android.ui.presentation.PresentationHost
import com.tw93.miaoyan.android.ui.presentation.PresentationImageHandler
import com.tw93.miaoyan.android.ui.presentation.PresentationMode
import com.tw93.miaoyan.android.ui.presentation.PreviewWebViewController
import com.tw93.miaoyan.android.ui.presentation.rememberContinuousPreviewDocument
import com.tw93.miaoyan.android.ui.presentation.rememberPresentationSession
import com.tw93.miaoyan.android.ui.theme.MiaoYanColors
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val JetBrainsMonoFamily = FontFamily(Font(R.font.jetbrains_mono_regular))

@Composable
fun MiaoYanApp(
    viewModel: LibraryViewModel,
    editorSettings: EditorSettings,
    appLanguage: AppLanguage,
    onFontChanged: (EditorFont) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busyOverlay = remember { BusyOverlayStateMachine() }
    val context = LocalContext.current
    val presentationSession = rememberPresentationSession()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTrashSettings by rememberSaveable { mutableStateOf(false) }
    var showGitSettings by rememberSaveable { mutableStateOf(false) }
    var showGitConflict by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importFrom)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::exportTo)
    }
    val launchImport = { importLauncher.launch(null) }
    val launchExport = { exportLauncher.launch(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.trimEnd().trimEnd('.'))
            viewModel.dismissMessage()
        }
    }

    LaunchedEffect(state.gitConflict?.localCommit, state.gitConflict?.remoteCommit) {
        if (state.gitConflict != null) showGitConflict = true
    }

    val selected = state.selected
    val canonicalRoot = remember(context) { File(context.filesDir, "libraries/default") }
    val imageScope = remember(canonicalRoot, selected?.note?.relativePath) {
        selected?.note?.relativePath?.let { LocalImagePolicy.resolveNoteAssetScope(canonicalRoot, it) }
    }
    val presentationImageHandler = remember(imageScope) {
        imageScope?.let(::AppPrivatePresentationImageHandler) ?: PresentationImageHandler.DenyAll
    }
    val presentationMode = presentationSession.mode
    val twoPane = LibraryWindowLayout.forWidthDp(LocalConfiguration.current.screenWidthDp) ==
        LibraryWindowLayoutMode.ListDetail
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    val continuousPreparation = rememberContinuousPreviewDocument(
        markdown = state.draft,
        darkMode = darkMode,
        editorSettings = editorSettings,
        enabled = selected != null,
    )
    val previewController = remember(selected?.note?.relativePath) { PreviewWebViewController() }
    if (presentationMode == PresentationMode.Slides && selected != null) {
        PresentationHost(
            mode = presentationMode,
            markdown = state.draft,
            editorSettings = editorSettings,
            initialSlide = presentationSession.slide,
            imageHandler = presentationImageHandler,
            onSlideChanged = presentationSession::reportSlide,
            onExit = presentationSession::exit,
        )
        return
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (state.messageTone == UiMessageTone.Error) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (state.messageTone == UiMessageTone.Error) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) { insets ->
        Surface(Modifier.fillMaxSize().padding(insets), color = MaterialTheme.colorScheme.background) {
            when {
                presentationMode == PresentationMode.ContinuousPreview && state.selected != null -> EditorScreen(
                    state = state,
                    editorSettings = editorSettings,
                    onBack = viewModel::closeNote,
                    onDraftChanged = viewModel::updateDraft,
                    onSelectionChanged = viewModel::updateSelection,
                    onPreviewChanged = viewModel::setPreview,
                    onTypeset = viewModel::typesetDraft,
                    onSave = viewModel::save,
                    continuousPreparation = continuousPreparation,
                    previewController = previewController,
                    previewImageHandler = presentationImageHandler,
                    fullscreenPreview = true,
                    onFullscreenPreview = {},
                    onExitFullscreenPreview = presentationSession::exit,
                    onSlidePresentation = { presentationSession.enter(PresentationMode.Slides) },
                    onPrepareAttachment = viewModel::prepareAttachment,
                    onAttachmentResult = viewModel::finishAttachmentPicker,
                )
                showSettings && showTrashSettings -> TrashSettingsScreen(
                    state = state,
                    onRestore = viewModel::restore,
                    onPermanentlyDelete = viewModel::permanentlyDelete,
                    onBack = { showTrashSettings = false },
                )
                showSettings -> SettingsScreen(
                    settings = editorSettings,
                    appLanguage = appLanguage,
                    state = state,
                    onFontChanged = onFontChanged,
                    onFontSizeChanged = onFontSizeChanged,
                    onThemeModeChanged = onThemeModeChanged,
                    onLanguageChanged = onLanguageChanged,
                    onImportLibrary = launchImport,
                    onExportLibrary = launchExport,
                    onTrash = { showTrashSettings = true },
                    onGitSettings = { showGitSettings = true },
                    onGitSync = viewModel::syncNow,
                    onResolveConflict = { showGitConflict = true },
                    onBack = {
                        showTrashSettings = false
                        showSettings = false
                    },
                )
                state.selected != null && twoPane -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(400.dp).fillMaxHeight()) {
                        LibraryScreen(
                            state = state,
                            onRefresh = viewModel::reload,
                            onQueryChanged = viewModel::updateQuery,
                            onOpenNote = viewModel::openNote,
                            onOpenFolder = viewModel::openFolder,
                            onOpenFolderPath = viewModel::openFolderPath,
                            onNavigateUp = viewModel::navigateUp,
                            onCreateNote = viewModel::createNote,
                            onCreateFolder = viewModel::createFolder,
                            onRenameNote = viewModel::renameNote,
                            onRenameFolder = viewModel::renameFolder,
                            onMoveToTrash = viewModel::moveToTrash,
                            onMoveFolderToTrash = viewModel::moveFolderToTrash,
                            onTogglePinned = viewModel::togglePinned,
                            onResolveConflict = { showGitConflict = true },
                            onSettings = { showSettings = true },
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .24f))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        EditorScreen(
                            state = state,
                            editorSettings = editorSettings,
                            onBack = viewModel::closeNote,
                            onDraftChanged = viewModel::updateDraft,
                            onSelectionChanged = viewModel::updateSelection,
                            onPreviewChanged = { enabled ->
                                if (enabled) previewController.markRequested("inline")
                                viewModel.setPreview(enabled)
                            },
                            onTypeset = viewModel::typesetDraft,
                            onSave = viewModel::save,
                            continuousPreparation = continuousPreparation,
                            previewController = previewController,
                            previewImageHandler = presentationImageHandler,
                            fullscreenPreview = false,
                            onFullscreenPreview = {
                                previewController.markRequested("fullscreen")
                                presentationSession.enter(PresentationMode.ContinuousPreview)
                            },
                            onExitFullscreenPreview = presentationSession::exit,
                            onSlidePresentation = { presentationSession.enter(PresentationMode.Slides) },
                            onPrepareAttachment = viewModel::prepareAttachment,
                            onAttachmentResult = viewModel::finishAttachmentPicker,
                        )
                    }
                }
                state.selected != null -> EditorScreen(
                    state = state,
                    editorSettings = editorSettings,
                    onBack = viewModel::closeNote,
                    onDraftChanged = viewModel::updateDraft,
                    onSelectionChanged = viewModel::updateSelection,
                    onPreviewChanged = { enabled ->
                        if (enabled) previewController.markRequested("inline")
                        viewModel.setPreview(enabled)
                    },
                    onTypeset = viewModel::typesetDraft,
                    onSave = viewModel::save,
                    continuousPreparation = continuousPreparation,
                    previewController = previewController,
                    previewImageHandler = presentationImageHandler,
                    fullscreenPreview = presentationMode == PresentationMode.ContinuousPreview,
                    onFullscreenPreview = {
                        previewController.markRequested("fullscreen")
                        presentationSession.enter(PresentationMode.ContinuousPreview)
                    },
                    onExitFullscreenPreview = presentationSession::exit,
                    onSlidePresentation = { presentationSession.enter(PresentationMode.Slides) },
                    onPrepareAttachment = viewModel::prepareAttachment,
                    onAttachmentResult = viewModel::finishAttachmentPicker,
                )
                else -> LibraryScreen(
                    state = state,
                    onRefresh = viewModel::reload,
                    onQueryChanged = viewModel::updateQuery,
                    onOpenNote = viewModel::openNote,
                    onOpenFolder = viewModel::openFolder,
                    onOpenFolderPath = viewModel::openFolderPath,
                    onNavigateUp = viewModel::navigateUp,
                    onCreateNote = viewModel::createNote,
                    onCreateFolder = viewModel::createFolder,
                    onRenameNote = viewModel::renameNote,
                    onRenameFolder = viewModel::renameFolder,
                    onMoveToTrash = viewModel::moveToTrash,
                    onMoveFolderToTrash = viewModel::moveFolderToTrash,
                    onTogglePinned = viewModel::togglePinned,
                    onResolveConflict = { showGitConflict = true },
                    onSettings = { showSettings = true },
                )
            }
            val busy = state.loading || state.saving || state.mutating || state.attaching || state.syncing
            val busyLabel = when {
                state.syncing -> stringResource(R.string.git_syncing)
                state.saving -> stringResource(R.string.save)
                state.attaching -> stringResource(R.string.importing_attachment)
                state.mutating -> stringResource(R.string.updating_library)
                else -> stringResource(R.string.loading)
            }
            DelayedLoadingOverlay(busy = busy, label = busyLabel, stateMachine = busyOverlay)
        }
    }

    if (showGitSettings) {
        GitSyncSettingsDialog(
            current = state.gitConfig,
            username = state.gitUsername,
            hasStoredToken = state.hasGitCredentials,
            onDismiss = { showGitSettings = false },
            onSave = { config, username, token ->
                showGitSettings = false
                viewModel.saveGitSettings(config, username, token)
            },
        )
    }
    state.gitConflict?.takeIf { showGitConflict }?.let { conflict ->
        GitConflictDialog(
            details = conflict,
            onLater = { showGitConflict = false },
            onKeepLocalUnrelated = {
                showGitConflict = false
                viewModel.keepLocalUnrelatedGitHistory()
            },
            onResolve = { choices ->
                showGitConflict = false
                viewModel.resolveGitConflict(choices)
            },
        )
    }
}

@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    onRefresh: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenNote: (LibraryNote) -> Unit,
    onOpenFolder: (LibraryFolder) -> Unit,
    onOpenFolderPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateNote: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameNote: (LibraryNote, String) -> Unit,
    onRenameFolder: (LibraryFolder, String) -> Unit,
    onMoveToTrash: (LibraryNote) -> Unit,
    onMoveFolderToTrash: (LibraryFolder) -> Unit,
    onTogglePinned: (LibraryNote) -> Unit,
    onResolveConflict: () -> Unit,
    onSettings: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renamingNote by remember { mutableStateOf<LibraryNote?>(null) }
    var renamingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var trashingNote by remember { mutableStateOf<LibraryNote?>(null) }
    var trashingFolder by remember { mutableStateOf<LibraryFolder?>(null) }

    BackHandler(enabled = !state.currentFolder.isRoot, onBack = onNavigateUp)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bird),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "MiaoYan",
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showCreateFolderDialog = true },
                    modifier = Modifier.size(48.dp).testTag("create-folder"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_create_folder),
                        contentDescription = stringResource(R.string.new_folder),
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                }
            }
            if (state.gitConflict != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.git_conflict_pending),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onResolveConflict) {
                        Text(stringResource(R.string.git_resolve))
                    }
                }
            }
            FolderBreadcrumb(
                relativePath = state.currentFolder.relativePath,
                onNavigateUp = onNavigateUp,
                onOpenFolderPath = onOpenFolderPath,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_notes)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            if (state.visibleFolders.isEmpty() && state.visibleNotes.isEmpty() && !state.loading) {
                if (state.folders.isEmpty() && state.notes.isEmpty() && state.query.isBlank()) {
                    if (state.currentFolder.isRoot) EmptyLibraryContent() else EmptyLibraryMessage(R.string.folder_empty)
                } else {
                    EmptyLibraryMessage(R.string.no_notes)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.visibleFolders, key = { "folder:${it.id}" }) { folder ->
                        FolderRow(
                            folder = folder,
                            onClick = { onOpenFolder(folder) },
                            onRename = { renamingFolder = folder },
                            onTrash = { trashingFolder = folder },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = .16f),
                        )
                    }
                    items(state.visibleNotes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            pinned = note.relativePath in state.pinnedPaths,
                            onClick = { onOpenNote(note) },
                            onTogglePinned = { onTogglePinned(note) },
                            onRename = { renamingNote = note },
                            onTrash = { trashingNote = note },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = .16f),
                        )
                    }
                }
            }
        }
        if (!state.loading && !state.mutating && !state.syncing) {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(56.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_new_note),
                    contentDescription = stringResource(R.string.new_note),
                )
            }
        }
    }

    if (showCreateDialog) {
        NoteNameDialog(
            title = stringResource(R.string.new_note),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> showCreateDialog = false; onCreateNote(name) },
        )
    }
    if (showCreateFolderDialog) {
        FolderNameDialog(
            title = stringResource(R.string.new_folder),
            initialName = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name -> showCreateFolderDialog = false; onCreateFolder(name) },
        )
    }
    renamingNote?.let { note ->
        NoteNameDialog(
            title = stringResource(R.string.rename_note),
            initialName = note.displayName,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = { renamingNote = null },
            onConfirm = { name -> renamingNote = null; onRenameNote(note, name) },
        )
    }
    renamingFolder?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.rename_folder),
            initialName = folder.displayName,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = { renamingFolder = null },
            onConfirm = { name -> renamingFolder = null; onRenameFolder(folder, name) },
        )
    }
    trashingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { trashingNote = null },
            title = { Text(stringResource(R.string.move_to_trash_title)) },
            text = { Text(stringResource(R.string.move_to_trash_message, note.displayName)) },
            confirmButton = {
                TextButton(onClick = { trashingNote = null; onMoveToTrash(note) }) {
                    Text(stringResource(R.string.move_to_trash))
                }
            },
            dismissButton = {
                TextButton(onClick = { trashingNote = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    trashingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { trashingFolder = null },
            title = { Text(stringResource(R.string.move_folder_to_trash_title)) },
            text = { Text(stringResource(R.string.move_folder_to_trash_message, folder.displayName)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("folder-trash-confirm"),
                    onClick = { trashingFolder = null; onMoveFolderToTrash(folder) },
                ) {
                    Text(stringResource(R.string.move_to_trash))
                }
            },
            dismissButton = {
                TextButton(onClick = { trashingFolder = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FolderBreadcrumb(
    relativePath: String,
    onNavigateUp: () -> Unit,
    onOpenFolderPath: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (relativePath.isNotEmpty()) {
            IconButton(onClick = onNavigateUp, modifier = Modifier.size(40.dp).testTag("folder-back")) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.offset(x = 4.dp),
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Box(
            Modifier.height(40.dp).clickable { onOpenFolderPath("") },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(stringResource(R.string.library_root), maxLines = 1)
        }
        var accumulated = ""
        relativePath.split('/').filter(String::isNotEmpty).forEach { segment ->
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            val destination = accumulated
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(24.dp).padding(3.dp),
            )
            Box(
                Modifier.height(40.dp).clickable { onOpenFolderPath(destination) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    segment,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: LibraryFolder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
) {
    var actionsExpanded by remember(folder.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("folder-row:${folder.relativePath}")
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_folder_open),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                folder.displayName,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { actionsExpanded = true },
                modifier = Modifier.testTag("folder-actions:${folder.relativePath}"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more_actions),
                )
            }
            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = { actionsExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trash)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_trash), contentDescription = null) },
                    onClick = { actionsExpanded = false; onTrash() },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryContent() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.miaoyan_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = androidx.compose.ui.graphics.Color.Unspecified,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.empty_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val result = NotePathPolicy.validateFolderName(name)
    val error = (result as? NameResult.Invalid)?.error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().testTag("folder-name"),
                    label = { Text(stringResource(R.string.folder_name)) },
                    isError = error != null,
                    singleLine = true,
                )
                error?.let {
                    Text(
                        folderNameErrorMessage(it),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("folder-name-confirm"),
                onClick = { onConfirm((result as NameResult.Valid).name) },
                enabled = result is NameResult.Valid,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun folderNameErrorMessage(error: NameError): String = stringResource(
    when (error) {
        NameError.EMPTY -> R.string.folder_name_error_empty
        NameError.RESERVED -> R.string.folder_name_error_reserved
        NameError.HIDDEN -> R.string.folder_name_error_hidden
        NameError.INVALID_CHARACTERS -> R.string.folder_name_error_characters
        NameError.UNSUPPORTED_EXTENSION -> R.string.folder_name_error_characters
        NameError.TOO_LONG -> R.string.folder_name_error_too_long
    },
)

@Composable
private fun NoteRow(
    note: LibraryNote,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
) {
    var actionsExpanded by remember(note.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("note-row:${note.relativePath}")
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 6.dp)) {
            Text(
                note.displayName.substringBeforeLast('.'),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val folder = note.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                if (folder.isNotEmpty()) {
                    Text(
                        folder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (note.modifiedAtMillis > 0) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(note.modifiedAtMillis)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { actionsExpanded = true },
                modifier = Modifier.testTag("note-actions:${note.relativePath}"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more_actions),
                )
            }
            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (pinned) R.string.unpin else R.string.pin)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_push_pin), contentDescription = null) },
                    onClick = { actionsExpanded = false; onTogglePinned() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = { actionsExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trash)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_trash), contentDescription = null) },
                    onClick = { actionsExpanded = false; onTrash() },
                )
            }
        }
    }
}

@Composable
private fun TrashRow(note: TrashedNote, onRestore: () -> Unit, onPermanentlyDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (note.kind == LibraryItemKind.FOLDER) {
            Icon(
                painterResource(R.drawable.ic_folder_open),
                contentDescription = stringResource(R.string.folder),
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(note.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            note.originalRelativePath?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onRestore) { Text(stringResource(R.string.restore)) }
        TextButton(onClick = onPermanentlyDelete) {
            Text(
                stringResource(R.string.delete_permanently),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyLibraryMessage(@StringRes message: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val result = NotePathPolicy.validateNoteName(name)
    val error = (result as? NameResult.Invalid)?.error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.note_name)) },
                    isError = error != null,
                    singleLine = true,
                )
                error?.let {
                    Text(
                        nameErrorMessage(it),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm((result as NameResult.Valid).name) },
                enabled = result is NameResult.Valid,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun nameErrorMessage(error: NameError): String = stringResource(
    when (error) {
        NameError.EMPTY -> R.string.name_error_empty
        NameError.RESERVED -> R.string.name_error_reserved
        NameError.HIDDEN -> R.string.name_error_hidden
        NameError.INVALID_CHARACTERS -> R.string.name_error_characters
        NameError.UNSUPPORTED_EXTENSION -> R.string.name_error_extension
        NameError.TOO_LONG -> R.string.name_error_too_long
    }
)

@Composable
private fun EditorScreen(
    state: LibraryUiState,
    editorSettings: EditorSettings,
    onBack: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSelectionChanged: (Int, Int) -> Unit,
    onPreviewChanged: (Boolean) -> Unit,
    onTypeset: () -> Unit,
    onSave: () -> Unit,
    continuousPreparation: ContinuousPreviewPreparation,
    previewController: PreviewWebViewController,
    previewImageHandler: PresentationImageHandler,
    fullscreenPreview: Boolean,
    onFullscreenPreview: () -> Unit,
    onExitFullscreenPreview: () -> Unit,
    onSlidePresentation: () -> Unit,
    onPrepareAttachment: (AttachmentKind) -> AttachmentRequest?,
    onAttachmentResult: (AttachmentKind, android.net.Uri?) -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onAttachmentResult(AttachmentKind.Image, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onAttachmentResult(AttachmentKind.File, uri)
    }
    val canEditDraft = !state.preview && !state.loading && !state.saving && !state.mutating && !state.syncing &&
        !state.formatting && !state.attaching && !state.attachmentPickerOpen
    val requestBack = { if (state.dirty) showDiscardDialog = true else onBack() }
    BackHandler(enabled = !fullscreenPreview, onBack = requestBack)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columnModifier = if (fullscreenPreview) {
            Modifier.fillMaxSize()
        } else {
            Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()
        }
        Column(columnModifier) {
            if (!fullscreenPreview) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = requestBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Text(
                        state.selected?.note?.displayName.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onFullscreenPreview) {
                        Icon(
                            painterResource(R.drawable.ic_videocam),
                            contentDescription = stringResource(R.string.fullscreen_preview),
                        )
                    }
                    IconButton(onClick = onSlidePresentation) {
                        Icon(
                            painterResource(R.drawable.ic_slideshow),
                            contentDescription = stringResource(R.string.slide_presentation),
                        )
                    }
                    IconButton(onClick = onTypeset, enabled = canEditDraft) {
                        if (state.formatting) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_typesetting),
                                contentDescription = stringResource(R.string.typesetting),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showAttachmentMenu = true }, enabled = canEditDraft) {
                            Icon(
                                painterResource(R.drawable.ic_attach_file),
                                contentDescription = stringResource(R.string.insert_attachment),
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = { showAttachmentMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.insert_image)) },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_insert_image), contentDescription = null)
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    if (onPrepareAttachment(AttachmentKind.Image) != null) {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.insert_file)) },
                                leadingIcon = {
                                    Icon(painterResource(R.drawable.ic_attach_file), contentDescription = null)
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    if (onPrepareAttachment(AttachmentKind.File) != null) {
                                        filePicker.launch(arrayOf("*/*"))
                                    }
                                },
                            )
                        }
                    }
                    IconButton(onClick = onSave, enabled = state.dirty && canEditDraft) {
                        Icon(painterResource(R.drawable.ic_save), contentDescription = stringResource(R.string.save))
                    }
                }
                ModeSwitcher(preview = state.preview, onPreviewChanged = onPreviewChanged)
            }
            Box(Modifier.fillMaxSize()) {
                ContinuousPreview(
                    preparation = continuousPreparation,
                    controller = previewController,
                    imageHandler = previewImageHandler,
                    active = state.preview || fullscreenPreview,
                    fullscreen = fullscreenPreview,
                    onExitFullscreen = onExitFullscreenPreview,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!state.preview && !fullscreenPreview) {
                    PlatformMarkdownEditor(
                        text = state.draft,
                        selectionStart = state.selectionStart,
                        selectionEnd = state.selectionEnd,
                        onTextChanged = onDraftChanged,
                        onSelectionChanged = onSelectionChanged,
                        editorSettings = editorSettings,
                        modifier = Modifier.fillMaxSize().testTag("markdown_editor"),
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }
}

@Composable
private fun ModeSwitcher(preview: Boolean, onPreviewChanged: (Boolean) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        ModeButton(
            label = stringResource(R.string.edit),
            icon = R.drawable.ic_edit,
            selected = !preview,
            modifier = Modifier.weight(1f),
            onClick = { onPreviewChanged(false) },
        )
        ModeButton(
            label = stringResource(R.string.preview),
            icon = R.drawable.ic_visibility,
            selected = preview,
            modifier = Modifier.weight(1f),
            onClick = { onPreviewChanged(true) },
        )
    }
}

@Composable
private fun ModeButton(label: String, icon: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(9.dp),
            )
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun PlatformMarkdownEditor(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    onTextChanged: (String) -> Unit,
    onSelectionChanged: (Int, Int) -> Unit,
    editorSettings: EditorSettings,
    modifier: Modifier = Modifier,
) {
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    val syntaxPalette = MarkdownEditorPalettes.forDarkMode(darkMode)
    val contentColor = syntaxPalette.body
    val backgroundColor =
        (if (darkMode) MiaoYanColors.EditorBackgroundDark else MiaoYanColors.EditorBackgroundLight).toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SelectionAwareEditText(context).apply {
                gravity = Gravity.TOP or Gravity.START
                val padding = editorPaddingPixels(resources.displayMetrics.density)
                setPadding(padding.horizontal, padding.top, padding.horizontal, padding.bottom)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                includeFontPadding = false
                setHorizontallyScrolling(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(text)
                setSelection(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
                selectionListener = onSelectionChanged
                updateSyntaxPalette(syntaxPalette)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                        noteSyntaxChange(start, count)
                    }
                    override fun afterTextChanged(value: Editable?) {
                        onTextChanged(value?.toString().orEmpty())
                        scheduleSyntaxHighlight()
                    }
                })
                scheduleSyntaxHighlight()
            }
        },
        update = { editor ->
            editor.setTextColor(contentColor)
            editor.setBackgroundColor(backgroundColor)
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorSettings.fontSizeSp.toFloat())
            editor.typeface = editorTypeface(editorSettings.font, editor)
            editor.letterSpacing = 0.5f / editorSettings.fontSizeSp
            editor.setLineSpacing(3f * editor.resources.displayMetrics.density, 1.3f)
            editor.updateSyntaxPalette(syntaxPalette)
            val composing = BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            if (!composing && editor.text.toString() != text) {
                val selection = editor.selectionStart.coerceIn(0, text.length)
                editor.setText(text)
                editor.setSelection(selection)
                editor.scheduleSyntaxHighlight()
            }
            if (!composing) {
                val start = selectionStart.coerceIn(0, editor.text.length)
                val end = selectionEnd.coerceIn(0, editor.text.length)
                if (editor.selectionStart != start || editor.selectionEnd != end) editor.setSelection(start, end)
            }
        },
    )
}

private class SelectionAwareEditText(context: Context) : EditText(context) {
    var selectionListener: ((Int, Int) -> Unit)? = null
    private var syntaxPalette: MarkdownSyntaxPalette = MarkdownEditorPalettes.Light
    private var textVersion = 0
    private var highlightedVersion = -1
    private var paletteNeedsRefresh = true
    private var pendingHighlightStart: Int? = null
    private var pendingHighlightEnd: Int? = null
    private val syntaxHighlightRunnable = Runnable {
        val editable = text ?: return@Runnable
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) return@Runnable
        if (highlightedVersion == textVersion && !paletteNeedsRefresh) return@Runnable
        val start = pendingHighlightStart ?: selectionStart.coerceAtLeast(0)
        val end = pendingHighlightEnd ?: selectionEnd.coerceAtLeast(start)
        if (
            MarkdownSyntaxHighlighter.highlight(
                editable = editable,
                palette = syntaxPalette,
                changedStart = start,
                changedEndExclusive = end,
                clearAll = paletteNeedsRefresh,
            )
        ) {
            highlightedVersion = textVersion
            paletteNeedsRefresh = false
            pendingHighlightStart = null
            pendingHighlightEnd = null
        }
    }

    fun updateSyntaxPalette(value: MarkdownSyntaxPalette) {
        if (syntaxPalette == value) return
        syntaxPalette = value
        paletteNeedsRefresh = true
        scheduleSyntaxHighlight()
    }

    fun noteSyntaxChange(start: Int, count: Int) {
        textVersion++
        val end = (start + count).coerceAtLeast(start)
        pendingHighlightStart = minOf(pendingHighlightStart ?: start, start)
        pendingHighlightEnd = maxOf(pendingHighlightEnd ?: end, end)
    }

    fun scheduleSyntaxHighlight() {
        removeCallbacks(syntaxHighlightRunnable)
        postDelayed(syntaxHighlightRunnable, SYNTAX_HIGHLIGHT_DELAY_MILLIS)
    }

    override fun onSelectionChanged(selectionStart: Int, selectionEnd: Int) {
        super.onSelectionChanged(selectionStart, selectionEnd)
        selectionListener?.invoke(selectionStart, selectionEnd)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val inputConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(inputConnection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { scheduleSyntaxHighlight() }

            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { scheduleSyntaxHighlight() }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(syntaxHighlightRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SYNTAX_HIGHLIGHT_DELAY_MILLIS = 32L
    }
}

internal data class EditorPaddingPixels(
    val horizontal: Int,
    val top: Int,
    val bottom: Int,
)

internal fun editorPaddingPixels(density: Float): EditorPaddingPixels = EditorPaddingPixels(
    horizontal = (20f * density).roundToInt(),
    top = (18f * density).roundToInt(),
    bottom = (48f * density).roundToInt(),
)

@Composable
internal fun TrashSettingsScreen(
    state: LibraryUiState,
    onRestore: (TrashedNote) -> Unit,
    onPermanentlyDelete: (TrashedNote) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<TrashedNote?>(null) }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    stringResource(R.string.trash),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.trash.isEmpty() && !state.loading) {
                EmptyLibraryMessage(R.string.trash_empty)
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(state.trash, key = { it.trashRelativePath }) { note ->
                        TrashRow(
                            note = note,
                            onRestore = { onRestore(note) },
                            onPermanentlyDelete = { pendingDelete = note },
                        )
                        HorizontalDivider(
                            Modifier.padding(start = 20.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = .16f),
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    stringResource(
                        if (note.kind == LibraryItemKind.FOLDER) {
                            R.string.delete_folder_permanently_title
                        } else {
                            R.string.delete_permanently_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (note.kind == LibraryItemKind.FOLDER) {
                            R.string.delete_folder_permanently_message
                        } else {
                            R.string.delete_permanently_message
                        },
                        note.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("trash-delete-confirm"),
                    onClick = {
                        pendingDelete = null
                        onPermanentlyDelete(note)
                    },
                ) {
                    Text(
                        stringResource(R.string.delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    settings: EditorSettings,
    appLanguage: AppLanguage,
    state: LibraryUiState,
    onFontChanged: (EditorFont) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
    onImportLibrary: () -> Unit,
    onExportLibrary: () -> Unit,
    onTrash: () -> Unit,
    onGitSettings: () -> Unit,
    onGitSync: () -> Unit,
    onResolveConflict: () -> Unit,
    onBack: () -> Unit,
) {
    var showLicense by remember { mutableStateOf(false) }
    var showJgitLicense by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.settings),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SettingsSection(
                    title = stringResource(R.string.storage),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    StorageWarning(state)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = R.drawable.ic_library_import,
                        title = stringResource(R.string.import_library),
                        detail = stringResource(R.string.import_library_detail),
                        onClick = onImportLibrary,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = R.drawable.ic_library_export,
                        title = stringResource(R.string.export_library),
                        detail = stringResource(R.string.export_library_detail),
                        onClick = onExportLibrary,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = R.drawable.ic_trash,
                        title = stringResource(R.string.trash),
                        detail = stringResource(R.string.trash_detail, state.trash.size),
                        onClick = onTrash,
                    )
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.git_settings),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    SettingsActionRow(
                        icon = R.drawable.ic_settings,
                        title = stringResource(R.string.git_settings_title),
                        detail = stringResource(
                            if (state.hasValidGitSetup) {
                                R.string.git_configured_detail
                            } else {
                                R.string.git_not_configured_detail
                            },
                        ),
                        onClick = onGitSettings,
                    )
                    if (state.hasValidGitSetup) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            icon = R.drawable.ic_refresh,
                            title = stringResource(R.string.git_sync_now),
                            detail = stringResource(R.string.git_sync_now_detail),
                            onClick = onGitSync,
                        )
                    }
                    if (state.gitConflict != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            icon = R.drawable.ic_storage_warning,
                            title = stringResource(R.string.git_resolve),
                            detail = stringResource(R.string.git_conflict_pending),
                            onClick = onResolveConflict,
                        )
                    }
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.appearance),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    SettingsDropdown(
                        label = stringResource(R.string.theme),
                        selected = themeModeLabel(settings.themeMode),
                        options = ThemeMode.entries.map { it to themeModeLabel(it) },
                        onSelected = onThemeModeChanged,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsDropdown(
                        label = stringResource(R.string.language),
                        selected = appLanguageLabel(appLanguage),
                        options = AppLanguage.entries.map { it to appLanguageLabel(it) },
                        onSelected = onLanguageChanged,
                    )
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.typography),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    SettingsDropdown(
                        label = stringResource(R.string.font),
                        selected = fontLabel(settings.font),
                        options = EditorFont.entries.map { it to fontLabel(it) },
                        onSelected = onFontChanged,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsDropdown(
                        label = stringResource(R.string.font_size),
                        selected = stringResource(R.string.font_size_value, settings.fontSizeSp),
                        options = EDITOR_FONT_SIZES.map { it to stringResource(R.string.font_size_value, it) },
                        onSelected = onFontSizeChanged,
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.typography_preview_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.typography_preview_sample),
                            fontFamily = composeFontFamily(settings.font),
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = (settings.fontSizeSp * 1.55f).sp,
                        )
                    }
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.open_source_licenses),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    LicenseRow(
                        title = stringResource(R.string.jetbrains_mono_attribution),
                        onClick = { showLicense = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LicenseRow(
                        title = stringResource(R.string.jgit_attribution),
                        onClick = { showJgitLicense = true },
                    )
                }
            }
        }
        }
    }

    if (showLicense) {
        val resources = LocalResources.current
        val license = remember(resources) {
            resources.openRawResource(R.raw.jetbrains_mono_ofl).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.open_source_license)) },
            text = {
                Text(
                    license,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
    if (showJgitLicense) {
        val resources = LocalResources.current
        val notice = remember(resources) {
            resources.openRawResource(R.raw.jgit_notice).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showJgitLicense = false },
            title = { Text(stringResource(R.string.jgit_license)) },
            text = {
                Text(
                    notice,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { showJgitLicense = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun StorageWarning(state: LibraryUiState) {
    val backupStatus = LibraryBackupStatusPolicy.evaluate(state.hasValidGitSetup, state.gitSyncStatus)
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val presentation = when (backupStatus.kind) {
        LibraryBackupStatusKind.Unconfigured -> StorageStatusPresentation(
            icon = R.drawable.ic_storage_warning,
            tint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.local_storage_warning_title),
            detail = stringResource(R.string.local_storage_warning_detail),
        )
        LibraryBackupStatusKind.FirstSyncRequired -> StorageStatusPresentation(
            icon = R.drawable.ic_refresh,
            tint = androidx.compose.ui.graphics.Color(if (darkTheme) 0xFFFFCC80 else 0xFFF57C00),
            title = stringResource(R.string.git_backup_first_sync_title),
            detail = stringResource(R.string.git_backup_first_sync_detail),
        )
        LibraryBackupStatusKind.Synced -> StorageStatusPresentation(
            icon = R.drawable.ic_check_circle,
            tint = androidx.compose.ui.graphics.Color(if (darkTheme) 0xFF81C784 else 0xFF2E7D32),
            title = stringResource(R.string.git_backup_synced_title),
            detail = stringResource(
                R.string.git_backup_synced_detail,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(requireNotNull(backupStatus.lastSuccessAtMillis))),
            ),
        )
        LibraryBackupStatusKind.LocallyModified -> StorageStatusPresentation(
            icon = R.drawable.ic_storage_warning,
            tint = androidx.compose.ui.graphics.Color(if (darkTheme) 0xFFFFCC80 else 0xFFF57C00),
            title = stringResource(R.string.git_backup_dirty_title),
            detail = stringResource(R.string.git_backup_dirty_detail),
        )
        LibraryBackupStatusKind.Failed -> StorageStatusPresentation(
            icon = R.drawable.ic_storage_warning,
            tint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.git_backup_failed_title),
            detail = stringResource(R.string.git_backup_failed_detail),
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(presentation.icon),
            contentDescription = null,
            tint = presentation.tint,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                presentation.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                presentation.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class StorageStatusPresentation(
    @param:DrawableRes
    val icon: Int,
    val tint: androidx.compose.ui.graphics.Color,
    val title: String,
    val detail: String,
)

@Composable
private fun SettingsActionRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Card { content() }
    }
}

@Composable
private fun <T> SettingsDropdown(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(selected, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun fontLabel(font: EditorFont): String = when (font) {
    EditorFont.SYSTEM_SANS -> stringResource(R.string.font_system_sans)
    EditorFont.SYSTEM_SERIF -> stringResource(R.string.font_system_serif)
    EditorFont.SYSTEM_MONOSPACE -> stringResource(R.string.font_system_monospace)
    EditorFont.JETBRAINS_MONO -> stringResource(R.string.font_jetbrains_mono)
}

@Composable
private fun themeModeLabel(themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.AUTO_SYSTEM -> stringResource(R.string.theme_auto_system)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
}

@Composable
private fun appLanguageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.AUTO_SYSTEM -> stringResource(R.string.language_auto_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.RUSSIAN -> stringResource(R.string.language_russian)
}

private fun composeFontFamily(font: EditorFont): FontFamily = when (font) {
    EditorFont.SYSTEM_SANS -> FontFamily.SansSerif
    EditorFont.SYSTEM_SERIF -> FontFamily.Serif
    EditorFont.SYSTEM_MONOSPACE -> FontFamily.Monospace
    EditorFont.JETBRAINS_MONO -> JetBrainsMonoFamily
}

private fun editorTypeface(font: EditorFont, editor: EditText): Typeface = when (font) {
    EditorFont.SYSTEM_SANS -> Typeface.create("sans-serif", Typeface.NORMAL)
    EditorFont.SYSTEM_SERIF -> Typeface.create("serif", Typeface.NORMAL)
    EditorFont.SYSTEM_MONOSPACE -> Typeface.create("monospace", Typeface.NORMAL)
    EditorFont.JETBRAINS_MONO -> editor.resources.getFont(R.font.jetbrains_mono_regular)
}

@Composable
private fun LoadingOverlay(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .22f))
            .testTag(BusyOverlayTestTag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 8.dp) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(label)
            }
        }
    }
}

internal const val BusyOverlayTestTag = "busy-overlay"

@Composable
internal fun DelayedLoadingOverlay(
    busy: Boolean,
    label: String,
    stateMachine: BusyOverlayStateMachine = remember { BusyOverlayStateMachine() },
) {
    var visible by remember(stateMachine) { mutableStateOf(stateMachine.isVisible) }
    LaunchedEffect(busy, stateMachine) {
        var waitMillis = stateMachine.updateBusy(busy)
        visible = stateMachine.isVisible
        while (waitMillis != null) {
            if (waitMillis > 0L) delay(waitMillis)
            waitMillis = stateMachine.advanceToNextDeadline()
            visible = stateMachine.isVisible
        }
    }
    if (visible) LoadingOverlay(label)
}
