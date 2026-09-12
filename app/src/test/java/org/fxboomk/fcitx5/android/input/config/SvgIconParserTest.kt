/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconParserTest {

    @Test
    fun scanTagsToleratesMalformedMarkup() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?><!DOCTYPE svg><!-- note -->
            <SVG width='10' viewBox="0 0 8 8"><path d='M0 0h8v8H0z' fill=#abc><circle cx=1 cy=2 r=3 />
        """.trimIndent()

        val tags = scanTags(xml)

        assertEquals(listOf("svg", "path", "circle"), tags.map { it.name })
        assertFalse(tags[0].isClose)
        assertEquals("10", tags[0].attrs["width"])
        assertEquals("0 0 8 8", tags[0].attrs["viewbox"])
        assertEquals("#abc", tags[1].attrs["fill"])
        assertEquals("1", tags[2].attrs["cx"])
        assertTrue(tags[2].isSelfClose)

        val closed = scanTags("<g transform=\"scale(2)\"><rect x=\"0\"/></g>")
        assertEquals(listOf("g", "rect", "g"), closed.map { it.name })
        assertTrue(closed[2].isClose)
        assertTrue(closed[1].isSelfClose)
    }

    @Test
    fun parseColorSupportsHexShorthandsNamesAndFunctions() {
        assertEquals(0xFF4285F4.toInt(), parseColor("#4285f4"))
        assertEquals(0xFFFF0000.toInt(), parseColor("#f00"))
        // #RGBA: last digit is alpha
        assertEquals(0x88FF0000.toInt(), parseColor("#f008"))
        // #RRGGBBAA: last pair is alpha
        assertEquals(0x804285F4.toInt(), parseColor("#4285f480"))
        assertEquals(0xFF008000.toInt(), parseColor("green"))
        assertEquals(0xFFFFA500.toInt(), parseColor(" ORANGE "))
        assertEquals(0xFF667799.toInt(), parseColor("rgb(102,119,153)"))
        assertEquals(0xFF667799.toInt(), parseColor("rgb(102 119 153)"))
        assertEquals(0x7FFF0000.toInt(), parseColor("rgba(255,0,0,0.5)"))
        assertEquals(0, parseColor("transparent"))
        assertNull(parseColor("#12345"))
        assertNull(parseColor("rebeccapurple"))
    }

    @Test
    fun parsePaintClassifiesFillDeclarations() {
        assertEquals(Pair(false, null), parsePaint("none"))
        assertEquals(Pair(true, null), parsePaint("currentColor"))
        assertEquals(Pair(true, null), parsePaint("url(#gradient)"))
        assertEquals(Pair(true, 0xFFFF0000.toInt()), parsePaint("#f00"))
        assertNull(parsePaint("not-a-color"))
    }

    @Test
    fun parseStyleDeclsExtractsDeclarations() {
        val decls = parseStyleDecls("fill:#abc ; stroke : red ;")
        assertEquals("#abc", decls["fill"])
        assertEquals("red", decls["stroke"])
        assertTrue(parseStyleDecls(null).isEmpty())
    }

    @Test
    fun parseTransformComposesFunctionsInOrder() {
        assertNull(parseTransform(null))
        assertEquals(
            floatArrayOf(2f, 0f, 0f, 2f, 10f, 20f).toList(),
            parseTransform("translate(10,20) scale(2)")!!.toList()
        )
        assertEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f).toList(),
            parseTransform("matrix(1,2,3,4,5,6)")!!.toList()
        )
        // rotate(90) about the origin maps (1,0) to (0,1)
        val rotate = parseTransform("rotate(90)")!!
        assertEquals(0f, rotate[0], 1e-5f)
        assertEquals(1f, rotate[1], 1e-5f)
        assertEquals(-1f, rotate[2], 1e-5f)
        assertEquals(0f, rotate[3], 1e-5f)
        // rotate(90, 10, 0) maps (10,0) to (10,0)
        val centered = parseTransform("rotate(90 10 0)")!!
        assertEquals(10f, centered[0] * 10f + centered[2] * 0f + centered[4], 1e-5f)
        assertEquals(0f, centered[1] * 10f + centered[3] * 0f + centered[5], 1e-5f)
        assertEquals(1f, parseTransform("skewX(45)")!![2], 1e-5f)
    }

    @Test
    fun mulAffineAppliesRightOperandFirst() {
        // translate(10, 20) * scale(2) maps (1,1) to (12, 22)
        val combined = mulAffine(affineTranslation(10f, 20f), affineScaling(2f, 2f))
        assertEquals(12f, combined[0] * 1f + combined[2] * 1f + combined[4], 1e-5f)
        assertEquals(22f, combined[1] * 1f + combined[3] * 1f + combined[5], 1e-5f)
    }

    @Test
    fun parseViewBoxRejectsMalformedValues() {
        assertEquals(floatArrayOf(-3f, 0f, 262f, 262f).toList(), parseViewBox("-3 0 262 262")!!.toList())
        assertEquals(floatArrayOf(0f, 0f, 24f, 24f).toList(), parseViewBox("0,0,24,24")!!.toList())
        assertNull(parseViewBox("0 0 24"))
        assertNull(parseViewBox("0 0 -24 24"))
        assertNull(parseViewBox(null))
    }

    @Test
    fun parseLengthStripsCommonUnits() {
        assertEquals(800f, parseLength("800px")!!, 1e-4f)
        assertEquals(96f, parseLength("1in")!!, 1e-4f)
        assertEquals(96f, parseLength("72pt")!!, 1e-4f)
        assertEquals(0.5f, parseLength(".5")!!, 1e-4f)
        assertNull(parseLength("auto"))
        assertNull(parseLength(null))
    }

    @Test
    fun parsePointsAcceptsMixedSeparators() {
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f), parsePoints("1,2 3 4,5,6").toList())
        assertTrue(parsePoints("  ").isEmpty())
    }

    @Test
    fun svgMarkupToleratesPrologBeforeSvgTag() {
        val markup = "<svg viewBox=\"0 0 24 24\"><path d=\"M0 0h24v24H0z\"/></svg>"
        val withProlog = "<?xml version=\"1.0\"?>\n<!-- icon -->$markup"
        assertEquals(withProlog.trim(), ButtonIconSpec.svgMarkup(withProlog))
        assertEquals(markup, ButtonIconSpec.svgMarkup("  $markup "))
        assertNull(ButtonIconSpec.svgMarkup("font:E141"))
        assertNull(ButtonIconSpec.svgMarkup(null))
    }
}
