package com.voiceqa.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MiniMaxAsrProtocolTest {

    @Test
    fun `maps app locale to MiniMax language header`() {
        assertEquals("zh", MiniMaxAsrProtocol.languageHeader("zh-CN"))
        assertEquals("en", MiniMaxAsrProtocol.languageHeader("en_US"))
        assertEquals("yue", MiniMaxAsrProtocol.languageHeader("yue-HK"))
        assertNull(MiniMaxAsrProtocol.languageHeader("nl-NL"))
    }

    @Test
    fun `parses successful and error responses`() {
        assertEquals(
            "你好，世界。",
            MiniMaxAsrProtocol.parseText("""{"text":" 你好，世界。 ","duration":1.2}""")
        )
        assertEquals(
            "invalid key",
            MiniMaxAsrProtocol.parseError("""{"type":"error","error":{"message":"invalid key"}}""")
        )
    }

    @Test
    fun `wraps pcm bytes in a valid mono wav container`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = MiniMaxAsrProtocol.pcm16MonoToWav(pcm, 16_000)

        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(4, littleEndianInt(wav, 40))
        assertTrue(wav.copyOfRange(44, 48).contentEquals(pcm))
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
