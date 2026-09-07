package com.tw93.miaoyan.android.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
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
import com.tw93.miaoyan.android.data.EDITOR_FONT_SIZES
import com.tw93.miaoyan.android.data.EditorFont
import com.tw93.miaoyan.android.data.EditorSettings
import com.tw93.miaoyan.android.data.LocalFileImageLoader
import com.tw93.miaoyan.android.data.LocalImagePolicy
import com.tw93.miaoyan.android.data.NameError
import com.tw93.miaoyan.android.data.NameResult
import com.tw93.miaoyan.android.data.NotePathPolicy
import com.tw93.miaoyan.android.model.LibraryNote
import com.tw93.miaoyan.android.model.TrashedNote
import com.tw93.miaoyan.android.ui.theme.MiaoYanColors
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val JetBrainsMonoFamily = FontFamily(Font(R.font.jetbrains_mono_regular))

@Composable
fun MiaoYanApp(
    viewModel: LibraryViewModel,
    editorSettings: EditorSettings,
    onFontChanged: (EditorFont) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Surface(Modifier.fillMaxSize().padding(insets), color = MaterialTheme.colorScheme.background) {
            when {
                showSettings -> SettingsScreen(
                    settings = editorSettings,
                    onFontChanged = onFontChanged,
                    onFontSizeChanged = onFontSizeChanged,
                    onImportLibrary = launchImport,
                    onExportLibrary = launchExport,
                    onBack = { showSettings = false },
                )
                state.selected != null -> EditorScreen(
                    state = state,
                    editorSettings = editorSettings,
                    onBack = viewModel::closeNote,
                    onDraftChanged = viewModel::updateDraft,
                    onPreviewChanged = viewModel::setPreview,
                    onSave = viewModel::save,
                    onSettings = { showSettings = true },
                )
                else -> LibraryScreen(
                    state = state,
                    onRefresh = viewModel::reload,
                    onQueryChanged = viewModel::updateQuery,
                    onOpenNote = viewModel::openNote,
                    onCreateNote = viewModel::createNote,
                    onRenameNote = viewModel::renameNote,
                    onMoveToTrash = viewModel::moveToTrash,
                    onRestore = viewModel::restore,
                    onShowTrash = viewModel::showTrash,
                    onTogglePinned = viewModel::togglePinned,
                    onImport = launchImport,
                    onExport = launchExport,
                    onSettings = { showSettings = true },
                )
            }
            if (state.loading || state.saving || state.mutating) {
                val label = when {
                    state.saving -> stringResource(R.string.save)
                    state.mutating -> stringResource(R.string.updating_library)
                    else -> stringResource(R.string.loading)
                }
                LoadingOverlay(label)
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    onRefresh: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenNote: (LibraryNote) -> Unit,
    onCreateNote: (String) -> Unit,
    onRenameNote: (LibraryNote, String) -> Unit,
    onMoveToTrash: (LibraryNote) -> Unit,
    onRestore: (TrashedNote) -> Unit,
    onShowTrash: (Boolean) -> Unit,
    onTogglePinned: (LibraryNote) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingNote by remember { mutableStateOf<LibraryNote?>(null) }
    var trashingNote by remember { mutableStateOf<LibraryNote?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MiaoYan",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.private_library),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!state.showingTrash) {
                    TextButton(onClick = { showCreateDialog = true }) {
                        Text(stringResource(R.string.new_note))
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.import_library)) }
                TextButton(onClick = onExport) { Text(stringResource(R.string.export_library)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onShowTrash(!state.showingTrash) }) {
                    Text(stringResource(if (state.showingTrash) R.string.notes else R.string.trash))
                }
            }
            if (state.showingTrash) {
                if (state.trash.isEmpty() && !state.loading) {
                    EmptyLibraryMessage(R.string.trash_empty)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.trash, key = { it.trashRelativePath }) { note ->
                            TrashRow(note = note, onRestore = { onRestore(note) })
                            HorizontalDivider(
                                Modifier.padding(start = 20.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = .16f),
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_notes)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                if (state.visibleNotes.isEmpty() && !state.loading) {
                    if (state.notes.isEmpty() && state.query.isBlank()) {
                        EmptyLibraryContent(
                            onCreate = { showCreateDialog = true },
                            onImport = onImport,
                        )
                    } else {
                        EmptyLibraryMessage(R.string.no_notes)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
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
                                Modifier.padding(start = 20.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = .16f),
                            )
                        }
                    }
                }
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
    renamingNote?.let { note ->
        NoteNameDialog(
            title = stringResource(R.string.rename_note),
            initialName = note.displayName,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = { renamingNote = null },
            onConfirm = { name -> renamingNote = null; onRenameNote(note, name) },
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
}

@Composable
private fun EmptyLibraryContent(onCreate: () -> Unit, onImport: () -> Unit) {
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
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate) { Text(stringResource(R.string.new_note)) }
            TextButton(onClick = onImport) { Text(stringResource(R.string.import_library)) }
        }
    }
}

@Composable
private fun NoteRow(
    note: LibraryNote,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
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
        TextButton(onClick = onTogglePinned) {
            Text(stringResource(if (pinned) R.string.unpin else R.string.pin))
        }
        TextButton(onClick = onRename) { Text(stringResource(R.string.rename)) }
        TextButton(onClick = onTrash) { Text(stringResource(R.string.trash)) }
    }
}

