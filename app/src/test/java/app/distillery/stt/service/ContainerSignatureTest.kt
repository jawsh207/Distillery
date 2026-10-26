// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerSignatureTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun recognizesCommonRecordingFormats() {
        assertEquals("amr", ContainerSignature.detect(ascii("#!AMR\nxxxxxx"), allowFrameSync = true))
        assertEquals("amr", ContainerSignature.detect(ascii("#!AMR-WB\nxxx"), allowFrameSync = true))
        assertEquals("mp4", ContainerSignature.detect(ascii("\u0000\u0000\u0000\u0018ftyp3gp4"), allowFrameSync = true))
        assertEquals("mp4", ContainerSignature.detect(ascii("\u0000\u0000\u0000 ftypM4A "), allowFrameSync = true))
        assertEquals("wav", ContainerSignature.detect(ascii("RIFF$\u0000\u0000\u0000WAVE"), allowFrameSync = true))
        assertEquals("ogg", ContainerSignature.detect(ascii("OggS\u0000\u0002xxxxxx"), allowFrameSync = true))
        assertEquals("flac", ContainerSignature.detect(ascii("fLaC\u0000\u0000"), allowFrameSync = true))
        assertEquals("mp3", ContainerSignature.detect(ascii("ID3\u0004\u0000"), allowFrameSync = true))
        assertEquals("webm", ContainerSignature.detect(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0), allowFrameSync = true))
    }

    @Test
    fun frameSyncOnlyWhenCallerDidNotSayPcm() {
        val adts = bytes(0xFF, 0xF1, 0x50, 0x80)
        assertEquals("mpeg", ContainerSignature.detect(adts, allowFrameSync = true))
        assertEquals(null, ContainerSignature.detect(adts, allowFrameSync = false))
    }

    @Test
    fun rawPcmIsNotAContainer() {
        assertEquals(null, ContainerSignature.detect(bytes(0x00, 0x00, 0x12, 0x01, 0xF0, 0xFF), allowFrameSync = true))
        assertEquals(null, ContainerSignature.detect(ByteArray(0), allowFrameSync = true))
    }
}
