package io.github.yuriyurin.fit3companion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yuriyurin.fit3companion.ble.*

@Composable
internal fun FileManagerScreen(snapshot: BleSnapshot) {
    val state = snapshot.bandFiles
    val enabled = snapshot.sapReady && !state.busy && !snapshot.firmwareFlash.busy && !snapshot.firmwareFlash.checking
    var folder by remember { mutableStateOf(state.folder) }
    var name by remember { mutableStateOf("fit3me.jar") }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var deleting by remember { mutableStateOf<BandFileEntry?>(null) }
    var saving by remember { mutableStateOf<BandFileEntry?>(null) }
    var showUpload by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun operation(kind: String = "list", path: String? = null, uri: Uri? = null, entry: BandFileEntry? = null) {
        runCatching {
            BandFilePaths.validate(folder)
            Fit3ConnectionService.active?.fileOperation(folder, kind, path, uri, entry)
                ?: error("Сервис подключения недоступен")
        }.onFailure { localError = it.message }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selected = uri; showUpload = true }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = saving; saving = null
        if (uri != null && entry != null) operation("download", entry.path, uri)
    }
    LaunchedEffect(snapshot.sapReady) { if (enabled) operation() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.files_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.files_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(folder, { folder = it; localError = null }, label = { Text(stringResource(R.string.files_folder)) }, enabled = enabled,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { BandFilePaths.parent(folder)?.let { folder = it; operation() } }, enabled = enabled && BandFilePaths.parent(folder) != null) { Text(stringResource(R.string.files_up)) }
            Button(onClick = { operation() }, enabled = enabled) { Text(stringResource(R.string.files_refresh)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = enabled && BandFilePaths.writable("$folder/probe")) { Text(stringResource(R.string.files_upload)) }
            OutlinedButton(onClick = { name = "new_folder"; showMkdir = true }, enabled = enabled && state.consoleAvailable != false && BandFilePaths.writable("$folder/probe")) { Text(stringResource(R.string.files_new_folder)) }
        }
        OutlinedButton(onClick = { name = "fit3me.jar"; showDownload = true }, enabled = enabled) { Text(stringResource(R.string.files_download_by_name)) }
        if (state.consoleAvailable == false && state.error == null) Text(stringResource(R.string.files_console_unavailable))
        if (!snapshot.sapReady) Text(stringResource(R.string.files_connect))
        if (state.busy) {
            if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("${AppStrings.translate(state.status)} ${if (state.total > 0) "${state.done} / ${state.total} Б" else ""}")
            TextButton(onClick = { Fit3ConnectionService.active?.cancelFileOperation() }) { Text(stringResource(R.string.files_cancel)) }
        } else if (state.status.isNotEmpty()) Text(state.status)
        (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (folder != state.folder && state.entries.isNotEmpty()) Text(stringResource(R.string.files_choose_refresh))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.entries, key = { it.path }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall, localize = false)
                        Text(if (entry.directory) stringResource(R.string.files_folder) else stringResource(R.string.files_bytes, entry.size ?: 0), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = enabled, onClick = {
                                if (entry.directory) { folder = entry.path; operation() }
                                else { saving = entry; save.launch(entry.name) }
                            }) { Text(if (entry.directory) stringResource(R.string.files_open) else stringResource(R.string.files_download)) }
                            if (BandFilePaths.writable(entry.path)) TextButton(enabled = enabled && state.consoleAvailable != false, onClick = { deleting = entry }) { Text(stringResource(R.string.files_delete)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.files_delete_question, entry.name), localize = false) },
        text = { Text(entry.path) }, confirmButton = { TextButton(onClick = { deleting = null; operation("delete", entry = entry) }, enabled = enabled) { Text(stringResource(R.string.files_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.files_cancel)) } }) }
    if (showUpload || showMkdir || showDownload) AlertDialog(onDismissRequest = { showUpload = false; showMkdir = false; showDownload = false },
        title = { Text(if (showDownload) stringResource(R.string.files_download) else if (showMkdir) stringResource(R.string.files_create_folder) else stringResource(R.string.files_upload_file)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it; localError = null }, label = { Text(stringResource(R.string.files_name)) }, singleLine = true)
            Text("$folder/$name")
            if (showUpload) Text(stringResource(R.string.files_overwrite), color = MaterialTheme.colorScheme.error)
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = enabled, onClick = {
            runCatching {
                require(name.isNotBlank() && !name.contains('/')) { "Введите имя файла" }
                val path = if (showDownload) BandFilePaths.validate("$folder/$name") else BandFilePaths.destination(folder, name)
                require(folder != state.folder || state.entries.none { it.path == path && it.directory }) { "Папка с таким именем уже существует" }
                path
            }.fold({ path ->
                if (showDownload) { saving = BandFileEntry(path, false); save.launch(name) }
                else if (showMkdir) operation("mkdir", path) else operation("upload", path, selected)
                showUpload = false; showMkdir = false; showDownload = false
            }, { localError = it.message })
        }) { Text(if (showDownload) stringResource(R.string.files_download) else if (showMkdir) stringResource(R.string.files_create) else stringResource(R.string.files_upload)) } },
        dismissButton = { TextButton(onClick = { showUpload = false; showMkdir = false; showDownload = false }) { Text(stringResource(R.string.files_cancel)) } })
}
