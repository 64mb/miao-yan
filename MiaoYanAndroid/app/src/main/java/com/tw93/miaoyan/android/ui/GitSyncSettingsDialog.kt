package com.tw93.miaoyan.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tw93.miaoyan.android.R
import com.tw93.miaoyan.android.git.GitSyncConfig

@Composable
fun GitSyncSettingsDialog(
    current: GitSyncConfig?,
    username: String,
    hasStoredToken: Boolean,
    onDismiss: () -> Unit,
    onSave: (GitSyncConfig, String, String) -> Unit,
) {
    var repositoryUrl by remember(current) { mutableStateOf(current?.repositoryUrl.orEmpty()) }
    var authorName by remember(current) { mutableStateOf(current?.authorName.orEmpty()) }
    var authorEmail by remember(current) { mutableStateOf(current?.authorEmail.orEmpty()) }
    var editedUsername by remember(username) { mutableStateOf(username) }
    var token by remember { mutableStateOf("") }
    var periodic by remember(current) { mutableStateOf(current?.periodicEnabled ?: false) }
    val canSave = repositoryUrl.isNotBlank() && authorName.isNotBlank() && authorEmail.isNotBlank() &&
        editedUsername.isNotBlank() && (hasStoredToken || token.isNotBlank())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Text(
                        stringResource(R.string.git_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            OutlinedTextField(
                                value = repositoryUrl,
                                onValueChange = { repositoryUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.git_repository_url)) },
                                singleLine = true,
                            )
                            Text(
                                text = stringResource(R.string.git_https_main_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = editedUsername,
                            onValueChange = { editedUsername = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.git_username)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.git_pat)) },
                            placeholder = if (hasStoredToken) {
                                { Text(stringResource(R.string.git_pat_unchanged)) }
                            } else {
                                null
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = authorName,
                            onValueChange = { authorName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.git_author_name)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = authorEmail,
                            onValueChange = { authorEmail = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.git_author_email)) },
                            singleLine = true,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.git_periodic),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.git_periodic_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Switch(checked = periodic, onCheckedChange = { periodic = it })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                onSave(
                                    GitSyncConfig(repositoryUrl, authorName, authorEmail, periodic),
                                    editedUsername,
                                    token,
                                )
                            },
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}
