package com.neodim.subtlenotes

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DrawVM"
private const val ERASER_RADIUS = 12f   // world px; the eraser is swept as 3 parallel segments this far apart
private const val AUTOSAVE_DELAY_MS = 1500L
const val MIN_ZOOM = 1f   // 1 = document width fits the screen
const val MAX_ZOOM = 4f
private val MESH_TO_WORLD = ImmutableAffineTransform.translate(ImmutableVec(0f, 0f))

enum class LibrarySort { RECENT, NAME }

sealed interface DrawAction
class AddAction(val items: List<StrokeList>) : DrawAction
class IndexedStroke(val index: Int, val item: StrokeList)
class EraseAction(val removed: List<IndexedStroke>) : DrawAction

class DrawViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = CanvasStorage(File(app.filesDir, "canvases"))
    private val ioLock = Mutex()

    var docWidth by mutableFloatStateOf(0f)
        private set
    private var deviceDocWidth = 0f

    // Camera. screen = (world - view) * zoom, so world = screen / zoom + view.
    var viewX by mutableFloatStateOf(0f)
        private set
    var viewY by mutableFloatStateOf(0f)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set

    var zoomLocked by mutableStateOf(false)
        private set

    private var viewportW = 0f   // width of the canvas area on screen, px

    fun onViewportSize(w: Float) {
        if (w != viewportW) {
            viewportW = w
            setView(viewX, viewY)
        }
    }

    fun transformView(prevFocal: Offset, focal: Offset, scaleChange: Float) {
        val oldZoom = zoom
        val newZoom = if (zoomLocked) oldZoom else (oldZoom * scaleChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val worldX = prevFocal.x / oldZoom + viewX
        val worldY = prevFocal.y / oldZoom + viewY
        zoom = newZoom
        setView(worldX - focal.x / newZoom, worldY - focal.y / newZoom)
    }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
    }

    fun resetZoom() {
        zoomLocked = false
        zoom = 1f
        setView(0f, viewY)
    }

    private fun resetView() {
        zoom = 1f
        zoomLocked = false
        viewX = 0f
        viewY = 0f
    }

    private fun setView(x: Float, y: Float) {
        val visibleW = viewportW / zoom
        viewX = when {
            viewportW <= 0f -> max(0f, x)                       // size not known yet
            visibleW >= docWidth -> (docWidth - visibleW) / 2f  // paper narrower than the screen: keep it centred
            else -> x.coerceIn(0f, docWidth - visibleW)         // never pan past the paper edges
        }
        viewY = max(0f, y)                                      // the document is endless downwards only
    }

    var drawVersion by mutableIntStateOf(0)
        private set

    private class DirtyRegion(val version: Int, val yMin: Float, val yMax: Float)
    private val dirty = ArrayList<DirtyRegion>()
    private var dirtyFloor = 0   // a tile older than this may have missed a dropped entry -> treat as dirty

    private fun bump(yMin: Float = -Float.MAX_VALUE, yMax: Float = Float.MAX_VALUE) {
        drawVersion++
        dirty.add(DirtyRegion(drawVersion, yMin, yMax))
        if (dirty.size > 256) {
            repeat(128) { dirtyFloor = max(dirtyFloor, dirty.removeAt(0).version) }
        }
    }

    private fun bump(items: List<StrokeList>) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (item in items) {
            val b = item.bounds
            if (b == null) { bump(); return }
            lo = min(lo, b.yMin)
            hi = max(hi, b.yMax)
        }
        if (lo <= hi) bump(lo, hi)
    }

    private fun itemsOf(a: DrawAction): List<StrokeList> = when (a) {
        is AddAction -> a.items
        is EraseAction -> a.removed.map { it.item }
    }

    fun tileIsDirty(tileVersion: Int, top: Float, bottom: Float): Boolean {
        if (tileVersion < dirtyFloor) return true
        for (d in dirty) {
            if (d.version > tileVersion && d.yMax >= top && d.yMin <= bottom) return true
        }
        return false
    }

    val finishedStrokes = mutableStateListOf<StrokeList>()
    val markedStrokes = mutableStateListOf<StrokeList>()   // eraser: marked, not yet committed
    private val undoStack = ArrayList<DrawAction>()
    private val redoStack = ArrayList<DrawAction>()

    val activeBrush: MutableState<BrushClass> = mutableStateOf(BrushClass())


    val library = mutableStateListOf<CanvasMeta>()
    var showLibrary by mutableStateOf(true)   // the menu is the starting point
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var currentName by mutableStateOf("")
        private set

    var sortMode by mutableStateOf(LibrarySort.RECENT)
        private set
    var sortAscending by mutableStateOf(false)
        private set

    fun setSort(mode: LibrarySort) {
        if (mode == sortMode) {
            sortAscending = !sortAscending
        } else {
            sortMode = mode
            sortAscending = mode == LibrarySort.NAME
        }
    }

    val sortedLibrary: List<CanvasMeta>
        get() {
            val base = when (sortMode) {
                LibrarySort.RECENT -> library.sortedBy { it.modified }
                LibrarySort.NAME -> library.sortedBy { it.name.lowercase() }
            }
            return if (sortAscending) base else base.reversed()
        }

    private var booted = false
    private var saveJob: Job? = null

    fun initDocWidth(width: Float) {
        if (deviceDocWidth != 0f || width <= 0f) return
        deviceDocWidth = width
        if (docWidth == 0f) docWidth = width
        bootstrap()
    }

    fun addStrokes(strokes: List<Stroke>) {
        val items = strokes.map { StrokeList(it, it.shape.computeBoundingBox()) }
        finishedStrokes.addAll(items)
        bump(items)
        record(AddAction(items))
    }

    private fun record(action: DrawAction) {
        undoStack.add(action)
        redoStack.clear()
        scheduleSave()
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        when (action) {
            is AddAction -> finishedStrokes.removeAll { s -> action.items.any { it === s } }
            is EraseAction -> action.removed.sortedBy { it.index }.forEach {
                finishedStrokes.add(it.index.coerceAtMost(finishedStrokes.size), it.item)
            }
        }
        redoStack.add(action)
        bump(itemsOf(action))
        scheduleSave()
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        when (action) {
            is AddAction -> finishedStrokes.addAll(action.items)
            is EraseAction -> finishedStrokes.removeAll { s -> action.removed.any { it.item === s } }
        }
        undoStack.add(action)
        bump(itemsOf(action))
        scheduleSave()
    }

    fun isMarked(s: StrokeList): Boolean = markedStrokes.any { it === s }

    fun eraseSweep(x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = ERASER_RADIUS / zoom   // constant on screen, so smaller in world units when zoomed in
        val minX = min(x0, x1) - r
        val maxX = max(x0, x1) + r
        val minY = min(y0, y1) - r
        val maxY = max(y0, y1) + r

        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        val px = if (len > 0.001f) -dy / len * r else r
        val py = if (len > 0.001f) dx / len * r else 0f
        val sweep = listOf(
            segment(x0, y0, x1, y1),
            segment(x0 + px, y0 + py, x1 + px, y1 + py),
            segment(x0 - px, y0 - py, x1 - px, y1 - py),
        )

        for (s in finishedStrokes) {
            if (isMarked(s)) continue
            val b = s.bounds ?: continue
            if (b.xMax < minX || b.xMin > maxX || b.yMax < minY || b.yMin > maxY) continue   // cheap reject
            if (sweep.any { s.stroke.shape.intersects(it, MESH_TO_WORLD) }) {
                markedStrokes.add(s)
                bump(listOf(s))
            }
        }
    }

    fun commitErase() {
        if (markedStrokes.isEmpty()) return
        val removed = finishedStrokes.withIndex()
            .filter { (_, s) -> isMarked(s) }
            .map { (i, s) -> IndexedStroke(i, s) }
        finishedStrokes.removeAll { s -> isMarked(s) }
        markedStrokes.clear()
        record(EraseAction(removed))
    }

    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float) =
        ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1))

    fun openLibrary() {
        saveJob?.cancel()
        viewModelScope.launch {
            saveNow()
            refreshLibrary()
            showLibrary = true
        }
    }

    fun closeLibrary() {
        if (currentId != null) showLibrary = false
    }

    fun defaultCanvasName(): String =
        "Canvas " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())

    fun newCanvas(name: String) {
        if (deviceDocWidth <= 0f) return   // window size not known yet
        saveJob?.cancel()
        viewModelScope.launch {
            saveNow()
            createNew(name.trim().ifEmpty { defaultCanvasName() })
            showLibrary = false
        }
    }

    fun renameCanvas(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            if (id == currentId) currentName = name
            try {
                io { storage.rename(id, name) }
            } catch (e: Exception) {
                Log.e(TAG, "Rename failed for $id", e)
            }
            refreshLibrary()
        }
    }

    fun openCanvas(id: String) {
        saveJob?.cancel()
        viewModelScope.launch {
            saveNow()
            try {
                loadInto(id)
                showLibrary = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open canvas $id", e)
            }
        }
    }

    fun deleteCanvas(id: String) {
        viewModelScope.launch {
            val deletingCurrent = id == currentId
            if (deletingCurrent) {
                saveJob?.cancel()
                currentId = null   // so no pending save can resurrect it
                currentName = ""
                finishedStrokes.clear()
                markedStrokes.clear()
                undoStack.clear()
                redoStack.clear()
                resetView()
                bump()
                showLibrary = true
            }
            io { storage.delete(id) }
            refreshLibrary()
        }
    }

    var exporting by mutableStateOf(false)
        private set

    fun exportPdf(uri: Uri, onDone: (Boolean) -> Unit) {
        if (exporting) return
        exporting = true
        val strokes = finishedStrokes.toList()   // snapshot on the main thread
        val width = docWidth
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { writePdf(strokes, width, it) }
                        ?: throw IOException("Could not open $uri")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "PDF export failed", e)
                false
            }
            exporting = false
            onDone(ok)
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        viewModelScope.launch { saveNow() }
    }

    private fun bootstrap() {
        if (booted) return
        booted = true
        viewModelScope.launch {
            refreshLibrary()
            showLibrary = true
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val id = currentId ?: return
        val name = currentName
        val width = docWidth
        val view = ViewState(viewX, viewY, zoom)
        val strokes = finishedStrokes.map { it.stroke }   // snapshot on the main thread
        try {
            io { storage.save(id, name, width, view, strokes) }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for $id", e)
        }
    }

    private suspend fun createNew(name: String) {
        val meta = io { storage.create(name, deviceDocWidth) }
        refreshLibrary()
        loadInto(meta.id)
    }

    private suspend fun loadInto(id: String) {
        val loaded = io { storage.load(id) }
        finishedStrokes.clear()
        finishedStrokes.addAll(loaded.strokes)
        markedStrokes.clear()
        undoStack.clear()
        redoStack.clear()
        docWidth = if (loaded.docWidth > 0f) loaded.docWidth else deviceDocWidth
        zoom = loaded.view.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomLocked = false
        setView(loaded.view.x, loaded.view.y)
        currentName = loaded.meta.name
        currentId = id
        bump()
    }

    private suspend fun refreshLibrary() {
        val items = io { storage.list() }
        library.clear()
        library.addAll(items)
    }

    private suspend fun <T> io(block: () -> T): T =
        ioLock.withLock { withContext(Dispatchers.IO) { block() } }
}