@Composable
private fun TrashRow(note: TrashedNote, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(note.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            note.originalRelativePath?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onRestore) { Text(stringResource(R.string.restore)) }
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.note_name)) },
                supportingText = { error?.let { Text(nameErrorMessage(it)) } },
                isError = error != null,
                singleLine = true,
            )
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
    onPreviewChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestBack = { if (state.dirty) showDiscardDialog = true else onBack() }
    BackHandler(onBack = requestBack)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = requestBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                state.selected?.note?.displayName.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
            }
            IconButton(onClick = onSave, enabled = state.dirty && !state.saving) {
                Icon(painterResource(R.drawable.ic_save), contentDescription = stringResource(R.string.save))
            }
        }
        ModeSwitcher(preview = state.preview, onPreviewChanged = onPreviewChanged)
        if (state.preview) {
            MarkdownPreview(
                markdown = state.draft,
                noteRelativePath = state.selected?.note?.relativePath,
                editorSettings = editorSettings,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlatformMarkdownEditor(
                text = state.draft,
                onTextChanged = onDraftChanged,
                editorSettings = editorSettings,
                modifier = Modifier.fillMaxSize(),
            )
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
    onTextChanged: (String) -> Unit,
    editorSettings: EditorSettings,
    modifier: Modifier = Modifier,
) {
    val darkMode = androidx.compose.foundation.isSystemInDarkTheme()
    val contentColor = (if (darkMode) MiaoYanColors.EditorTextDark else MiaoYanColors.EditorTextLight).toArgb()
    val backgroundColor =
        (if (darkMode) MiaoYanColors.EditorBackgroundDark else MiaoYanColors.EditorBackgroundLight).toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                gravity = Gravity.TOP or Gravity.START
                setPadding(20, 18, 20, 48)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                includeFontPadding = false
                setHorizontallyScrolling(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(value: Editable?) = onTextChanged(value?.toString().orEmpty())
                })
                setText(text)
                setSelection(text.length)
            }
        },
        update = { editor ->
            editor.setTextColor(contentColor)
            editor.setBackgroundColor(backgroundColor)
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorSettings.fontSizeSp.toFloat())
            editor.typeface = editorTypeface(editorSettings.font, editor)
            editor.letterSpacing = 0.5f / editorSettings.fontSizeSp
            editor.setLineSpacing(3f * editor.resources.displayMetrics.density, 1.3f)
            val composing = BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            if (!composing && editor.text.toString() != text) {
                val selection = editor.selectionStart.coerceIn(0, text.length)
                editor.setText(text)
                editor.setSelection(selection)
            }
        },
    )
}

@Composable
private fun MarkdownPreview(
    markdown: String,
    noteRelativePath: String?,
    editorSettings: EditorSettings,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    var jetBrainsMonoData by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resources) {
        jetBrainsMonoData = withContext(Dispatchers.IO) {
            loadJetBrainsMonoData(resources)
        }
    }
    val html = remember(markdown, darkMode, editorSettings, jetBrainsMonoData) {
        MarkdownRenderer.renderDocument(
            markdown = markdown,
            darkMode = darkMode,
            font = editorSettings.font,
            fontSizeSp = editorSettings.fontSizeSp,
            jetBrainsMonoData = jetBrainsMonoData,
        )
    }
    val canonicalRoot = remember(context) { File(context.filesDir, "libraries/default") }
    val imageScope = remember(canonicalRoot, noteRelativePath) {
        noteRelativePath?.let { LocalImagePolicy.resolveNoteAssetScope(canonicalRoot, it) }
    }
    key(imageScope) {
        val imageLoader = remember(imageScope) { imageScope?.let(::LocalFileImageLoader) }
        AndroidView(
            modifier = modifier,
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.domStorageEnabled = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (request.method != "GET" || request.url.host != LocalImagePolicy.AssetHost) {
                                return blockedWebResourceResponse()
                            }
                            return imageLoader?.load(url) ?: blockedWebResourceResponse()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            val userActivated = request.isForMainFrame && request.hasGesture()
                            if (uri.host != LocalImagePolicy.AssetHost &&
                                PreviewNavigationPolicy.opensExternally(uri.toString(), userActivated)
                            ) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            return true
                        }
                    }
                }
            },
            update = { webView ->
                if (webView.tag != html) {
                    webView.tag = html
                    webView.loadDataWithBaseURL(
                        "https://appassets.androidplatform.net/",
                        html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private fun blockedWebResourceResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Forbidden",
    mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
    java.io.ByteArrayInputStream(ByteArray(0)),
)

@Composable
private fun SettingsScreen(
    settings: EditorSettings,
    onFontChanged: (EditorFont) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
    onImportLibrary: () -> Unit,
    onExportLibrary: () -> Unit,
    onBack: () -> Unit,
) {
    var showLicense by remember { mutableStateOf(false) }
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
                    StorageWarning()
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
                }
            }
            item {
                SettingsSection(
                    title = stringResource(R.string.appearance),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
                ) {
                    SettingsInfoRow(
                        icon = R.drawable.ic_theme_system,
                        title = stringResource(R.string.system_theme),
                        detail = stringResource(R.string.system_theme_detail),
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
                TextButton(onClick = { showLicense = true }) {
                    Text(stringResource(R.string.jetbrains_mono_attribution))
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
}

@Composable
private fun StorageWarning() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(R.drawable.ic_storage_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.local_storage_warning_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.local_storage_warning_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
private fun SettingsInfoRow(icon: Int, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

@SuppressLint("ResourceType")
private fun loadJetBrainsMonoData(resources: Resources): String =
    // Font resources are compiled file resources and can be opened as streams; lint's
    // resource annotation only lists R.raw even though Resources supports both here.
    resources.openRawResource(R.font.jetbrains_mono_regular).use { input ->
        Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
    }

@Composable
private fun LoadingOverlay(label: String) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .22f)),
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
