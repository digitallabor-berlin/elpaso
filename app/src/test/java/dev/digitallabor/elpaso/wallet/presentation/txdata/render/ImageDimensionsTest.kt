package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PaSO View §3: "its decoded dimensions **MUST NOT** exceed 2048 pixels in either
 * direction. A non-conforming image makes the `transaction_data` entry not compatible."
 *
 * The format is sniffed from the bytes, never from the `Content-Type` the host returned —
 * that header is chosen by a server the *verifier* named, so trusting it would let the
 * dimension gate be steered by the party the gate exists to constrain.
 */
class ImageDimensionsTest {
    // --- helpers that build the smallest real header of each format ---

    private fun be32(value: Int) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                write(be32(13))
                write("IHDR".toByteArray())
                write(be32(width))
                write(be32(height))
                write(byteArrayOf(8, 6, 0, 0, 0))
            }.toByteArray()

    /** SOI, an APP0 segment to be skipped, then SOF0 carrying the real size. */
    private fun jpeg(
        width: Int,
        height: Int,
        marker: Byte = 0xC0.toByte(),
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                write(byteArrayOf(0xFF.toByte(), 0xE0.toByte()))
                write(be16(6))
                write("JFIF".toByteArray())
                write(byteArrayOf(0xFF.toByte(), marker))
                write(be16(11))
                write(byteArrayOf(8))
                write(be16(height))
                write(be16(width))
                write(byteArrayOf(3, 1))
            }.toByteArray()

    @Test
    fun readsPngSize() {
        assertEquals(ImageDimensions.Size(120, 40), ImageDimensions.read(png(120, 40)))
    }

    @Test
    fun readsJpegSizeSkippingEarlierSegments() {
        assertEquals(ImageDimensions.Size(640, 480), ImageDimensions.read(jpeg(640, 480)))
    }

    /**
     * Progressive JPEGs carry SOF2 instead of SOF0. Reading only SOF0 would return null
     * for a perfectly ordinary image and refuse it as undeterminable.
     */
    @Test
    fun readsProgressiveJpegSize() {
        assertEquals(ImageDimensions.Size(300, 200), ImageDimensions.read(jpeg(300, 200, 0xC2.toByte())))
    }

    /** DHT/DQT markers share the SOFn numeric neighbourhood and must not be read as a frame header. */
    @Test
    fun skipsNonFrameJpegMarkers() {
        val bytes =
            ByteArrayOutputStream()
                .apply {
                    write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                    // DHT (0xC4) — numerically inside C0..CF but not a frame header.
                    write(byteArrayOf(0xFF.toByte(), 0xC4.toByte()))
                    write(be16(4))
                    write(byteArrayOf(0, 0))
                    write(byteArrayOf(0xFF.toByte(), 0xC0.toByte()))
                    write(be16(11))
                    write(byteArrayOf(8))
                    write(be16(77))
                    write(be16(99))
                    write(byteArrayOf(3, 1))
                }.toByteArray()
        assertEquals(ImageDimensions.Size(99, 77), ImageDimensions.read(bytes))
    }

    @Test
    fun readsSvgWidthAndHeight() {
        val svg = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"></svg>"""
        assertEquals(ImageDimensions.Size(800, 600), ImageDimensions.read(svg.toByteArray()))
    }

    /** A viewBox is the size when width/height are absent or given in percent. */
    @Test
    fun fallsBackToSvgViewBox() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 768"></svg>"""
        assertEquals(ImageDimensions.Size(1024, 768), ImageDimensions.read(svg.toByteArray()))
    }

    @Test
    fun readsSvgSizeWithUnitSuffix() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100px" height="50px"></svg>"""
        assertEquals(ImageDimensions.Size(100, 50), ImageDimensions.read(svg.toByteArray()))
    }

    @Test
    fun unknownFormatIsUndeterminable() {
        assertNull(ImageDimensions.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun truncatedPngIsUndeterminable() {
        assertNull(ImageDimensions.read(png(10, 10).copyOf(12)))
    }

    @Test
    fun emptyInputIsUndeterminable() {
        assertNull(ImageDimensions.read(ByteArray(0)))
    }
}
