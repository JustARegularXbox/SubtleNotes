package com.neodim.subtlenotes

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import java.util.UUID

data class CanvasMeta(val id: String, val name: String, val modified: Long)

data class ViewState(val x: Float, val y: Float, val zoom: Float)

class LoadedCanvas(val meta: CanvasMeta, val docWidth: Float, val view: ViewState, val strokes: List<StrokeList>)

class CanvasStorage(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private fun fileFor(id: String) = File(dir, "$id.snc")

    private var inputsCache = IdentityHashMap<Stroke, ByteArray>()
    private var familyCache = IdentityHashMap<BrushFamily, ByteArray>()

    fun list(): List<CanvasMeta> =
        (dir.listFiles { f -> f.name.endsWith(".snc") } ?: emptyArray())
            .mapNotNull { readHeader(it) }
            .sortedByDescending { it.modified }

    fun create(name: String, docWidth: Float): CanvasMeta {
        val id = UUID.randomUUID().toString()
        save(id, name, docWidth, ViewState(0f, 0f, 1f), emptyList())
        return CanvasMeta(id, name, fileFor(id).lastModified())
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    fun rename(id: String, newName: String) {
        val target = fileFor(id)
        val modified = target.lastModified()
        val bytes = target.readBytes()
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        if (inp.readInt() != MAGIC) throw IOException("Not a SubtleNotes canvas: $target")
        val version = inp.readInt()
        inp.readUTF() // old name
        val bodyOffset = bytes.size - inp.available()

        val tmp = File(dir, "$id.snc.tmp")
        val fos = FileOutputStream(tmp)
        DataOutputStream(BufferedOutputStream(fos)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(version)
            out.writeUTF(newName)
            out.write(bytes, bodyOffset, bytes.size - bodyOffset)
            out.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not move $tmp to $target")
        }
        target.setLastModified(modified)
    }

    fun save(id: String, name: String, docWidth: Float, view: ViewState, strokes: List<Stroke>) {
        val familyIndexByContent = HashMap<String, Int>()
        val familyBlobs = ArrayList<ByteArray>()
        val familyOf = IntArray(strokes.size)
        val newFamilyCache = IdentityHashMap<BrushFamily, ByteArray>()
        val newInputsCache = IdentityHashMap<Stroke, ByteArray>()
        strokes.forEachIndexed { i, stroke ->
            val family = stroke.brush.family
            val blob = newFamilyCache.getOrPut(family) {
                familyCache[family] ?: ByteArrayOutputStream().also { family.encode(it) }.toByteArray()
            }
            newInputsCache[stroke] = inputsCache[stroke]
                ?: ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()
            val key = String(blob, Charsets.ISO_8859_1)
            familyOf[i] = familyIndexByContent.getOrPut(key) {
                familyBlobs.add(blob)
                familyBlobs.lastIndex
            }
        }

        val tmp = File(dir, "$id.snc.tmp")
        val fos = FileOutputStream(tmp)
        DataOutputStream(BufferedOutputStream(fos)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(name)
            out.writeFloat(docWidth)
            out.writeFloat(view.y)
            out.writeFloat(view.zoom)
            out.writeFloat(view.x)

            out.writeInt(familyBlobs.size)
            familyBlobs.forEach { out.writeInt(it.size); out.write(it) }

            out.writeInt(strokes.size)
            strokes.forEachIndexed { i, stroke ->
                val brush = stroke.brush
                out.writeInt(familyOf[i])
                out.writeInt(brush.colorIntArgb)
                out.writeFloat(brush.size)
                out.writeFloat(brush.epsilon)
                val inputs = newInputsCache.getValue(stroke)
                out.writeInt(inputs.size)
                out.write(inputs)
            }
            out.flush()
            fos.fd.sync()
        }

        val target = fileFor(id)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not move $tmp to $target")
        }
        familyCache = newFamilyCache
        inputsCache = newInputsCache
    }

    fun load(id: String): LoadedCanvas {
        val file = fileFor(id)
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            if (inp.readInt() != MAGIC) throw IOException("Not a SubtleNotes canvas: $file")
            val version = inp.readInt()
            if (version !in 1..VERSION) throw IOException("Unsupported canvas version $version")
            val name = inp.readUTF()
            val docWidth = inp.readFloat()
            val scrollY = if (version >= 2) inp.readFloat() else 0f
            val zoom = if (version >= 3) inp.readFloat() else 1f
            val viewX = if (version >= 3) inp.readFloat() else 0f

            val families = List(inp.readInt()) {
                BrushFamily.decode(ByteArrayInputStream(inp.readBlob()))
            }

            val strokes = List(inp.readInt()) {
                val familyIdx = inp.readInt()
                val color = inp.readInt()
                val size = inp.readFloat()
                val epsilon = inp.readFloat()
                val inputs = StrokeInputBatch.decode(ByteArrayInputStream(inp.readBlob()))
                val brush = Brush.createWithColorIntArgb(
                    family = families[familyIdx],
                    colorIntArgb = color,
                    size = size,
                    epsilon = epsilon,
                )
                val stroke = Stroke(brush, inputs)
                StrokeList(stroke, stroke.shape.computeBoundingBox())
            }
            return LoadedCanvas(CanvasMeta(id, name, file.lastModified()), docWidth, ViewState(viewX, scrollY, zoom), strokes)
        }
    }

    private fun readHeader(file: File): CanvasMeta? = try {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            if (inp.readInt() != MAGIC) return null
            inp.readInt() // version
            CanvasMeta(file.nameWithoutExtension, inp.readUTF(), file.lastModified())
        }
    } catch (e: Exception) {
        null
    }

    private fun DataInputStream.readBlob(): ByteArray =
        ByteArray(readInt()).also { readFully(it) }

    private companion object {
        const val MAGIC = 0x534E4331 // "SNC1"
        const val VERSION = 3
    }
}