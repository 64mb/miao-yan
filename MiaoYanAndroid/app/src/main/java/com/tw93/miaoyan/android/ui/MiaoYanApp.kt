package com.tw93.miaoyan.android.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.TextWatcher
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.data.LocalFileImageLoader
import com.tw93.miaoyan.android.data.LocalImagePolicy
import com.tw93.miaoyan.android.model.LibraryNote
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun MiaoYanApp(viewModel: LibraryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::selectRoot)
    }
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
                state.selected != null -> EditorScreen(
                    state = state,
                    onBack = viewModel::closeNote,
                    onDraftChanged = viewModel::updateDraft,
                    onPreviewChanged = viewModel::setPreview,
                    onSave = viewModel::save,
                )
                state.rootUri == null -> WelcomeScreen(onChoose = { chooser.launch(null) })
                else -> LibraryScreen(
                    state = state,
                    onChoose = { chooser.launch(state.rootUri) },
                    onRefresh = viewModel::reload,
                    onQueryChanged = viewModel::updateQuery,
                    onOpenNote = viewModel::openNote,
                )
            }
            if (state.loading || state.saving) {
                LoadingOverlay(if (state.saving) stringResource(R.string.save) else stringResource(R.string.loading))
            }
        }
    }
}

@Composable
private fun WelcomeScreen(onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onChoose) { Text(stringResource(R.string.choose_library)) }
    }
}

@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenNote: (LibraryNote) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("MiaoYan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    state.libraryName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            TextButton(onClick = onChoose) { Text(stringResource(R.string.choose_another_library)) }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_notes)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        if (state.visibleNotes.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_notes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.visibleNotes, key = { it.documentId }) { note ->
                    NoteRow(note = note, onClick = { onOpenNote(note) })
                    HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: LibraryNote, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
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
                Text(folder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (note.modifiedAtMillis > 0) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.modifiedAtMillis)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EditorScreen(
    state: LibraryUiState,
    onBack: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onPreviewChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    var showDiscardDialog by remember { mutableStateOf(false) }
    val requestBack = { if (state.dirty) showDiscardDialog = true else onBack() }
    BackHandler(onBack = requestBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = requestBack) { Text("‹ ${stringResource(R.string.back)}") }
            Text(
                state.selected?.note?.displayName.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onSave, enabled = state.dirty && !state.saving) {
                Text(stringResource(R.string.save))
            }
        }
        ModeSwitcher(preview = state.preview, onPreviewChanged = onPreviewChanged)
        if (state.preview) {
            MarkdownPreview(
                markdown = state.draft,
                noteRelativePath = state.selected?.note?.relativePath,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlatformMarkdownEditor(
                text = state.draft,
                onTextChanged = onDraftChanged,
                modifier = Modifier.fillMaxSize(),
            )
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
            selected = !preview,
            modifier = Modifier.weight(1f),
            onClick = { onPreviewChanged(false) },
        )
        ModeButton(
            label = stringResource(R.string.preview),
            selected = preview,
            modifier = Modifier.weight(1f),
            onClick = { onPreviewChanged(true) },
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(9.dp),
            )
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PlatformMarkdownEditor(text: String, onTextChanged: (String) -> Unit, modifier: Modifier = Modifier) {
    val contentColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                gravity = Gravity.TOP or Gravity.START
                setTextSize(17f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(20, 18, 20, 48)
                setBackgroundColor(AndroidColor.TRANSPARENT)
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
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val darkMode = MaterialTheme.colorScheme.background.luminance() < .5f
    val html = remember(markdown, darkMode) { MarkdownRenderer.renderDocument(markdown, darkMode) }
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
