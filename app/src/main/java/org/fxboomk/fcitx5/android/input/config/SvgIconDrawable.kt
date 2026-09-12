/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

internal class SvgTag(
    val name: String,
    val attrs: Map<String, String>,
    val isClose: Boolean,
    val isSelfClose: Boolean
)

/**
 * Scans SVG markup into tag tokens without requiring well-formed XML: unclosed or
 * mismatched tags, single-quoted/unquoted attributes, mixed-case tag names, and an
 * XML declaration, comments, CDATA or DOCTYPE before or around the markup are all
 * tolerated. Text nodes are ignored.
 */
private val TAG_REGEX = Regex(
    """<(?:!--[\s\S]*?-->|!\[CDATA\[[\s\S]*?\]\]>|\?[\s\S]*?\?>|![^>]*|/([A-Za-z][\w:.-]*)|([A-Za-z][\w:.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*))>"""
)

private val ATTR_REGEX = Regex("""([A-Za-z_:][-\w:.]*)\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>\`]+)""")

internal fun parseAttrs(blob: String): Map<String, String> = buildMap {
    for (match in ATTR_REGEX.findAll(blob)) {
        val name = match.groupValues[1].lowercase()
        var value = match.groupValues[2]
        if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0]) {
            value = value.substring(1, value.length - 1)
        }
        put(name, value.trim())
    }
}

internal fun scanTags(xml: String): List<SvgTag> = buildList {
    for (match in TAG_REGEX.findAll(xml)) {
        val closeName = match.groupValues[1]
        if (closeName.isNotEmpty()) {
            add(SvgTag(closeName.lowercase(), emptyMap(), isClose = true, isSelfClose = false))
            continue
        }
        val openName = match.groupValues[2]
        // Comments, CDATA, processing instructions and DOCTYPE carry no tag name.
        if (openName.isEmpty()) continue
        var blob = match.groupValues[3]
        val selfClose = match.value.endsWith("/>")
        if (selfClose && blob.endsWith("/")) blob = blob.removeSuffix("/")
        add(SvgTag(openName.lowercase(), parseAttrs(blob), isClose = false, isSelfClose = selfClose))
    }
}

private val NAMED_COLORS: Map<String, Long> = mapOf(
    "black" to 0xFF000000, "white" to 0xFFFFFFFF, "red" to 0xFFFF0000, "green" to 0xFF008000,
    "blue" to 0xFF0000FF, "yellow" to 0xFFFFFF00, "cyan" to 0xFF00FFFF, "aqua" to 0xFF00FFFF,
    "magenta" to 0xFFFF00FF, "fuchsia" to 0xFFFF00FF, "gray" to 0xFF808080, "grey" to 0xFF808080,
    "silver" to 0xFFC0C0C0, "maroon" to 0xFF800000, "olive" to 0xFF808000, "navy" to 0xFF000080,
    "teal" to 0xFF008080, "lime" to 0xFF00FF00, "orange" to 0xFFFFA500, "purple" to 0xFF800080,
    "pink" to 0xFFFFC0CB, "brown" to 0xFFA52A2A, "gold" to 0xFFFFD700, "indigo" to 0xFF4B0082,
    "violet" to 0xFFEE82EE, "crimson" to 0xFFDC143C, "darkgray" to 0xFFA9A9A9,
    "darkgrey" to 0xFFA9A9A9, "lightgray" to 0xFFD3D3D3, "lightgrey" to 0xFFD3D3D3,
    "darkred" to 0xFF8B0000, "darkgreen" to 0xFF006400, "darkblue" to 0xFF00008B,
    "lightblue" to 0xFFADD8E6, "lightgreen" to 0xFF90EE90, "darkorange" to 0xFFFF8C00,
    "skyblue" to 0xFF87CEEB, "steelblue" to 0xFF4682B4, "tomato" to 0xFFFF6347,
    "coral" to 0xFFFF7F50, "salmon" to 0xFFFA8072, "khaki" to 0xFFF0E68C, "beige" to 0xFFF5F5DC
)

