package com.axelmalik.escposemuandroid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque

enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

sealed class ParserEvent {
    data class Text(
        val value: String,
        val alignment: TextAlignment,
        val bold: Boolean,
        val positionPx: Int? = null,
    ) : ParserEvent()

    data object LineFeed : ParserEvent()

    data object Cut : ParserEvent()

    data class RasterImage(
        val widthPx: Int,
        val heightPx: Int,
        val pixels: BooleanArray,
        val alignment: TextAlignment = TextAlignment.LEFT,
    ) : ParserEvent()
}

class EscPosParser {
    private enum class State {
        NORMAL,
        ESC,
        ESC_ALIGN,
        ESC_BOLD,
        ESC_CUT,
        ESC_BIT_IMAGE_MODE,
        ESC_BIT_IMAGE_WIDTH_LOW,
        ESC_BIT_IMAGE_WIDTH_HIGH,
        ESC_BIT_IMAGE_PAYLOAD,
        ESC_POSITION_LOW,
        ESC_POSITION_HIGH,
        SKIP_PARAMETERS,
        FS,
        GS,
        GS_CUT,
        GS_CUT_FEED,
        GS_FUNCTION_NAME,
        GS_FUNCTION_LENGTH_LOW,
        GS_FUNCTION_LENGTH_HIGH,
        GS_FUNCTION_PAYLOAD,
        GS_RASTER_SELECTOR,
        GS_RASTER_MODE,
        GS_RASTER_HEADER,
        GS_RASTER_PAYLOAD,
    }

    private val input = ArrayDeque<Int>()
    private val textBytes = ByteArrayOutputStream()
    private val rasterHeader = ByteArrayOutputStream(4)
    private var rasterPayload = ByteArray(0)
    private var rasterPayloadOffset = 0
    private var rasterMode = 0
    private var parametersToSkip = 0
    private var bitImageMode = 0
    private var bitImageWidth = 0
    private var bitImagePayload = ByteArray(0)
    private var bitImagePayloadOffset = 0
    private var printPositionPx: Int? = null
    private var ignoreLineFeedAfterBitImage = false
    private var gsFunctionLength = 0
    private var gsFunctionPayload = ByteArray(0)
    private var gsFunctionPayloadOffset = 0
    private var state = State.NORMAL
    private var alignment = TextAlignment.LEFT
    private var bold = false

    fun feed(bytes: ByteArray): List<ParserEvent> {
        bytes.forEach { input.addLast(it.toInt() and 0xFF) }
        return parseAvailable()
    }

    fun finish(): List<ParserEvent> {
        val events = mutableListOf<ParserEvent>()
        flushText(events)
        return events
    }

    private fun parseAvailable(): List<ParserEvent> {
        val events = mutableListOf<ParserEvent>()
        var keepParsing = true
        while (keepParsing) {
            keepParsing = when (state) {
                State.NORMAL -> parseNormal(events)
                State.ESC -> parseEsc(events)
                State.ESC_ALIGN -> parseAlignment()
                State.ESC_BOLD -> parseBold()
                State.ESC_CUT -> parseEscCut(events)
                State.ESC_BIT_IMAGE_MODE -> parseBitImageMode()
                State.ESC_BIT_IMAGE_WIDTH_LOW -> parseBitImageWidthLow()
                State.ESC_BIT_IMAGE_WIDTH_HIGH -> parseBitImageWidthHigh()
                State.ESC_BIT_IMAGE_PAYLOAD -> parseBitImagePayload(events)
                State.ESC_POSITION_LOW -> parsePositionLow()
                State.ESC_POSITION_HIGH -> parsePositionHigh()
                State.SKIP_PARAMETERS -> parseSkippedParameters()
                State.FS -> parseFs()
                State.GS -> parseGs()
                State.GS_CUT -> parseGsCut(events)
                State.GS_CUT_FEED -> parseGsCutFeed(events)
                State.GS_FUNCTION_NAME -> parseGsFunctionName()
                State.GS_FUNCTION_LENGTH_LOW -> parseGsFunctionLengthLow()
                State.GS_FUNCTION_LENGTH_HIGH -> parseGsFunctionLengthHigh()
                State.GS_FUNCTION_PAYLOAD -> parseGsFunctionPayload(events)
                State.GS_RASTER_SELECTOR -> parseRasterSelector()
                State.GS_RASTER_MODE -> parseRasterMode()
                State.GS_RASTER_HEADER -> parseRasterHeader()
                State.GS_RASTER_PAYLOAD -> parseRasterPayload(events)
            }
        }
        return events
    }

