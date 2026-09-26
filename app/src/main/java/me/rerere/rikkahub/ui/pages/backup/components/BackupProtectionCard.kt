package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BackupSecurityStore

@Composable
fun BackupProtectionCard() {
    val context = LocalContext.current
    val store = remember(context) { BackupSecurityStore(context) }
    var full by remember { mutableStateOf(store.includeCredentials) }
    var dialog by remember { mutableStateOf(false) }
    var editedFull by remember { mutableStateOf(full) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pocket_backup_protection), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(if (full) R.string.pocket_backup_encrypted else R.string.pocket_backup_without_credentials),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                editedFull = full; error = null
                password = runCatching { store.password() }.getOrDefault("")
                dialog = true
            }) { Text(stringResource(R.string.pocket_backup_configure)) }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { if (!saving) { dialog = false; password = "" } },
        title = { Text(stringResource(R.string.pocket_backup_protection)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.pocket_backup_scope))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !editedFull, onClick = { editedFull = false })
                Text(stringResource(R.string.pocket_backup_without_credentials))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = editedFull, onClick = { editedFull = true })
                Text(stringResource(R.string.pocket_backup_encrypted))
            }
            OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.pocket_backup_password)) },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.pocket_backup_password_help), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.pocket_backup_content_notice), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !saving && (!editedFull || password.length >= 8), onClick = {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.save(editedFull, password) }
                    full = editedFull; password = ""; dialog = false
                } catch (e: Exception) { error = e.message }
                finally { saving = false }
            }
        }) { Text(stringResource(R.string.pocket_backup_apply)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { dialog = false; password = "" }) { Text(stringResource(R.string.cancel)) } })
}
