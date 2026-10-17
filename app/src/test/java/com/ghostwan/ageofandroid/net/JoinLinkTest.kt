package com.ghostwan.ageofandroid.net

import com.ghostwan.ageofandroid.ui.encodeQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinLinkTest {
    @Test
    fun roundTrip() {
        val link = JoinLink(listOf("192.168.1.20", "10.0.0.4"), 47821, "Érwan & co")
        val text = link.toString()
        assertEquals("ageofandroid://join?h=192.168.1.20,10.0.0.4&p=47821&n=%C3%89rwan+%26+co", text)
        assertEquals(link, JoinLink.parse(text))
    }

    @Test
    fun bareAddresses() {
        assertEquals(JoinLink(listOf("192.168.1.20")), JoinLink.parse(" 192.168.1.20 "))
        assertEquals(JoinLink(listOf("192.168.1.20"), 5000), JoinLink.parse("192.168.1.20:5000"))
        assertNull(JoinLink.parse(""))
        assertNull(JoinLink.parse("https://example.com"))
        assertNull(JoinLink.parse("ageofandroid://join?p=1"))
    }

    @Test
    fun qrCodeDecodesBackToTheLink() {
        val text = JoinLink(listOf("192.168.1.20"), DEFAULT_PORT, "Erwan").toString()
        val m = encodeQr(text)
        // Render with a quiet zone, 4 pixels per module, as a scanner would see it.
        val scale = 4
        val border = 4
        val w = (m.width + border * 2) * scale
        val pixels = IntArray(w * w) { 0xFFFFFFFF.toInt() }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + border) * scale + dy) * w + (x + border) * scale + dx] = 0xFF000000.toInt()
            }
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels)))).text
        assertEquals(text, decoded)
        assertEquals("Erwan", JoinLink.parse(decoded)!!.name)
    }
}
