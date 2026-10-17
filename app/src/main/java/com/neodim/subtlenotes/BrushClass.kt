package com.neodim.subtlenotes

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes

class BrushClass {
    var type: BrushFamily by mutableStateOf(StockBrushes.pressurePen())
    var colorArgb: Int by mutableIntStateOf(Color.Black.toArgb())
    var size: Float by mutableFloatStateOf(5f)
    var epsilon: Float by mutableFloatStateOf(0.1f)
    val brush: Brush by derivedStateOf {
        Brush.createWithColorIntArgb(
            family = type,
            colorIntArgb = colorArgb,
            size = size,
            epsilon = epsilon,
        )
    }
}