package com.neodim.subtlenotes

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val MIN_BRUSH_SIZE = 1f
private val MAX_BRUSH_SIZE = 30f

@Composable
fun BrushToolbar(brush: BrushClass, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorToolButton(brush)
        SizeToolButton(brush)
    }
}

@Composable
private fun ColorToolButton(brush: BrushClass) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(onClick = { open = true }) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(brush.colorArgb))
                    .border(2.dp, Color(0xFF444444), CircleShape)
            )
        }
        ToolPopup(open = open, onDismiss = { open = false }) { ColorPicker(brush) }
    }
}

@Composable
private fun SizeToolButton(brush: BrushClass) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(onClick = { open = true }) {
            Box(
                Modifier
                    .size(brush.size.coerceIn(4f, 32f).dp)
                    .clip(CircleShape)
                    .background(Color(brush.colorArgb))
                    .border(1.dp, Color(0xFF888888), CircleShape)
            )
        }
        ToolPopup(open = open, onDismiss = { open = false }) { SizePicker(brush) }
    }
}

@Composable
private fun ToolButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(durationMillis = 80), label = "press")
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(52.dp)
            .clip(shape)
            .background(Color(0xFFF2F2F2))
            .border(1.dp, Color(0xFF888888), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private class SidePopupPositioner(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right + gapPx)
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)
        val y = anchorBounds.top
            .coerceAtMost(windowSize.height - popupContentSize.height)
            .coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun ToolPopup(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val state = remember { MutableTransitionState(false) }
    state.targetState = open

    if (state.currentState || state.targetState) {
        Popup(
            popupPositionProvider = remember(gapPx) { SidePopupPositioner(gapPx) },
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(tween(110)) + scaleIn(tween(110), initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 0.2f)),
                exit = fadeOut(tween(80)) + scaleOut(tween(80), targetScale = 0.96f, transformOrigin = TransformOrigin(0f, 0.2f)),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E1E1E),
                    shadowElevation = 8.dp,
                ) {
                    Box(Modifier.padding(16.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun SizePicker(brush: BrushClass) {
    Column(Modifier.width(260.dp)) {
        Text("Size: ${brush.size.roundToInt()}", color = Color.White)
        Slider(
            value = brush.size,
            onValueChange = { brush.size = it },
            valueRange = MIN_BRUSH_SIZE..MAX_BRUSH_SIZE,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF808080))
        ) {
            val y = size.height / 2f
            drawLine(
                color = Color(brush.colorArgb),
                start = Offset(24f, y),
                end = Offset(size.width - 24f, y),
                strokeWidth = brush.size,
                cap = StrokeCap.Round,
            )
        }
    }
}


private val HUE_COLORS = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

@Composable
private fun ColorPicker(brush: BrushClass) {
    val seed = remember { FloatArray(3).also { AndroidColor.colorToHSV(brush.colorArgb, it) } }
    var hue by remember { mutableFloatStateOf(seed[0]) }
    var sat by remember { mutableFloatStateOf(seed[1]) }
    var value by remember { mutableFloatStateOf(seed[2]) }

    fun push() {
        brush.colorArgb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    }

    Column(Modifier.width(240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(240.dp)
                .pointerInput(Unit) {
                    fun update(p: Offset) {
                        val r = size.width / 2f
                        val dx = p.x - r
                        val dy = p.y - r
                        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        if (angle < 0f) angle += 360f
                        hue = angle
                        sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                        push()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        update(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            update(change.position)
                            change.consume()
                        } while (change.pressed)
                    }
                }
        ) {
            val r = size.minDimension / 2f
            drawCircle(brush = Brush.sweepGradient(HUE_COLORS, center), radius = r)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r),
                radius = r,
            )
            drawCircle(color = Color.Black.copy(alpha = 1f - value), radius = r)

            val a = Math.toRadians(hue.toDouble())
            val marker = Offset(
                center.x + (cos(a) * sat * r).toFloat(),
                center.y + (sin(a) * sat * r).toFloat(),
            )
            drawCircle(Color.White, radius = 11f, center = marker, style = Stroke(width = 3f))
            drawCircle(Color.Black, radius = 13.5f, center = marker, style = Stroke(width = 1.5f))
        }

        Slider(
            value = value,
            onValueChange = { value = it; push() },
            valueRange = 0f..1f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(brush.colorArgb))
                    .border(1.dp, Color.Gray, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text("#%06X".format(brush.colorArgb and 0xFFFFFF), color = Color.White)
        }
    }
}