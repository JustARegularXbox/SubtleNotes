package com.neodim.subtlenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(viewModel: DrawViewModel, modifier: Modifier = Modifier) {
    var pendingDelete by remember { mutableStateOf<CanvasMeta?>(null) }
    var pendingRename by remember { mutableStateOf<CanvasMeta?>(null) }
    var creating by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Canvases", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text("New canvas") }
            if (viewModel.currentId != null) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { viewModel.closeLibrary() }) { Text("Back") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sort:", color = Color.Gray)
            Spacer(Modifier.width(8.dp))
            SortButton("Recent", LibrarySort.RECENT, viewModel)
            Spacer(Modifier.width(8.dp))
            SortButton("Name", LibrarySort.NAME, viewModel)
        }
        Spacer(Modifier.height(16.dp))

        if (viewModel.library.isEmpty()) {
            Text("No canvases yet. Tap \"New canvas\" to start.", color = Color.Gray)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.sortedLibrary, key = { it.id }) { meta ->
                val isCurrent = meta.id == viewModel.currentId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isCurrent) Color(0xFF2A2A40) else Color(0xFF1C1C1C))
                        .clickable { viewModel.openCanvas(meta.id) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(meta.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            dateFormat.format(Date(meta.modified)) + if (isCurrent) "  (open)" else "",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { pendingRename = meta }) { Text("Rename") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pendingDelete = meta }) { Text("Delete") }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New canvas",
            initial = remember { viewModel.defaultCanvasName() },
            confirmLabel = "Create",
            onConfirm = { viewModel.newCanvas(it); creating = false },
            onDismiss = { creating = false },
        )
    }

    pendingRename?.let { meta ->
        NameDialog(
            title = "Rename canvas",
            initial = meta.name,
            confirmLabel = "Rename",
            onConfirm = { viewModel.renameCanvas(meta.id, it); pendingRename = null },
            onDismiss = { pendingRename = null },
        )
    }

    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete canvas?") },
            text = { Text("\"${meta.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCanvas(meta.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SortButton(label: String, mode: LibrarySort, viewModel: DrawViewModel) {
    val active = viewModel.sortMode == mode
    val arrow = if (!active) "" else if (viewModel.sortAscending) " \u25B2" else " \u25BC"
    if (active) {
        Button(onClick = { viewModel.setSort(mode) }) { Text(label + arrow) }
    } else {
        OutlinedButton(onClick = { viewModel.setSort(mode) }) { Text(label) }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}