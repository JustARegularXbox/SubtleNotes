package com.neodim.subtlenotes

import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withSave
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import java.io.OutputStream
import kotlin.math.ceil

private const val PAGE_W_PT = 595   // A4 at 72 pt/inch
private const val PAGE_H_PT = 842
private val PAPER_COLOR = AndroidColor.BLACK   // matches the on-screen paper; WHITE if it's going to a printer

fun writePdf(strokes: List<StrokeList>, docWidth: Float, out: OutputStream) {
    val s = PAGE_W_PT / docWidth                 // pt per world unit
    val pageWorldH = PAGE_H_PT / s
    val contentBottom = strokes.maxOfOrNull { it.bounds?.yMax ?: 0f } ?: 0f
    val pageCount = maxOf(1, ceil(contentBottom / pageWorldH).toInt())

    val renderer = CanvasStrokeRenderer.create()
    val pdf = PdfDocument()
    try {
        for (p in 0 until pageCount) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W_PT, PAGE_H_PT, p + 1).create())
            val canvas = page.canvas
            canvas.drawColor(PAPER_COLOR)

            val top = p * pageWorldH
            val bottom = top + pageWorldH
            val toPage = MutableAffineTransform()
            AffineTransform.multiply(
                ImmutableAffineTransform.scale(s, s),
                ImmutableAffineTransform.translate(ImmutableVec(0f, -top)),
                toPage,
            )
            canvas.withSave {
                clipRect(0f, 0f, PAGE_W_PT.toFloat(), PAGE_H_PT.toFloat())
                scale(s, s)
                translate(0f, -top)
                for (item in strokes) {
                    val b = item.bounds
                    if (b != null && (b.yMax < top || b.yMin > bottom)) continue
                    renderer.draw(canvas = this, stroke = item.stroke, strokeToScreenTransform = toPage)
                }
            }
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
    } finally {
        pdf.close()
    }
}