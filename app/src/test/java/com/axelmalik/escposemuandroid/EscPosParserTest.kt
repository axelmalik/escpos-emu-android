package com.axelmalik.escposemuandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosParserTest {
    @Test
    fun splitTextAndLineFeedEmitTextThenLineFeed() {
        val parser = EscPosParser()

        val first = parser.feed("Hello".toByteArray())
        val second = parser.feed(byteArrayOf(0x0A))

        assertTrue(first.isEmpty())
        assertEquals(
            listOf(
                ParserEvent.Text("Hello", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            second,
        )
    }

    @Test
    fun alignmentAndBoldAreAppliedToFollowingText() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x61, 0x01,
                0x1B, 0x45, 0x01,
                'T'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(),
                0x1B, 0x45, 0x00,
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("Tot", TextAlignment.CENTER, true),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun zeroAbsolutePositionKeepsCenterAlignment() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x61, 0x01,
                0x1B, 0x24, 0x00, 0x00,
                'T'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("Test", TextAlignment.CENTER, false, 0),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun cutCommandsEmitCutWithoutBinaryTextNoise() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1D, 0x56, 0x00,
                0x1B, 0x69,
            ),
        )

        assertEquals(listOf(ParserEvent.Cut, ParserEvent.Cut), events)
    }

    @Test
    fun utf8TextIsPreservedUntilStreamFinishes() {
        val parser = EscPosParser()

        assertEquals(emptyList<ParserEvent>(), parser.feed("咖啡".toByteArray()))
        assertEquals(
            listOf(ParserEvent.Text("咖啡", TextAlignment.LEFT, false)),
            parser.finish(),
        )
    }

    @Test
    fun splitRasterHeaderUsesMsbFirstPixelMapping() {
        val parser = EscPosParser()
        val image = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            0x01, 0x00,
            0x02, 0x00,
            0x80.toByte(), 0x40,
        )

        val events = image.asList().chunked(2).flatMap { parser.feed(it.toByteArray()) }

        assertEquals(1, events.size)
        val raster = events.single() as ParserEvent.RasterImage
        assertEquals(8, raster.widthPx)
        assertEquals(2, raster.heightPx)
        assertTrue(raster.pixels[0])
        assertTrue(!raster.pixels[1])
        assertTrue(raster.pixels[8] == false)
        assertTrue(raster.pixels[9])
    }

    @Test
    fun rasterModeThreeDoublesWidthAndHeight() {
        val parser = EscPosParser()
        val events = parser.feed(
            byteArrayOf(
                0x1D, 0x76, 0x30, 0x03,
                0x01, 0x00,
                0x01, 0x00,
                0x80.toByte(),
            ),
        )

        val raster = events.single() as ParserEvent.RasterImage
        assertEquals(16, raster.widthPx)
        assertEquals(2, raster.heightPx)
        assertTrue(raster.pixels[0])
        assertTrue(raster.pixels[1])
        assertTrue(raster.pixels[16])
        assertTrue(raster.pixels[17])
    }

    @Test
    fun lineSpacingParameterIsNotRenderedAsText() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x33, 0x2E,
                'S'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
                't'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("Subtotal", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun gsLineSpacingParameterIsNotRenderedAsText() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1D, 0x33, 0x2C,
                'S'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
                't'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("Subtotal", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun absolutePrintPositionIsPreservedForColumns() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x24, 0x80.toByte(), 0x00,
                '5'.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("5200", TextAlignment.LEFT, false, 128),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun absolutePrintPositionResetsAfterLineFeed() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x24, 0x20, 0x00,
                'A'.code.toByte(), 0x0A,
                'B'.code.toByte(), 0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("A", TextAlignment.LEFT, false, 32),
                ParserEvent.LineFeed,
                ParserEvent.Text("B", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun separatorTextIsEmittedAsOneLine() {
        val parser = EscPosParser()

        val events = parser.feed("------------------------------\n".toByteArray())

        assertEquals(
            listOf(
                ParserEvent.Text("------------------------------", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun kanjiModeOffCommandDoesNotRenderItsPeriod() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1C, 0x2E,
                'S'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
                't'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
                0x0A,
            ),
        )

        assertEquals(
            listOf(
                ParserEvent.Text("Subtotal", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }

    @Test
    fun escStarImageIsDecodedWithoutBinaryTextNoise() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1B, 0x2A, 0x21, 0x01, 0x00,
                0x80.toByte(), 0x00, 0x00,
                0x0A,
            ),
        )

        assertEquals(1, events.size)
        val image = events.single() as ParserEvent.RasterImage
        assertEquals(1, image.widthPx)
        assertEquals(24, image.heightPx)
        assertTrue(image.pixels[0])
    }

    @Test
    fun graphicsRasterImageIsDecodedWithoutBinaryTextNoise() {
        val parser = EscPosParser()

        val events = parser.feed(
            byteArrayOf(
                0x1D, 0x28, 0x4C,
                0x0B, 0x00,
                48, 112, 48, 1, 1, 49,
                0x01, 0x00, 0x01, 0x00,
                0x80.toByte(),
            ),
        )

        assertEquals(1, events.size)
        val image = events.single() as ParserEvent.RasterImage
        assertEquals(8, image.widthPx)
        assertEquals(1, image.heightPx)
        assertTrue(image.pixels[0])
    }

    @Test
    fun latin1TextIsDecodedWhenUtf8IsInvalid() {
        val parser = EscPosParser()

        val events = parser.feed(byteArrayOf(0xE9.toByte(), 0x0A))

        assertEquals(
            listOf(
                ParserEvent.Text("é", TextAlignment.LEFT, false),
                ParserEvent.LineFeed,
            ),
            events,
        )
    }
}
