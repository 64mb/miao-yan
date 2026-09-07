package com.tw93.miaoyan.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.git.GitConflictChoice
import com.tw93.miaoyan.android.git.GitConflictDetails
import com.tw93.miaoyan.android.git.GitConflictFile
import com.tw93.miaoyan.android.git.GitConflictKind
import java.text.DateFormat
import java.util.Date

@Composable
fun GitConflictDialog(
    details: GitConflictDetails,
    onLater: () -> Unit,
    onKeepLocalUnrelated: () -> Unit,
    onResolve: (Map<String, GitConflictChoice>) -> Unit,
) {
    var choices by remember(details.localCommit, details.remoteCommit) {
        mutableStateOf(details.files.associate { it.path to GitConflictChoice.Local })
    }

    if (details.kind == GitConflictKind.UnrelatedHistory) {
        UnrelatedHistoryDialog(
            details = details,
            onLater = onLater,
            onKeepLocal = onKeepLocalUnrelated,
            onUseRemote = {
                onResolve(details.files.associate { it.path to GitConflictChoice.Remote })
            },
        )
        return
    }

    GitDialogSurface(onDismiss = onLater) {
        Text(stringResource(R.string.git_conflict_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.git_conflict_message),
            modifier = Modifier.padding(top = 14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { choices = details.files.associate { it.path to GitConflictChoice.Local } },
            ) { Text(stringResource(R.string.git_use_all_local)) }
            TextButton(
                onClick = { choices = details.files.associate { it.path to GitConflictChoice.Remote } },
            ) { Text(stringResource(R.string.git_use_all_remote)) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(details.files, key = { it.path }) { file ->
                ConflictFileRow(
                    file = file,
                    choice = choices.getValue(file.path),
                    onChoice = { choice -> choices = choices + (file.path to choice) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .16f))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
        ) {
            TextButton(onClick = onLater) { Text(stringResource(R.string.git_resolve_later)) }
            TextButton(onClick = { onResolve(choices) }) {
                Text(stringResource(R.string.git_continue_sync))
            }
        }
    }
}

@Composable
private fun UnrelatedHistoryDialog(
    details: GitConflictDetails,
    onLater: () -> Unit,
    onKeepLocal: () -> Unit,
    onUseRemote: () -> Unit,
) {
    GitDialogSurface(onDismiss = onLater) {
        Text(stringResource(R.string.git_unrelated_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.git_unrelated_message),
            modifier = Modifier.padding(top = 14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
        ) {
            TextButton(onClick = onKeepLocal) {
                Text(stringResource(R.string.git_keep_local))
            }
            TextButton(onClick = onUseRemote, enabled = details.files.isNotEmpty()) {
                Text(stringResource(R.string.git_replace_with_remote))
            }
        }
    }
}

@Composable
private fun GitDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), content = content)
            }
        }
    }
}

@Composable
private fun ConflictFileRow(
    file: GitConflictFile,
    choice: GitConflictChoice,
    onChoice: (GitConflictChoice) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(file.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConflictChoiceButton(
                label = stringResource(R.string.git_local),
                detail = localTimestamp(file.localExists, file.localModifiedAtMillis),
                selected = choice == GitConflictChoice.Local,
                onClick = { onChoice(GitConflictChoice.Local) },
                modifier = Modifier.weight(1f),
            )
            ConflictChoiceButton(
                label = stringResource(R.string.git_remote),
                detail = remoteTimestamp(file.remoteExists, file.remoteModifiedAtMillis),
                selected = choice == GitConflictChoice.Remote,
                onClick = { onChoice(GitConflictChoice.Remote) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConflictChoiceButton(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 92.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                if (selected) "✓ $label" else label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                detail,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun localTimestamp(exists: Boolean, modifiedAtMillis: Long?): String {
    if (!exists) return stringResource(R.string.git_local_deleted)
    if (modifiedAtMillis == null) return stringResource(R.string.git_local_time_unknown)
    return stringResource(R.string.git_local_modified, formatTimestamp(modifiedAtMillis))
}

@Composable
private fun remoteTimestamp(exists: Boolean, modifiedAtMillis: Long?): String {
    if (!exists) return stringResource(R.string.git_remote_deleted)
    if (modifiedAtMillis == null) return stringResource(R.string.git_remote_time_unknown)
    return stringResource(R.string.git_remote_changed, formatTimestamp(modifiedAtMillis))
}

private fun formatTimestamp(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
