package io.github.yuriyurin.fit3companion

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.yuriyurin.fit3companion.ble.CustomFace
import io.github.yuriyurin.fit3companion.ble.CustomFaceLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CustomFaceCell(face: CustomFace, library: CustomFaceLibrary, style: Int,
    modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit, menuOpen: Boolean = false) {
    var bitmap by remember(face.key, style) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(face.key, style) { bitmap = withContext(Dispatchers.IO) { library.preview(face.key, style) } }
    Card(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = if (menuOpen) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fit3PreviewFrame(Modifier.width(96.dp)) {
                if (bitmap != null) Image(bitmap!!.asImageBitmap(), face.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text("—", style = MaterialTheme.typography.headlineMedium)
            }
            Text(face.name, maxLines = 2, minLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun CustomFaceActionsDialog(face: CustomFace, library: CustomFaceLibrary,
    onDismiss: () -> Unit, onChanged: () -> Unit, initialAction: String = "") {
    var action by remember(face.key, initialAction) { mutableStateOf(initialAction) }
    var name by remember(face.key) { mutableStateOf(face.name) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun commit() {
        busy = true
        scope.launch {
            failed = withContext(Dispatchers.IO) {
                runCatching { if (action == "delete") library.delete(face.key) else library.rename(face.key, name) }.isFailure
            }
            busy = false
            if (!failed) onChanged()
        }
    }
    if (action.isBlank()) {
        CompactFaceMenu(onDismiss,
            onRename = { action = "rename" }, onDelete = { action = "delete" })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (action == "rename") R.string.custom_faces_rename else R.string.custom_faces_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (action) {
                    "rename" -> OutlinedTextField(name, { name = it.take(60) }, singleLine = true,
                        label = { Text(stringResource(R.string.custom_faces_name)) }, modifier = Modifier.fillMaxWidth())
                    "delete" -> Text(stringResource(R.string.custom_faces_delete_question))
                    else -> {
                        TextButton(onClick = { action = "rename" }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.custom_faces_rename))
                        }
                        TextButton(onClick = { action = "delete" }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.custom_faces_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.custom_faces_operation_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (action.isNotBlank()) TextButton(onClick = { commit() },
                enabled = !busy && (action == "delete" || name.trim().isNotEmpty() && name.none { it.isISOControl() })) {
                Text(stringResource(if (action == "delete") R.string.custom_faces_delete else R.string.custom_faces_save))
            }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.custom_faces_cancel)) } })
}

/** No title, empty button slots or wide AlertDialog padding for a two-action menu. */
@Composable
internal fun CompactFaceMenu(onDismiss: () -> Unit, onRename: (() -> Unit)? = null,
    onDelete: () -> Unit, deleteLabel: Int = R.string.custom_faces_delete,
    onDeleteLocal: (() -> Unit)? = null, onVariants: (() -> Unit)? = null,
    deleteEnabled: Boolean = true) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(200.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (onVariants != null) TextButton(onClick = onVariants,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.custom_faces_variants))
                }
                if (onRename != null) TextButton(onClick = onRename,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.custom_faces_rename))
                }
                TextButton(onClick = onDelete, enabled = deleteEnabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(deleteLabel), color = if (deleteEnabled)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                if (onDeleteLocal != null) TextButton(onClick = onDeleteLocal,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.custom_faces_delete_local), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun CustomFaceVariantsDialog(face: CustomFace, library: CustomFaceLibrary, enabled: Boolean,
    onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(face.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            face.styles.chunked(3).forEach { styles ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    styles.forEach { style -> CustomFaceCell(
                        face.copy(name = stringResource(R.string.custom_faces_variant, style + 1)), library, style,
                        Modifier.weight(1f), onClick = { if (enabled) onSelect(style) }, onLongClick = {}) }
                    repeat(3 - styles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (!enabled) Text(stringResource(R.string.custom_faces_connect), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_faces_cancel)) } })
}