    private fun parseNormal(events: MutableList<ParserEvent>): Boolean {
        val byte = poll() ?: return false
        if (byte != LF) ignoreLineFeedAfterBitImage = false
        when (byte) {
            ESC -> {
                flushText(events)
                state = State.ESC
            }

            GS -> {
                flushText(events)
                state = State.GS
            }

            FS -> {
                state = State.FS
            }

            LF -> {
                if (ignoreLineFeedAfterBitImage) {
                    ignoreLineFeedAfterBitImage = false
                } else {
                    flushText(events)
                    events += ParserEvent.LineFeed
                    printPositionPx = null
                }
            }

            in 0x20..0x7E,
            in 0x80..0xFF
            -> textBytes.write(byte)
        }
        return true
    }

    private fun parseEsc(events: MutableList<ParserEvent>): Boolean {
        val command = poll() ?: return false
        state = when (command) {
            0x61 -> State.ESC_ALIGN
            0x45 -> State.ESC_BOLD
            0x2A -> State.ESC_BIT_IMAGE_MODE
            0x69 -> {
                flushText(events)
                events += ParserEvent.Cut
                State.NORMAL
            }

            0x20, 0x21, 0x2D, 0x33, 0x3D, 0x47, 0x4D, 0x52, 0x56, 0x57, 0x64, 0x65, 0x72, 0x74, 0x7B -> skipParameters(1)
            0x24 -> State.ESC_POSITION_LOW
            0x5C -> skipParameters(2)
            0x70 -> skipParameters(4)

            else -> State.NORMAL
        }
        return true
    }

    private fun skipParameters(count: Int): State {
        parametersToSkip = count
        return State.SKIP_PARAMETERS
    }

    private fun parseSkippedParameters(): Boolean {
        while (parametersToSkip > 0) {
            if (poll() == null) return false
            parametersToSkip -= 1
        }
        state = State.NORMAL
        return true
    }

    private fun parseFs(): Boolean {
        val command = poll() ?: return false
        state = when (command) {
            0x26, 0x2E -> State.NORMAL
            0x21, 0x43 -> skipParameters(1)
            0x53 -> skipParameters(2)
            else -> State.NORMAL
        }
        return true
    }

    private fun parseBitImageMode(): Boolean {
        val mode = poll() ?: return false
        bitImageMode = mode
        state = State.ESC_BIT_IMAGE_WIDTH_LOW
        return true
    }

    private fun parsePositionLow(): Boolean {
        val low = poll() ?: return false
        printPositionPx = low
        state = State.ESC_POSITION_HIGH
        return true
    }

    private fun parsePositionHigh(): Boolean {
        val high = poll() ?: return false
        printPositionPx = (printPositionPx ?: 0) or (high shl 8)
        state = State.NORMAL
        return true
    }

    private fun parseBitImageWidthLow(): Boolean {
        val low = poll() ?: return false
        bitImageWidth = low
        state = State.ESC_BIT_IMAGE_WIDTH_HIGH
        return true
    }

