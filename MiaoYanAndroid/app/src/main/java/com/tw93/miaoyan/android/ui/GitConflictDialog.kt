package com.tw93.miaoyan.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.git.GitConflictChoice
import com.tw93.miaoyan.android.git.GitConflictDetails
import com.tw93.miaoyan.android.git.GitConflictFile
import java.text.DateFormat
import java.util.Date

@Composable
fun GitConflictDialog(
    details: GitConflictDetails,
    onLater: () -> Unit,
    onResolve: (Map<String, GitConflictChoice>) -> Unit,
) {
    var choices by remember(details.localCommit, details.remoteCommit) {
        mutableStateOf(details.files.associate { it.path to GitConflictChoice.Local })
    }

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.git_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.git_conflict_message))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { choices = details.files.associate { it.path to GitConflictChoice.Local } },
                    ) { Text(stringResource(R.string.git_use_all_local)) }
                    TextButton(
                        onClick = { choices = details.files.associate { it.path to GitConflictChoice.Remote } },
                    ) { Text(stringResource(R.string.git_use_all_remote)) }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(details.files, key = { it.path }) { file ->
                        ConflictFileRow(
                            file = file,
                            choice = choices.getValue(file.path),
                            onChoice = { choice -> choices = choices + (file.path to choice) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(choices) }) {
                Text(stringResource(R.string.git_continue_sync))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.git_resolve_later)) }
        },
    )
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
    TextButton(onClick = onClick, modifier = modifier) {
        Column {
            Text(if (selected) "✓ $label" else label, fontWeight = if (selected) FontWeight.Bold else null)
            Text(detail, style = MaterialTheme.typography.labelSmall)
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