private val FUNC_COLOR_REGEX = Regex("""^rgba?\(([^)]*)\)$""")

/**
 * Parses an SVG/CSS color into ARGB. Returns null for colors that cannot be resolved
 * (unknown names, unsupported syntax); the caller then falls back to the inherited paint.
 */
internal fun parseColor(raw: String): Int? {
    val value = raw.trim().lowercase()
    if (value.startsWith("#")) {
        val hex = value.substring(1)
        fun valid(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' }
        val longHex = if (hex.length == 3 || hex.length == 4) hex.map { "$it$it" }.joinToString("") else hex
        return when (longHex.length) {
            6 -> if (valid(longHex)) (0xFF000000L or longHex.toLong(16)).toInt() else null
            8 -> if (valid(longHex)) {
                (longHex.substring(6, 8).toLong(16) shl 24 or longHex.substring(0, 6).toLong(16)).toInt()
            } else null
            else -> null
        }
    }
    if (value == "transparent") return 0
    NAMED_COLORS[value]?.toInt()?.let { return it }
    FUNC_COLOR_REGEX.find(value)?.let { match ->
        val parts = match.groupValues[1].split(Regex("[\\s,/]+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun channel(s: String): Int? {
            val t = s.trim()
            return if (t.endsWith("%")) {
                t.dropLast(1).toFloatOrNull()?.let { (it.coerceIn(0f, 100f) * 255f / 100f).toInt() }
            } else {
                t.toIntOrNull()?.coerceIn(0, 255)
            }
        }
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = if (parts.size >= 4) {
            val t = parts[3].trim()
            val alpha = if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.div(100f) else t.toFloatOrNull()
            alpha?.coerceIn(0f, 1f)?.let { (it * 255f).toInt() } ?: return null
        } else 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return null
}

/**
 * Resolves a fill/stroke declaration to (visible, explicitColor). A null result means the
 * declaration was absent or unresolvable and the inherited paint applies. Null color with
 * visible=true means the paint follows the theme tint (currentColor, gradients, inherit).
 */
internal fun parsePaint(raw: String): Pair<Boolean, Int?>? {
    val value = raw.trim().lowercase()
    return when {
        value.isEmpty() -> null
        value == "none" -> Pair(false, null)
        value == "currentcolor" || value == "inherit" -> Pair(true, null)
        value.startsWith("url(") -> Pair(true, null)
        else -> parseColor(value)?.let { Pair(true, it) }
    }
}

internal fun parseStyleDecls(raw: String?): Map<String, String> {
    raw ?: return emptyMap()
    return buildMap {
        for (decl in raw.split(';')) {
            val i = decl.indexOf(':')
            if (i <= 0) continue
            put(decl.substring(0, i).trim().lowercase(), decl.substring(i + 1).trim())
        }
    }
}

private val LENGTH_REGEX = Regex("""[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""")

/** Parses a length, stripping the common SVG units (px, pt, in, ...). */
internal fun parseLength(raw: String?): Float? {
    raw ?: return null
    val text = raw.trim()
    val match = LENGTH_REGEX.find(text) ?: return null
    if (match.range.first != 0) return null
    var value = match.value.toFloatOrNull() ?: return null
    when (text.substring(match.value.length).trimStart().lowercase()) {
        "pt" -> value *= 96f / 72f
        "pc" -> value *= 16f
        "mm" -> value *= 96f / 25.4f
        "cm" -> value *= 96f / 2.54f
        "in" -> value *= 96f
    }
    return value
}

internal fun parseViewBox(raw: String?): FloatArray? {
    val parts = raw?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull(String::toFloatOrNull) ?: return null
    if (parts.size != 4 || parts[2] <= 0f || parts[3] <= 0f) return null
    return parts.toFloatArray()
}

internal fun parsePoints(raw: String): FloatArray =
    raw.trim().split(Regex("[\\s,]+")).mapNotNull(String::toFloatOrNull).toFloatArray()

internal fun affineIdentity(): FloatArray = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

internal fun affineTranslation(tx: Float, ty: Float): FloatArray = floatArrayOf(1f, 0f, 0f, 1f, tx, ty)

internal fun affineScaling(sx: Float, sy: Float): FloatArray = floatArrayOf(sx, 0f, 0f, sy, 0f, 0f)

internal fun affineRotation(degrees: Float): FloatArray {
    val rad = Math.toRadians(degrees.toDouble())
    val c = rad.let { kotlin.math.cos(it) }.toFloat()
    val s = kotlin.math.sin(rad).toFloat()
    return floatArrayOf(c, s, -s, c, 0f, 0f)
}

/** Composes two SVG affines: the result applies [b] to points first, then [a]. */
internal fun mulAffine(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
    a[0] * b[0] + a[2] * b[1],
    a[1] * b[0] + a[3] * b[1],
    a[0] * b[2] + a[2] * b[3],
    a[1] * b[2] + a[3] * b[3],
    a[0] * b[4] + a[2] * b[5] + a[4],
    a[1] * b[4] + a[3] * b[5] + a[5]
)

private val TRANSFORM_REGEX = Regex("""([a-zA-Z]+)\s*\(([^)]*)\)""")

/** Parses a transform list into one composed affine; unsupported or malformed parts are skipped. */
internal fun parseTransform(raw: String?): FloatArray? {
    raw ?: return null
    var acc: FloatArray? = null
    for (match in TRANSFORM_REGEX.findAll(raw)) {
        val name = match.groupValues[1].lowercase()
        val args = match.groupValues[2].trim().split(Regex("[\\s,]+"))
            .mapNotNull(String::toFloatOrNull)
        val next = when (name) {
            "translate" -> when (args.size) {
                1 -> affineTranslation(args[0], 0f)
                2 -> affineTranslation(args[0], args[1])
                else -> null
            }
            "scale" -> when (args.size) {
                1 -> affineScaling(args[0], args[0])
                2 -> affineScaling(args[0], args[1])
                else -> null
            }
            "rotate" -> when (args.size) {
                1 -> affineRotation(args[0])
                3 -> mulAffine(
                    mulAffine(affineTranslation(args[1], args[2]), affineRotation(args[0])),
                    affineTranslation(-args[1], -args[2])
                )
                else -> null
            }
            "matrix" -> if (args.size == 6) args.toFloatArray() else null
            "skewx" -> if (args.size == 1) {
                floatArrayOf(1f, 0f, kotlin.math.tan(Math.toRadians(args[0].toDouble())).toFloat(), 1f, 0f, 0f)
            } else null
            "skewy" -> if (args.size == 1) {
                floatArrayOf(1f, kotlin.math.tan(Math.toRadians(args[0].toDouble())).toFloat(), 0f, 1f, 0f, 0f)
            } else null
            else -> null
        } ?: continue
        acc = acc?.let { mulAffine(it, next) } ?: next
    }
    return acc
}

internal data class SvgShape internal constructor(
    internal val path: Path,
    internal val affine: FloatArray,
    internal val fillColor: Int?,
    internal val fillOpacity: Float,
    internal val strokeColor: Int?,
    internal val strokeOpacity: Float,
    internal val strokeWidth: Float,
    internal val strokeCap: Paint.Cap,
    internal val strokeJoin: Paint.Join
)

internal class SvgIcon internal constructor(
    internal val shapes: List<SvgShape>,
    internal val viewMinX: Float,
    internal val viewMinY: Float,
    internal val viewWidth: Float,
    internal val viewHeight: Float
)

/**
 * A lenient SVG icon renderer for configurable toolbar buttons, covering the subset
 * commonly used for icons:
 * - <path>, <rect>, <circle>, <ellipse>, <line>, <polygon>, <polyline>, including shapes
 *   inside <defs> instantiated through <use href="#id">
 * - fill/stroke paints inherited from enclosing <g>/<svg>, via style declarations or
 *   presentation attributes, with #RGB/#RGBA/#RRGGBB/#RRGGBBAA hex, rgb()/rgba() and
 *   common named colors
 * - translate/scale/rotate/matrix/skewX/skewY transforms on <g> and shapes
 * - an offset viewBox, falling back to width/height attributes or the shape bounds
 *
 * Shapes with a non-black explicit color keep it, so colorful icons ignore the theme
 * tint; monochrome icons (black or unspecified paints) follow the theme tint instead.
 */
internal class SvgIconDrawable private constructor(
    private val icon: SvgIcon,
    private val density: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var alphaValue = 255
    private var tintList: ColorStateList? = null
    private var tintColor: Int? = null

    override fun draw(canvas: Canvas) {
        if (icon.viewWidth <= 0f || icon.viewHeight <= 0f) return
        val scale = minOf(bounds.width() / icon.viewWidth, bounds.height() / icon.viewHeight)
        val baseAlpha = alphaValue / 255f
        val matrix = Matrix()
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - icon.viewWidth * scale) / 2f,
            bounds.top + (bounds.height() - icon.viewHeight * scale) / 2f
        )
        canvas.scale(scale, scale)
        canvas.translate(-icon.viewMinX, -icon.viewMinY)
        for (shape in icon.shapes) {
            matrix.setValues(
                floatArrayOf(
                    shape.affine[0], shape.affine[2], shape.affine[4],
                    shape.affine[1], shape.affine[3], shape.affine[5],
                    0f, 0f, 1f
                )
            )
            canvas.save()
            canvas.concat(matrix)
            if (shape.fillOpacity > 0f) {
                paint.style = Paint.Style.FILL
                applyPaintColor(shape.fillColor, shape.fillOpacity * baseAlpha)
                canvas.drawPath(shape.path, paint)
            }
            if (shape.strokeOpacity > 0f && shape.strokeWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shape.strokeWidth
                paint.strokeCap = shape.strokeCap
                paint.strokeJoin = shape.strokeJoin
                applyPaintColor(shape.strokeColor, shape.strokeOpacity * baseAlpha)
                canvas.drawPath(shape.path, paint)
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun applyPaintColor(explicit: Int?, opacity: Float) {
        val base = explicit ?: tintColor ?: Color.BLACK
        paint.color = base
        paint.alpha = ((base ushr 24) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun setTint(tintColor: Int) {
        tintList = null
        this.tintColor = tintColor
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        tintList = tint
        tintColor = tint?.getColorForState(state, tint.defaultColor)
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tintList?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        val tint = tintList ?: return false
        val newColor = tint.getColorForState(state, tint.defaultColor)
        if (newColor == tintColor) return false
        tintColor = newColor
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth(): Int = (24f * density + 0.5f).toInt()
    override fun getIntrinsicHeight(): Int = getIntrinsicWidth()
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun parse(xml: String, density: Float): Drawable? = runCatching {
            parseLenientSvg(xml)?.let { SvgIconDrawable(it, density) }
        }.getOrNull()
    }
}

private val SHAPE_NAMES = setOf("path", "rect", "circle", "ellipse", "line", "polygon", "polyline")

// Rendered for their children only in browsers; icons cannot support them, so drop the subtree.
private val SKIP_SUBTREES = setOf(
    "style", "title", "desc", "metadata", "clippath", "mask", "filter", "marker",
    "pattern", "script", "lineargradient", "radialgradient", "text", "foreignobject"
)

private const val MAX_SHAPES = 4096

private const val OPAQUE_BLACK = 0xFF000000.toInt()

private data class PaintState(
    var fillVisible: Boolean = true,
    var fillColor: Int? = null,
    var fillOpacity: Float = 1f,
    var strokeVisible: Boolean = false,
    var strokeColor: Int? = null,
    var strokeOpacity: Float = 1f,
    var strokeWidth: Float = 1f,
    var strokeCap: Paint.Cap = Paint.Cap.BUTT,
    var strokeJoin: Paint.Join = Paint.Join.MITER,
    var opacity: Float = 1f,
    var affine: FloatArray = affineIdentity()
)

private data class UseRef(val href: String, val affine: FloatArray, val dx: Float, val dy: Float)

private fun buildPath(name: String, attrs: Map<String, String>): Path? {
    fun length(key: String): Float? = attrs[key]?.let(::parseLength)
    return when (name) {
        "path" -> attrs["d"]?.takeIf { it.isNotBlank() }?.let { data ->
            runCatching { PathParser.createPathFromPathData(data) }.getOrNull()
        }
        "rect" -> {
            val w = length("width") ?: return null
            val h = length("height") ?: return null
            if (w <= 0f || h <= 0f) return null
            val x = length("x") ?: 0f
            val y = length("y") ?: 0f
            val rxRaw = length("rx")
            val ryRaw = length("ry")
            val rx = (rxRaw ?: ryRaw ?: 0f).coerceAtMost(w / 2f)
            val ry = (ryRaw ?: rxRaw ?: 0f).coerceAtMost(h / 2f)
            Path().apply {
                if (rx > 0f || ry > 0f) {
                    addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                } else {
                    addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
                }
            }
        }
        "circle" -> {
            val r = length("r") ?: return null
            if (r <= 0f) return null
            val cx = length("cx") ?: 0f
            val cy = length("cy") ?: 0f
            Path().apply { addOval(RectF(cx - r, cy - r, cx + r, cy + r), Path.Direction.CW) }
        }
        "ellipse" -> {
            val rx = length("rx") ?: return null
            val ry = length("ry") ?: return null
            if (rx <= 0f || ry <= 0f) return null
            val cx = length("cx") ?: 0f
            val cy = length("cy") ?: 0f
            Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }
        }
        "line" -> {
            val x1 = length("x1") ?: 0f
            val y1 = length("y1") ?: 0f
            val x2 = length("x2") ?: 0f
            val y2 = length("y2") ?: 0f
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
        }
        "polygon", "polyline" -> {
            val points = parsePoints(attrs["points"] ?: return null)
            if (points.size < 4) return null
            Path().apply {
                moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    lineTo(points[i], points[i + 1])
                    i += 2
                }
                if (name == "polygon") close()
            }
        }
        else -> null
    }
}

internal fun parseLenientSvg(xml: String): SvgIcon? {
    var viewBox: FloatArray? = null
    var size: FloatArray? = null

    val mainShapes = ArrayList<SvgShape>()
    val defsShapes = ArrayList<SvgShape>()
    var collectDepth = 0
    val shapesById = HashMap<String, MutableList<SvgShape>>()
    val pendingUses = ArrayList<UseRef>()
    val groupIds = ArrayList<String>()
    val skipStack = ArrayDeque<String>()
    val stateStack = ArrayDeque<Pair<PaintState, Int>>()
    var state = PaintState()

    fun register(shape: SvgShape, ownId: String?) {
        for (id in groupIds) shapesById.getOrPut(id) { ArrayList() }.add(shape)
        if (!ownId.isNullOrBlank()) shapesById.getOrPut(ownId) { ArrayList() }.add(shape)
    }

    fun applyPaintAttrs(attrs: Map<String, String>) {
        val style = parseStyleDecls(attrs["style"])
        fun prop(name: String): String? = style[name] ?: attrs[name]
        prop("fill")?.let { raw ->
            parsePaint(raw)?.let { (visible, color) ->
                state.fillVisible = visible
                state.fillColor = color
            }
        }
        prop("fill-opacity")?.let { state.fillOpacity = (parseLength(it) ?: 1f).coerceIn(0f, 1f) }
        prop("stroke")?.let { raw ->
            parsePaint(raw)?.let { (visible, color) ->
                state.strokeVisible = visible
                state.strokeColor = color
            }
        }
        prop("stroke-width")?.let { state.strokeWidth = (parseLength(it) ?: 1f).coerceAtLeast(0f) }
        prop("stroke-opacity")?.let { state.strokeOpacity = (parseLength(it) ?: 1f).coerceIn(0f, 1f) }
        prop("stroke-linecap")?.let {
            state.strokeCap = when (it.trim().lowercase()) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
        }
        prop("stroke-linejoin")?.let {
            state.strokeJoin = when (it.trim().lowercase()) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
        }
        prop("opacity")?.let { state.opacity *= (parseLength(it) ?: 1f).coerceIn(0f, 1f) }
    }

    fun emitShape(name: String, attrs: Map<String, String>) {
        if (mainShapes.size + defsShapes.size >= MAX_SHAPES) return
        val style = parseStyleDecls(attrs["style"])
        fun prop(key: String): String? = style[key] ?: attrs[key]

        var fillVisible = state.fillVisible
        var fillColor = state.fillColor
        var fillOpacity = state.fillOpacity
        prop("fill")?.let { raw ->
            parsePaint(raw)?.let { (visible, color) ->
                fillVisible = visible
                fillColor = color
            }
        }
        prop("fill-opacity")?.let { fillOpacity = (parseLength(it) ?: 1f).coerceIn(0f, 1f) }
        var strokeVisible = state.strokeVisible
        var strokeColor = state.strokeColor
        var strokeOpacity = state.strokeOpacity
        var strokeWidth = state.strokeWidth
        prop("stroke")?.let { raw ->
            parsePaint(raw)?.let { (visible, color) ->
                strokeVisible = visible
                strokeColor = color
            }
        }
        prop("stroke-width")?.let { strokeWidth = (parseLength(it) ?: 1f).coerceAtLeast(0f) }
        prop("stroke-opacity")?.let { strokeOpacity = (parseLength(it) ?: 1f).coerceIn(0f, 1f) }
        prop("opacity")?.let {
            val own = (parseLength(it) ?: 1f).coerceIn(0f, 1f)
            fillOpacity *= own
            strokeOpacity *= own
        }

        if (name == "line") fillVisible = false
        if (!fillVisible && !strokeVisible) return
        val path = buildPath(name, attrs) ?: return
        val local = parseTransform(attrs["transform"]) ?: affineIdentity()
        val shape = SvgShape(
            path,
            mulAffine(state.affine, local),
            fillColor,
            if (fillVisible) fillOpacity * state.opacity else 0f,
            strokeColor,
            if (strokeVisible) strokeOpacity * state.opacity else 0f,
            strokeWidth,
            state.strokeCap,
            state.strokeJoin
        )
        if (collectDepth > 0) defsShapes.add(shape) else mainShapes.add(shape)
        register(shape, attrs["id"])
    }

    for (tag in scanTags(xml)) {
        val name = tag.name
        if (skipStack.isNotEmpty()) {
            if (tag.isClose) {
                if (name == skipStack.last()) skipStack.removeLast()
            } else if (!tag.isSelfClose && name in SKIP_SUBTREES) {
                skipStack.addLast(name)
            }
            continue
        }
        when {
            tag.isClose -> when (name) {
                "g", "svg" -> {
                    val previous = stateStack.removeLastOrNull()
                    if (previous != null) {
                        state = previous.first
                        while (groupIds.size > previous.second) groupIds.removeAt(groupIds.size - 1)
                    }
                }
                "defs", "symbol" -> collectDepth = (collectDepth - 1).coerceAtLeast(0)
            }
            tag.isSelfClose -> when {
                name in SHAPE_NAMES -> emitShape(name, tag.attrs)
                name == "use" -> {
                    val href = (tag.attrs["href"] ?: tag.attrs["xlink:href"])?.trim()?.removePrefix("#")
                    if (!href.isNullOrEmpty()) {
                        val dx = parseLength(tag.attrs["x"]) ?: 0f
                        val dy = parseLength(tag.attrs["y"]) ?: 0f
                        val useTransform = parseTransform(tag.attrs["transform"])
                        val affine = if (useTransform != null) mulAffine(state.affine, useTransform) else state.affine
                        pendingUses.add(UseRef(href, affine.copyOf(), dx, dy))
                    }
                }
            }
            else -> when (name) {
                "g", "svg" -> {
                    stateStack.addLast(Pair(state.copy(), groupIds.size))
                    applyPaintAttrs(tag.attrs)
                    tag.attrs["id"]?.let { groupIds.add(it) }
                    parseTransform(tag.attrs["transform"])?.let {
                        state.affine = mulAffine(state.affine, it)
                    }
                    if (name == "svg") {
                        if (viewBox == null) viewBox = parseViewBox(tag.attrs["viewbox"])
                        if (viewBox == null && size == null) {
                            val w = parseLength(tag.attrs["width"])
                            val h = parseLength(tag.attrs["height"])
                            if (w != null && h != null && w > 0f && h > 0f) size = floatArrayOf(w, h)
                        }
                    }
                }
                "defs", "symbol" -> collectDepth++
                "use" -> {
                    val href = (tag.attrs["href"] ?: tag.attrs["xlink:href"])?.trim()?.removePrefix("#")
                    if (!href.isNullOrEmpty()) {
                        val dx = parseLength(tag.attrs["x"]) ?: 0f
                        val dy = parseLength(tag.attrs["y"]) ?: 0f
                        val useTransform = parseTransform(tag.attrs["transform"])
                        val affine = if (useTransform != null) mulAffine(state.affine, useTransform) else state.affine
                        pendingUses.add(UseRef(href, affine.copyOf(), dx, dy))
                    }
                }
                in SKIP_SUBTREES -> skipStack.addLast(name)
                in SHAPE_NAMES -> emitShape(name, tag.attrs)
            }
        }
    }

    for (use in pendingUses) {
        val targets = shapesById[use.href] ?: continue
        val useAffine = mulAffine(use.affine, affineTranslation(use.dx, use.dy))
        for (target in targets) {
            if (mainShapes.size >= MAX_SHAPES) break
            mainShapes.add(target.copy(affine = mulAffine(useAffine, target.affine)))
        }
    }

    if (mainShapes.isEmpty()) return null

    // Explicit black is the default from most icon packs; treat a fully black/unspecified
    // icon as monochrome so it keeps following the theme tint.
    val colorful = mainShapes.any { shape ->
        (shape.fillColor != null && shape.fillColor != OPAQUE_BLACK) ||
            (shape.strokeColor != null && shape.strokeColor != OPAQUE_BLACK)
    }
    val shapes = if (colorful) mainShapes else mainShapes.map {
        it.copy(fillColor = null, strokeColor = null)
    }

    val bounds = RectF()
    if (viewBox == null && size == null) {
        val matrix = Matrix()
        for (shape in shapes) {
            val r = RectF()
            shape.path.computeBounds(r, true)
            matrix.setValues(
                floatArrayOf(
                    shape.affine[0], shape.affine[2], shape.affine[4],
                    shape.affine[1], shape.affine[3], shape.affine[5],
                    0f, 0f, 1f
                )
            )
            matrix.mapRect(r)
            bounds.union(r)
        }
        if (bounds.isEmpty) return null
    }

    val (minX, minY, width, height) = when {
        viewBox != null -> listOf(viewBox[0], viewBox[1], viewBox[2], viewBox[3])
        size != null -> listOf(0f, 0f, size[0], size[1])
        else -> listOf(bounds.left, bounds.top, bounds.width(), bounds.height())
    }
    if (width <= 0f || height <= 0f) return null
    return SvgIcon(shapes, minX, minY, width, height)
}