    private fun parseBitImageWidthHigh(): Boolean {
        val high = poll() ?: return false
        bitImageWidth = bitImageWidth or (high shl 8)
        val bytesPerColumn = when (bitImageMode) {
            0, 1 -> 1
            32, 33 -> 3
            else -> 0
        }
        val payloadSize = bitImageWidth.toLong() * bytesPerColumn
        if (
            bytesPerColumn == 0 ||
            bitImageWidth !in 1..MAX_BIT_IMAGE_WIDTH ||
            payloadSize > MAX_RASTER_PAYLOAD_BYTES
        ) {
            state = State.NORMAL
            bitImageWidth = 0
            return true
        }
        bitImagePayload = ByteArray(payloadSize.toInt())
        bitImagePayloadOffset = 0
        state = State.ESC_BIT_IMAGE_PAYLOAD
        return true
    }

    private fun parseBitImagePayload(events: MutableList<ParserEvent>): Boolean {
        while (bitImagePayloadOffset < bitImagePayload.size) {
            val byte = poll() ?: return false
            bitImagePayload[bitImagePayloadOffset++] = byte.toByte()
        }
        events += decodeBitImage(bitImageMode, bitImageWidth, bitImagePayload)
        bitImagePayload = ByteArray(0)
        bitImagePayloadOffset = 0
        bitImageWidth = 0
        ignoreLineFeedAfterBitImage = true
        state = State.NORMAL
        return true
    }

    private fun decodeBitImage(mode: Int, width: Int, payload: ByteArray): ParserEvent.RasterImage {
        val bytesPerColumn = if (mode == 0 || mode == 1) 1 else 3
        val height = bytesPerColumn * 8
        val pixels = BooleanArray(width * height)
        for (x in 0 until width) {
            for (byteIndex in 0 until bytesPerColumn) {
                val value = payload[x * bytesPerColumn + byteIndex].toInt() and 0xFF
                for (bit in 0 until 8) {
                    if ((value and (0x80 shr bit)) != 0) {
                        pixels[(byteIndex * 8 + bit) * width + x] = true
                    }
                }
            }
        }
        return ParserEvent.RasterImage(width, height, pixels, alignment)
    }

    private fun parseAlignment(): Boolean {
        val value = poll() ?: return false
        alignment = when (value) {
            0, 48 -> TextAlignment.LEFT
            1, 49 -> TextAlignment.CENTER
            2, 50 -> TextAlignment.RIGHT
            else -> alignment
        }
        state = State.NORMAL
        return true
    }

    private fun parseBold(): Boolean {
        val value = poll() ?: return false
        bold = value != 0
        state = State.NORMAL
        return true
    }

    private fun parseEscCut(events: MutableList<ParserEvent>): Boolean {
        flushText(events)
        events += ParserEvent.Cut
        state = State.NORMAL
        return true
    }

    private fun parseGs(): Boolean {
        val command = poll() ?: return false
        state = when (command) {
            0x56 -> State.GS_CUT
            0x76 -> State.GS_RASTER_SELECTOR
            0x28 -> State.GS_FUNCTION_NAME
            0x21, 0x33, 0x42, 0x45, 0x48, 0x66, 0x68, 0x77 -> skipParameters(1)
            0x4C, 0x57 -> skipParameters(2)
            else -> State.NORMAL
        }
        return true
    }

    private fun parseGsFunctionName(): Boolean {
        val name = poll() ?: return false
        state = if (name == 0x4C) State.GS_FUNCTION_LENGTH_LOW else State.NORMAL
        return true
    }

    private fun parseGsFunctionLengthLow(): Boolean {
        val low = poll() ?: return false
        gsFunctionLength = low
        state = State.GS_FUNCTION_LENGTH_HIGH
        return true
    }

    private fun parseGsFunctionLengthHigh(): Boolean {
        val high = poll() ?: return false
        gsFunctionLength = gsFunctionLength or (high shl 8)
        if (gsFunctionLength > MAX_GS_FUNCTION_LENGTH) {
            gsFunctionLength = 0
            state = State.NORMAL
            return true
        }
        gsFunctionPayload = ByteArray(gsFunctionLength)
        gsFunctionPayloadOffset = 0
        state = State.GS_FUNCTION_PAYLOAD
        return true
    }

