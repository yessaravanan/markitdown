package com.markitdown.local.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.markitdown.local.converter.ConversionException
import com.markitdown.local.converter.ConversionOptions
import com.markitdown.local.converter.MarkItDown
import com.markitdown.local.converter.SourceDocument
import com.markitdown.local.ocr.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val busy: Boolean = false,
    val progress: Float = 0f,
    val progressText: String = "",
    val markdown: String = "",
    val outputName: String = "document.md",
    val error: String? = null,
    val message: String? = null,
    val installedLanguages: List<String> = emptyList(),
    val bundledLanguages: Set<String> = emptySet(),
    val selectedLanguages: List<String> = listOf(OcrEngine.DEFAULT_LANGUAGE),
    val forceOcr: Boolean = false,
    val includeMetadata: Boolean = true,
    val pageMarkers: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            selectedLanguages = prefs.getString("langs", OcrEngine.DEFAULT_LANGUAGE)!!.split('+').filter { it.isNotBlank() },
            forceOcr = prefs.getBoolean("forceOcr", false),
            includeMetadata = prefs.getBoolean("metadata", true),
            pageMarkers = prefs.getBoolean("pageMarkers", true),
        ),
    )
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Leftovers from a previous session (rendered pages, shared exports, camera shots).
            listOf("work", "export", "camera").forEach { File(app.cacheDir, it).deleteRecursively() }
            refreshLanguages()
        }
    }

    private fun refreshLanguages() {
        val ctx = getApplication<Application>()
        val installed = runCatching { OcrEngine.installedLanguages(ctx) }.getOrDefault(emptyList())
        val bundled = ctx.assets.list("tessdata").orEmpty().map { it.removeSuffix(".traineddata") }.toSet()
        _state.update { s ->
            val selected = s.selectedLanguages.filter { it in installed }.ifEmpty { installed.take(1) }
            s.copy(installedLanguages = installed, bundledLanguages = bundled, selectedLanguages = selected)
        }
    }

    fun convert(uris: List<Uri>, deleteAfter: Boolean = false) {
        if (uris.isEmpty() || _state.value.busy) return
        val ctx = getApplication<Application>()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null, progress = 0f, progressText = "Starting…") }
            try {
                val sources = withContext(Dispatchers.IO) { uris.map { describe(ctx, it) } }
                val s = _state.value
                val options = ConversionOptions(
                    ocrLanguages = s.selectedLanguages.joinToString("+").ifEmpty { OcrEngine.DEFAULT_LANGUAGE },
                    forceOcr = s.forceOcr,
                    includeMetadata = s.includeMetadata,
                    pageMarkers = s.pageMarkers,
                )
                val result = withContext(Dispatchers.Default) {
                    MarkItDown(ctx).convert(sources, options) { done, total, msg ->
                        _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1), progressText = msg) }
                    }
                }
                val base = result.title?.let(::safeFileName)
                    ?: sources.first().displayName.substringBeforeLast('.').let(::safeFileName)
                _state.update { it.copy(markdown = result.markdown, outputName = "${base.ifEmpty { "document" }}.md") }
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "Cancelled") }
                throw e
            } catch (e: ConversionException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(error = "Ran out of memory. Try fewer images or a smaller PDF.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Conversion failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = false) }
                if (deleteAfter) withContext(Dispatchers.IO) { File(ctx.cacheDir, "camera").deleteRecursively() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun toggleLanguage(lang: String) = _state.update { s ->
        val next = if (lang in s.selectedLanguages) s.selectedLanguages - lang else s.selectedLanguages + lang
        val fixed = next.ifEmpty { listOf(lang) }
        prefs.edit().putString("langs", fixed.joinToString("+")).apply()
        s.copy(selectedLanguages = fixed)
    }

    fun setForceOcr(v: Boolean) = _state.update { prefs.edit().putBoolean("forceOcr", v).apply(); it.copy(forceOcr = v) }
    fun setIncludeMetadata(v: Boolean) = _state.update { prefs.edit().putBoolean("metadata", v).apply(); it.copy(includeMetadata = v) }
    fun setPageMarkers(v: Boolean) = _state.update { prefs.edit().putBoolean("pageMarkers", v).apply(); it.copy(pageMarkers = v) }
    fun updateMarkdown(text: String) = _state.update { it.copy(markdown = text) }
    fun clear() = _state.update { it.copy(markdown = "", error = null, message = null) }
    fun dismissMessages() = _state.update { it.copy(error = null, message = null) }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun importLanguage(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = describe(ctx, uri).displayName
                val lang = ctx.contentResolver.openInputStream(uri)!!.use { OcrEngine.importModel(ctx, name, it) }
                refreshLanguages()
                _state.update { it.copy(message = "Installed OCR language: $lang") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Import failed") }
            }
        }
    }

    fun removeLanguage(lang: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            OcrEngine.deleteModel(ctx, lang)
            refreshLanguages()
        }
    }

    fun saveTo(uri: Uri) {
        val ctx = getApplication<Application>()
        val text = _state.value.markdown
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                _state.update { it.copy(message = "Saved") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    /** Writes the Markdown to a private cache file so it can be handed to another app via FileProvider. */
    fun exportFile(): File {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        return File(dir, _state.value.outputName).apply { writeText(_state.value.markdown) }
    }

    private fun describe(ctx: Context, uri: Uri): SourceDocument {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val display = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        return SourceDocument(uri, display, ctx.contentResolver.getType(uri))
    }

    private fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(80)
}
