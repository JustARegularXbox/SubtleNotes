package com.neodim.subtlenotes

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neodim.subtlenotes.ui.theme.SubtleNotesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val activityViewModel: DrawViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            SubtleNotesTheme {
                val viewModel = viewModel<DrawViewModel>()
                val windowInfo = LocalWindowInfo.current

                LaunchedEffect(windowInfo.containerSize) {
                    val size = windowInfo.containerSize
                    viewModel.initDocWidth(minOf(size.width, size.height).toFloat())
                }

                Crossfade(
                    targetState = viewModel.showLibrary,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1C)),
                    animationSpec = tween(durationMillis = 120),
                    label = "screen",
                ) { showLibrary ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showLibrary) {
                            LibraryScreen(viewModel = viewModel)
                        } else {
                            DrawingScreen(viewModel = viewModel, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        activityViewModel.flushSave()
    }
}

private const val ZOOM_SENSITIVITY = 0.45f

private const val GESTURE_SLOP_PX = 40f

private const val TILE_WORLD = 256   // world units per tile edge; small so a re-render hitch stays tiny

private fun renderScaleFor(zoom: Float): Int = when {
    zoom <= 1.01f -> 1
    zoom <= 2.02f -> 2
    else -> 4
}

private class Tile(val layer: GraphicsLayer, val ix: Int, val iy: Int) {
    var version = -1
}

private class TileCache(private val ctx: GraphicsContext, initialScale: Int) {
    private val tiles = HashMap<Long, Tile>()

    var scale by mutableIntStateOf(initialScale)
    private var builtScale = initialScale

    private fun key(ix: Int, iy: Int): Long = (iy.toLong() shl 32) or (ix.toLong() and 0xFFFFFFFFL)

    fun tile(ix: Int, iy: Int): Tile = tiles.getOrPut(key(ix, iy)) { Tile(ctx.createGraphicsLayer(), ix, iy) }

    fun syncScale() {
        if (builtScale != scale) {
            releaseAll()
            builtScale = scale
        }
    }

    fun trim(ixLo: Int, ixHi: Int, iyLo: Int, iyHi: Int) {
        val it = tiles.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.ix < ixLo || t.ix > ixHi || t.iy < iyLo || t.iy > iyHi) {
                ctx.releaseGraphicsLayer(t.layer)
                it.remove()
            }
        }
    }

    fun releaseAll() {
        tiles.values.forEach { ctx.releaseGraphicsLayer(it.layer) }
        tiles.clear()
    }
}