    private fun parseGsFunctionPayload(events: MutableList<ParserEvent>): Boolean {
        while (gsFunctionPayloadOffset < gsFunctionPayload.size) {
            val byte = poll() ?: return false
            gsFunctionPayload[gsFunctionPayloadOffset++] = byte.toByte()
        }
        decodeGraphicsImage(gsFunctionPayload)?.let {
            events += it
        }
        gsFunctionLength = 0
        gsFunctionPayload = ByteArray(0)
        gsFunctionPayloadOffset = 0
        state = State.NORMAL
        return true
    }

    private fun decodeGraphicsImage(payload: ByteArray): ParserEvent.RasterImage? {
        if (payload.size < GRAPHICS_IMAGE_HEADER_SIZE) return null
        if (payload[0].toInt() != 48 || payload[1].toInt() != 112 || payload[2].toInt() != 48) return null
        val widthBytes = payload[6].toInt() and 0xFF or ((payload[7].toInt() and 0xFF) shl 8)
        val heightPixels = payload[8].toInt() and 0xFF or ((payload[9].toInt() and 0xFF) shl 8)
        val imageSize = widthBytes.toLong() * heightPixels.toLong()
        if (
            widthBytes !in 1..MAX_RASTER_WIDTH_BYTES ||
            heightPixels !in 1..MAX_RASTER_HEIGHT_PIXELS ||
            imageSize > MAX_RASTER_PAYLOAD_BYTES ||
            payload.size < GRAPHICS_IMAGE_HEADER_SIZE + imageSize
        ) return null
        return decodeRasterImage(
            mode = 0,
            payload = payload.copyOfRange(GRAPHICS_IMAGE_HEADER_SIZE, GRAPHICS_IMAGE_HEADER_SIZE + imageSize.toInt()),
            widthBytes = widthBytes,
            heightPixels = heightPixels,
        )
    }

    private fun parseRasterSelector(): Boolean {
        val selector = poll() ?: return false
        state = if (selector == 0x30) State.GS_RASTER_MODE else State.NORMAL
        return true
    }

    private fun parseGsCut(events: MutableList<ParserEvent>): Boolean {
        val mode = poll() ?: return false
        flushText(events)
        if (mode == 65 || mode == 66) {
            state = State.GS_CUT_FEED
        } else {
            events += ParserEvent.Cut
            state = State.NORMAL
        }
        return true
    }

    private fun parseGsCutFeed(events: MutableList<ParserEvent>): Boolean {
        if (poll() == null) return false
        events += ParserEvent.Cut
        state = State.NORMAL
        return true
    }

    private fun parseRasterMode(): Boolean {
        val mode = poll() ?: return false
        rasterMode = mode
        rasterHeader.reset()
        state = State.GS_RASTER_HEADER
        return true
    }

    private fun parseRasterHeader(): Boolean {
        while (rasterHeader.size() < RASTER_HEADER_SIZE) {
            val byte = poll() ?: return false
            rasterHeader.write(byte)
        }

        val header = rasterHeader.toByteArray()
        val widthBytes = header[0].toInt() and 0xFF or ((header[1].toInt() and 0xFF) shl 8)
        val heightPixels = header[2].toInt() and 0xFF or ((header[3].toInt() and 0xFF) shl 8)
        val payloadSize = widthBytes.toLong() * heightPixels.toLong()
        val scaledWidth = widthBytes.toLong() * 8L * if (rasterMode == 1 || rasterMode == 3) 2L else 1L
        val scaledHeight = heightPixels.toLong() * if (rasterMode == 2 || rasterMode == 3) 2L else 1L

        if (
            widthBytes !in 1..MAX_RASTER_WIDTH_BYTES ||
            heightPixels !in 1..MAX_RASTER_HEIGHT_PIXELS ||
            payloadSize > MAX_RASTER_PAYLOAD_BYTES ||
            scaledWidth > Int.MAX_VALUE ||
            scaledHeight > Int.MAX_VALUE ||
            scaledWidth * scaledHeight > MAX_RASTER_PIXELS
        ) {
            state = State.NORMAL
            rasterHeader.reset()
            return true
        }

        rasterPayload = ByteArray(payloadSize.toInt())
        rasterPayloadOffset = 0
        state = State.GS_RASTER_PAYLOAD
        return true
    }

