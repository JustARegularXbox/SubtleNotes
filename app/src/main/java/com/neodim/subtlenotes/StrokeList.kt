package com.neodim.subtlenotes

import androidx.ink.geometry.Box
import androidx.ink.strokes.Stroke

data class StrokeList (
    val stroke: Stroke,
    val bounds: Box?,
)