private fun renderTile(
    layer: GraphicsLayer,
    ix: Int,
    iy: Int,
    rs: Int,
    density: Density,
    renderer: CanvasStrokeRenderer,
    viewModel: DrawViewModel,
) {
    val left = (ix * TILE_WORLD).toFloat()
    val top = (iy * TILE_WORLD).toFloat()
    val right = left + TILE_WORLD
    val bottom = top + TILE_WORLD
    val texPx = TILE_WORLD * rs
    val toPixels = MutableAffineTransform()
    AffineTransform.multiply(
        ImmutableAffineTransform.scale(rs.toFloat(), rs.toFloat()),   // lhs: applied second
        ImmutableAffineTransform.translate(ImmutableVec(-left, -top)), // rhs: applied first
        toPixels,
    )
    layer.compositingStrategy = CompositingStrategy.Offscreen
    layer.record(density, LayoutDirection.Ltr, IntSize(texPx, texPx)) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withScale(rs.toFloat(), rs.toFloat()) {
                withTranslation(-left, -top) {
                    viewModel.finishedStrokes.forEach { sList ->
                        val b = sList.bounds
                        if (b != null && (b.yMax < top || b.yMin > bottom || b.xMax < left || b.xMin > right)) {
                            return@forEach
                        }
                        if (viewModel.isMarked(sList)) return@forEach   // erased-in-progress: hide immediately
                        renderer.draw(canvas = this, stroke = sList.stroke, strokeToScreenTransform = toPixels)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawingScreen(viewModel: DrawViewModel, modifier: Modifier = Modifier) {

    val renderer = remember { CanvasStrokeRenderer.create() }
    val graphicsContext = LocalGraphicsContext.current
    val tiles = remember { TileCache(graphicsContext, renderScaleFor(viewModel.zoom)) }
    DisposableEffect(tiles) { onDispose { tiles.releaseAll() } }

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.exportPdf(uri) { ok ->
            Toast.makeText(context, if (ok) "PDF exported" else "PDF export failed", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(tiles) {
        snapshotFlow { viewModel.zoom }.collectLatest { z ->
            delay(150)
            tiles.scale = renderScaleFor(z)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewModel.onViewportSize(it.width.toFloat()) }
                .pointerInput(Unit) {
                    var erasing = false          // currently marking strokes
                    var swallowStylus = false    // stylus contact began as an erase: keep it away from InProgressStrokes
                    var drawingContact = false   // stylus contact began as ink: InProgressStrokes owns it until pen-up
                    var twoFingerActive = false  // a two-finger gesture is in progress
                    var gestureStartSpan = 0f    // finger distance when it began
                    var gestureStartFocal = Offset.Zero
                    var gestureMode = 0          // 0 = undecided, 1 = scroll (never zooms), 2 = pinch
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val (stylusChanges, touchChanges) =
                                event.changes.partition { it.type == PointerType.Stylus }

                            val stylus = stylusChanges.firstOrNull()
                            val inContact = stylus != null && stylus.pressed
                            val buttonDown = stylus != null && event.buttons.isPrimaryPressed

                            if (!inContact) {
                                drawingContact = false
                            } else if (!swallowStylus && !drawingContact && !buttonDown && stylus != null) {
                                val wx = stylus.position.x / viewModel.zoom + viewModel.viewX
                                if (wx < 0f || wx > viewModel.docWidth) {
                                    swallowStylus = true   // landed on the margin beside the paper: ignore this contact
                                } else {
                                    drawingContact = true
                                }
                            }
                            val wantsErase = inContact && buttonDown && !drawingContact

                            if (wantsErase && stylus != null) {
                                erasing = true
                                swallowStylus = true
                                val z = viewModel.zoom
                                val vx = viewModel.viewX
                                val vy = viewModel.viewY
                                viewModel.eraseSweep(
                                    stylus.previousPosition.x / z + vx, stylus.previousPosition.y / z + vy,
                                    stylus.position.x / z + vx, stylus.position.y / z + vy,
                                )
                            } else if (erasing) {
                                viewModel.commitErase()   // contact ended or button released
                                erasing = false
                            }
                            if (swallowStylus) {
                                stylusChanges.forEach { it.consume() }
                                if (!inContact) swallowStylus = false
                            }

                            val activeTouches = touchChanges.filter { it.pressed }
                            if (activeTouches.size == 2 && stylusChanges.isEmpty()) {
                                val a = activeTouches[0]
                                val b = activeTouches[1]
                                val prevFocal = (a.previousPosition + b.previousPosition) / 2f
                                val focal = (a.position + b.position) / 2f
                                val prevSpan = (a.previousPosition - b.previousPosition).getDistance()
                                val span = (a.position - b.position).getDistance()

                                if (!twoFingerActive) {
                                    twoFingerActive = true
                                    gestureStartSpan = span
                                    gestureStartFocal = focal
                                    gestureMode = 0
                                }
                                if (gestureMode == 0) {
                                    val spanDelta = abs(span - gestureStartSpan)
                                    val focalDelta = (focal - gestureStartFocal).getDistance()
                                    if (max(spanDelta, focalDelta) > GESTURE_SLOP_PX) {
                                        gestureMode = if (spanDelta > focalDelta) 2 else 1
                                    }
                                }
                                val scaleChange =
                                    if (gestureMode == 2 && prevSpan > 1f) (span / prevSpan).pow(ZOOM_SENSITIVITY) else 1f
                                viewModel.transformView(prevFocal, focal, scaleChange)
                            } else {
                                twoFingerActive = false
                            }
                            touchChanges.forEach { it.consume() }
                        }
                    }
                }
        ) {
            InkLayer(viewModel)
            Canvas(modifier = Modifier.fillMaxSize()) {
                viewModel.drawVersion                       // subscribe: redraw when strokes change
                val z = viewModel.zoom
                val vx = viewModel.viewX
                val vy = viewModel.viewY
                tiles.syncScale()
                val rs = tiles.scale
                val texPx = (TILE_WORLD * rs).toFloat()

                val paperLeft = ((0f - vx) * z).roundToInt().toFloat()
                val paperRight = ((viewModel.docWidth - vx) * z).roundToInt().toFloat()
                val paperTop = ((0f - vy) * z).roundToInt().toFloat().coerceAtLeast(0f)

                val shadowW = 28.dp.toPx()
                val shadowH = size.height - paperTop
                val shadowStops = listOf(Color.Transparent, Color(0x22000000), Color(0x80000000))
                if (paperLeft > 0f) {
                    translate(left = paperLeft - shadowW, top = paperTop) {
                        drawRect(
                            brush = Brush.horizontalGradient(shadowStops, startX = 0f, endX = shadowW),
                            size = Size(shadowW, shadowH),
                        )
                    }
                }
                if (paperRight < size.width) {
                    translate(left = paperRight, top = paperTop) {
                        drawRect(
                            brush = Brush.horizontalGradient(shadowStops.reversed(), startX = 0f, endX = shadowW),
                            size = Size(shadowW, shadowH),
                        )
                    }
                }

                drawRect(
                    color = Color.Black,
                    topLeft = Offset(paperLeft, paperTop),
                    size = Size(paperRight - paperLeft, size.height - paperTop),
                )

                val lastColumn = (viewModel.docWidth / TILE_WORLD).toInt().coerceAtLeast(0)
                val firstX = floor(vx / TILE_WORLD).toInt().coerceAtLeast(0)
                val lastX = floor((vx + size.width / z) / TILE_WORLD).toInt().coerceAtMost(lastColumn)
                val firstY = floor(vy / TILE_WORLD).toInt().coerceAtLeast(0)
                val lastY = floor((vy + size.height / z) / TILE_WORLD).toInt()

                clipRect(left = paperLeft, top = 0f, right = paperRight, bottom = size.height) {
                    for (iy in firstY..lastY) {
                        for (ix in firstX..lastX) {
                            val tile = tiles.tile(ix, iy)
                            val worldTop = (iy * TILE_WORLD).toFloat()
                            if (viewModel.tileIsDirty(tile.version, worldTop, worldTop + TILE_WORLD)) {
                                renderTile(tile.layer, ix, iy, rs, this, renderer, viewModel)
                                tile.version = viewModel.drawVersion
                            }
                            val x0 = ((ix * TILE_WORLD - vx) * z).roundToInt()
                            val x1 = (((ix + 1) * TILE_WORLD - vx) * z).roundToInt()
                            val y0 = ((iy * TILE_WORLD - vy) * z).roundToInt()
                            val y1 = (((iy + 1) * TILE_WORLD - vy) * z).roundToInt()
                            if (x1 <= x0 || y1 <= y0) continue
                            withTransform({
                                translate(x0.toFloat(), y0.toFloat())
                                scale((x1 - x0) / texPx, (y1 - y0) / texPx, pivot = Offset.Zero)
                            }) {
                                drawLayer(tile.layer)
                            }
                        }
                    }
                }
                tiles.trim(firstX - 1, lastX + 1, firstY - 1, lastY + 1)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { viewModel.openLibrary() }) { Text("Menu") }
            Spacer(modifier = Modifier.width(12.dp))
            Text(viewModel.currentName, color = Color.White)
        }
        BrushToolbar(
            brush = viewModel.activeBrush.value,
            modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)
        )
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { exportLauncher.launch(viewModel.currentName.replace('/', '-') + ".pdf") },
                enabled = !viewModel.exporting,
            ) { Text("PDF") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { viewModel.undo() }) { Text("Undo") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { viewModel.redo() }) { Text("Redo") }
        }
        Text(
            text = (if (viewModel.zoomLocked) "\uD83D\uDD12 " else "") + "${(viewModel.zoom * 100).roundToInt()}%",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .clip(RoundedCornerShape(50))
                .background(if (viewModel.zoomLocked) Color(0xCC1565C0) else Color(0x99000000))
                .combinedClickable(
                    onClick = { viewModel.toggleZoomLock() },
                    onLongClick = { viewModel.resetZoom() },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InkLayer(viewModel: DrawViewModel) {
    val z = viewModel.zoom
    val toWorld = Matrix().apply {
        values[0] = 1f / z
        values[5] = 1f / z
        values[12] = viewModel.viewX
        values[13] = viewModel.viewY
    }
    InProgressStrokes(
        defaultBrush = viewModel.activeBrush.value.brush,
        nextBrush = remember { { viewModel.activeBrush.value.brush } },
        pointerEventToWorldTransform = toWorld,
        onStrokesFinished = { strokes -> viewModel.addStrokes(strokes) },
    )
}