    private fun parseRasterPayload(events: MutableList<ParserEvent>): Boolean {
        while (rasterPayloadOffset < rasterPayload.size) {
            val byte = poll() ?: return false
            rasterPayload[rasterPayloadOffset++] = byte.toByte()
        }

        flushText(events)
        events += decodeRasterImage(rasterMode, rasterPayload)
        rasterPayload = ByteArray(0)
        rasterPayloadOffset = 0
        state = State.NORMAL
        return true
    }

    private fun decodeRasterImage(mode: Int, payload: ByteArray): ParserEvent.RasterImage {
        val header = rasterHeader.toByteArray()
        val widthBytes = header[0].toInt() and 0xFF or ((header[1].toInt() and 0xFF) shl 8)
        val heightPixels = header[2].toInt() and 0xFF or ((header[3].toInt() and 0xFF) shl 8)
        return decodeRasterImage(mode, payload, widthBytes, heightPixels)
    }

    private fun decodeRasterImage(
        mode: Int,
        payload: ByteArray,
        widthBytes: Int,
        heightPixels: Int,
    ): ParserEvent.RasterImage {
        val baseWidth = widthBytes * 8
        val doubleWidth = mode == 1 || mode == 3
        val doubleHeight = mode == 2 || mode == 3
        val width = if (doubleWidth) baseWidth * 2 else baseWidth
        val height = if (doubleHeight) heightPixels * 2 else heightPixels
        val pixels = BooleanArray(width * height)

        for (sourceY in 0 until heightPixels) {
            for (sourceByte in 0 until widthBytes) {
                val value = payload[sourceY * widthBytes + sourceByte].toInt() and 0xFF
                for (bit in 0 until 8) {
                    if ((value and (0x80 shr bit)) == 0) continue
                    val sourceX = sourceByte * 8 + bit
                    val firstX = if (doubleWidth) sourceX * 2 else sourceX
                    val firstY = if (doubleHeight) sourceY * 2 else sourceY
                    pixels[firstY * width + firstX] = true
                    if (doubleWidth) pixels[firstY * width + firstX + 1] = true
                    if (doubleHeight) pixels[(firstY + 1) * width + firstX] = true
                    if (doubleWidth && doubleHeight) pixels[(firstY + 1) * width + firstX + 1] = true
                }
            }
        }

        return ParserEvent.RasterImage(width, height, pixels, alignment)
    }

    private fun flushText(events: MutableList<ParserEvent>) {
        if (textBytes.size() == 0) return
        val text = decodeText(textBytes.toByteArray())
        if (text.isNotEmpty()) events += ParserEvent.Text(text, alignment, bold, printPositionPx)
        textBytes.reset()
    }

    private fun decodeText(bytes: ByteArray): String {
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun poll(): Int? = if (input.isEmpty()) null else input.removeFirst()

    private companion object {
        const val ESC = 0x1B
        const val FS = 0x1C
        const val GS = 0x1D
        const val LF = 0x0A
        const val RASTER_HEADER_SIZE = 4
        const val MAX_RASTER_WIDTH_BYTES = 512
        const val MAX_RASTER_HEIGHT_PIXELS = 10_000
        const val MAX_RASTER_PAYLOAD_BYTES = 4L * 1024L * 1024L
        const val MAX_RASTER_PIXELS = 32L * 1024L * 1024L
        const val MAX_BIT_IMAGE_WIDTH = 4_096
        const val MAX_GS_FUNCTION_LENGTH = 4 * 1024 * 1024
        const val GRAPHICS_IMAGE_HEADER_SIZE = 10
    }
}
