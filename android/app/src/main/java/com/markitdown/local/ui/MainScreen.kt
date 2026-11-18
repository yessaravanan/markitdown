package com.markitdown.local.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showLanguages by remember { mutableStateOf(false) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.convert(listOf(it)) }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.convert(uris)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.convert(listOf(uri), deleteAfter = true)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let(vm::saveTo)
    }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importLanguage)
    }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissMessages()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MarkItDown Local")
                        Text(
                            "PDF & images → Markdown, offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrivacyNote()

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("PDF")
                }
                Button(onClick = { pickImages.launch(arrayOf("image/*")) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text("Images")
                }
                FilledTonalButton(
                    onClick = {
                        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                        cameraUri = uri
                        runCatching { takePhoto.launch(uri) }.onFailure { vm.showMessage("No camera app available") }
                    },
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Camera")
                }
            }

            OptionsCard(state, vm, onLanguages = { showLanguages = true })

            if (state.busy) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.progressText, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }

            if (state.markdown.isNotEmpty()) {
                ResultCard(
                    state = state,
                    onCopy = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(state.outputName, state.markdown))
                        vm.showMessage("Copied")
                    },
                    onShare = { share(ctx, vm) },
                    onSave = { save.launch(state.outputName) },
                    onClear = vm::clear,
                )
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (showLanguages) {
        LanguagesDialog(
            state = state,
            onToggle = vm::toggleLanguage,
            onImport = { importModel.launch(arrayOf("*/*")) },
            onRemove = vm::removeLanguage,
            onDismiss = { showLanguages = false },
        )
    }
}

@Composable
private fun PrivacyNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Runs entirely on this phone. The app has no internet permission, so files can't be uploaded anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(state: UiState, vm: MainViewModel, onLanguages: () -> Unit) {
    Card {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("OCR: ${state.selectedLanguages.joinToString(" + ")}", Modifier.weight(1f))
                TextButton(onClick = onLanguages, enabled = !state.busy) { Text("Languages") }
            }
            OptionRow("OCR every PDF page", "For PDFs whose text comes out garbled", state.forceOcr, !state.busy, vm::setForceOcr)
            OptionRow("Image metadata", "Size, date, camera, GPS", state.includeMetadata, !state.busy, vm::setIncludeMetadata)
            OptionRow("Page markers", "<!-- Page N --> between PDF pages", state.pageMarkers, !state.busy, vm::setPageMarkers)
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ResultCard(
    state: UiState,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.outputName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "Clear") }
            }
            Text(
                "${state.markdown.length} characters · ${state.markdown.lines().size} lines",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(6.dp)); Text("Save .md") }
                OutlinedButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy") }
                OutlinedButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share") }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Markdown") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Preview") })
            }
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            if (tab == 0) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    factory = { c ->
                        TextView(c).apply {
                            typeface = Typeface.MONOSPACE
                            setTextIsSelectable(true)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        }
                    },
                    update = { it.setTextColor(textColor); if (it.text.toString() != state.markdown) it.text = state.markdown },
                )
            } else {
                val markwon = rememberMarkwon()
                AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    factory = { c -> TextView(c).apply { setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) } },
                    update = { it.setTextColor(textColor); markwon.setMarkdown(it, state.markdown) },
                )
            }
        }
    }
}

@Composable
private fun rememberMarkwon(): Markwon {
    val ctx = LocalContext.current
    return remember(ctx) { Markwon.builder(ctx).usePlugin(TablePlugin.create(ctx)).build() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesDialog(
    state: UiState,
    onToggle: (String) -> Unit,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onImport) { Text("Import .traineddata") } },
        title = { Text("OCR languages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Select the languages in your documents. To add one (e.g. Tamil = tam.traineddata), " +
                        "copy the model file from github.com/tesseract-ocr/tessdata_fast to your phone, then import it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.installedLanguages.forEach { lang ->
                        FilterChip(
                            selected = lang in state.selectedLanguages,
                            onClick = { onToggle(lang) },
                            label = { Text(lang) },
                        )
                    }
                }
                val removable = state.installedLanguages.filter { it !in state.bundledLanguages }
                if (removable.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        removable.forEach { lang -> TextButton(onClick = { onRemove(lang) }) { Text("Remove $lang") } }
                    }
                }
            }
        },
    )
}

private fun share(ctx: Context, vm: MainViewModel) {
    val file = vm.exportFile()
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val markdown = vm.state.value.markdown
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (markdown.length < 100_000) putExtra(Intent.EXTRA_TEXT, markdown)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share Markdown"))
}
