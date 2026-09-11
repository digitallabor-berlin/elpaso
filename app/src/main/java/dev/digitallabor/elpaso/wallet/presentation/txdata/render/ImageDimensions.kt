package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import kotlin.math.ceil

/**
 * Reads an image's pixel dimensions out of its own header, in pure Kotlin.
 *
 * PaSO View §3 makes the bound a **MUST**: "its decoded dimensions MUST NOT exceed 2048
 * pixels in either direction. A non-conforming image makes the `transaction_data` entry
 * not compatible." Enforcing that requires knowing the dimensions before the image is
 * drawn.
 *
 * **Why not `BitmapFactory.decodeByteArray` with `inJustDecodeBounds`.** That is the
 * obvious Android answer, and it cannot be tested here: the module sets
 * `testOptions.unitTests.isReturnDefaultValues = true`, so every `android.util.*` and
 * `android.graphics.*` call returns null/0/false on the JVM. A dimension gate built on it
 * would report "0 × 0, within bounds" for every input in every unit test — the cap would
 * appear enforced while actually passing everything, which is the worst of both outcomes.
 * AGENTS.md states the rule directly: push such logic into pure-Kotlin helpers and
 * unit-test those. Reading a PNG IHDR, a JPEG SOFn, or an SVG root tag is a header parse,
 * not image decoding, so nothing is lost by doing it here.
 *
 * **The format is sniffed from the bytes, never from `Content-Type`.** That header comes
 * from a host the *verifier* chose. Trusting it would let the party the gate constrains
 * pick which parser runs — declare `image/svg+xml` over PNG bytes and the SVG reader finds
 * no root tag, returns null, and the image slips into "undeterminable" handling of the
 * caller's choosing. Sniffing removes that lever entirely.
 *
 * Returns null when the dimensions cannot be established. That is deliberately *not* the
 * same as "within bounds" — see [ImageResolver], which refuses such content, because an
 * image whose size is unknown has not been shown to satisfy a MUST.
 */
object ImageDimensions {
    data class Size(
        val width: Int,
        val height: Int,
    )

    /** The longest prefix scanned for an SVG root element. */
    private const val SVG_SNIFF_BYTES = 8 * 1024

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** `name="value"` / `name='value'`, tolerating namespaced and hyphenated names. */
    private val ATTRIBUTE = Regex("""([A-Za-z_][\w:.-]*)\s*=\s*["']([^"']*)["']""")

    fun read(bytes: ByteArray): Size? = readPng(bytes) ?: readJpeg(bytes) ?: readSvg(bytes)

    // --- PNG (ISO/IEC 15948 §11.2.2 IHDR) ---

    /**
     * The 8-byte signature is followed immediately by IHDR, whose first two fields are the
     * width and height as big-endian unsigned 32-bit integers — so they sit at fixed
     * offsets 16 and 20. The spec requires IHDR to be the first chunk, so no scan is
     * needed.
     */
    private fun readPng(bytes: ByteArray): Size? {
        if (bytes.size < 24) return null
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return null
        }
        if (bytes[12] != 'I'.code.toByte() ||
            bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() ||
            bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = be32(bytes, 16)
        val height = be32(bytes, 20)
        // A dimension past Int range, or zero, is not a renderable image. Returning null
        // rather than a nonsense Size keeps "undeterminable" a single concept.
        if (width <= 0 || height <= 0) return null
        return Size(width, height)
    }

    // --- JPEG (ITU-T T.81 §B.2.2 frame header) ---

    /**
     * Walks the marker segments to the first Start-Of-Frame, whose payload is
     * `precision(1) height(2) width(2)`.
     *
     * Two details are easy to get wrong and both are exercised by tests. Progressive JPEGs
     * carry **SOF2** (`0xC2`), not SOF0, so matching only `0xC0` returns null for a
     * perfectly ordinary photograph. And `0xC4` (DHT), `0xC8` (JPG) and `0xCC` (DAC) sit
     * inside the `0xC0..0xCF` range without being frame headers — reading one as a frame
     * yields dimensions taken from Huffman table bytes.
     */
    private fun readJpeg(bytes: ByteArray): Size? {
        if (bytes.size < 4) return null
        if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null

        var i = 2
        while (i + 3 < bytes.size) {
            if (u8(bytes, i) != 0xFF) {
                i++
                continue
            }
            var marker = u8(bytes, i + 1)
            // 0xFF is also the fill byte; any run of them precedes the real marker.
            while (marker == 0xFF && i + 2 < bytes.size) {
                i++
                marker = u8(bytes, i + 1)
            }
            i += 2

            // Standalone markers carry no length field.
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (marker == 0xD9) return null

            if (i + 1 >= bytes.size) return null
            val length = (u8(bytes, i) shl 8) or u8(bytes, i + 1)
            if (length < 2) return null

            if (isFrameHeader(marker)) {
                if (i + 6 >= bytes.size) return null
                val height = (u8(bytes, i + 3) shl 8) or u8(bytes, i + 4)
                val width = (u8(bytes, i + 5) shl 8) or u8(bytes, i + 6)
                if (width <= 0 || height <= 0) return null
                return Size(width, height)
            }
            i += length
        }
        return null
    }

    private fun isFrameHeader(marker: Int): Boolean = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    // --- SVG ---

    /**
     * Reads `width`/`height` off the root `<svg>` element, falling back to the `viewBox`
     * when they are absent or expressed in percent — a proportional size says nothing
     * about pixels, so it cannot answer the question §3 asks.
     */
    private fun readSvg(bytes: ByteArray): Size? {
        val head = String(bytes, 0, minOf(bytes.size, SVG_SNIFF_BYTES), Charsets.UTF_8)
        val open = head.indexOf("<svg", ignoreCase = true).takeIf { it >= 0 } ?: return null
        val close = head.indexOf('>', open).takeIf { it >= 0 } ?: return null
        val attributes =
            ATTRIBUTE
                .findAll(head.substring(open, close))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

        val width = attributes["width"]?.let(::parseLength)
        val height = attributes["height"]?.let(::parseLength)
        if (width != null && height != null) return Size(width, height)

        val viewBox =
            attributes["viewbox"]
                ?.trim()
                ?.split(Regex("[\\s,]+"))
                ?.mapNotNull { it.toDoubleOrNull() }
                ?: return null
        if (viewBox.size != 4) return null
        val vbWidth = ceil(viewBox[2]).toInt()
        val vbHeight = ceil(viewBox[3]).toInt()
        if (vbWidth <= 0 || vbHeight <= 0) return null
        return Size(vbWidth, vbHeight)
    }

    /**
     * `"100"`, `"100px"`, `"100.5pt"` → pixels; `"50%"` → null.
     *
     * Rounds **up**: the value feeds a maximum, and rounding a 2048.4 px image down to
     * 2048 would admit content the cap excludes.
     */
    private fun parseLength(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.endsWith("%")) return null
        val numeric = trimmed.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
        val value = numeric.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        return ceil(value).toInt()
    }

    // --- byte helpers ---

    private fun u8(
        bytes: ByteArray,
        index: Int,
    ): Int = bytes[index].toInt() and 0xFF

    /** Big-endian unsigned 32-bit. Values past [Int.MAX_VALUE] come back negative and are refused by the caller. */
    private fun be32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (u8(bytes, offset) shl 24) or
            (u8(bytes, offset + 1) shl 16) or
            (u8(bytes, offset + 2) shl 8) or
            u8(bytes, offset + 3)
}
