package com.tytcp.app.codeplug

import com.tytcp.app.usb.Uv390Programmer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeplugTest {

    private fun fakeRdt(): ByteArray {
        val rdt = ByteArray(Codeplug.RDT_SIZE) { (it * 31 + 7).toByte() }
        "DfuSe".forEachIndexed { i, c -> rdt[i] = c.code.toByte() }
        val t = Codeplug.TRAILER_OFFSET
        rdt[t + 8] = 'U'.code.toByte(); rdt[t + 9] = 'F'.code.toByte(); rdt[t + 10] = 'D'.code.toByte()
        val crc = Codeplug.dfuCrc(rdt, 0, t + 12)
        for (i in 0 until 4) rdt[t + 12 + i] = (crc shr (8 * i)).toByte()
        return rdt
    }

    @Test
    fun rdtRoundTrip() {
        val rdt = fakeRdt()
        val cp = Codeplug.parse(rdt)
        assertArrayEquals(rdt, cp.toRdt())
    }

    @Test
    fun rdtMapping() {
        val rdt = fakeRdt()
        val cp = Codeplug.parse(rdt)
        assertEquals(rdt[549], cp.bin[0])
        assertEquals(rdt[549 + 0x2084], cp.bin[0x2084])
        assertEquals(rdt[262709], cp.bin[0x40000])
        assertEquals(rdt[Codeplug.RDT_SIZE - 1], cp.bin[Codeplug.BIN_SIZE - 1])
    }

    @Test
    fun editFieldsAndCrc() {
        val cp = Codeplug.parse(fakeRdt())
        cp.radioId = 2501234
        cp.radioName = "POST-12"
        cp.introLine1 = "Hello"
        assertEquals(2501234, cp.radioId)
        assertEquals("POST-12", cp.radioName)
        assertEquals("Hello", cp.introLine1)
        // rdt offset 8873 (0x22A9) = Radio ID, little-endian
        val rdt = cp.toRdt()
        assertEquals(0x72, rdt[8873].toInt() and 0xFF)  // 2501234 = 0x262A72
        assertEquals(0x2A, rdt[8874].toInt() and 0xFF)
        assertEquals(0x26, rdt[8875].toInt() and 0xFF)
        assertEquals('P'.code, rdt[8917].toInt())
        // CRC суффикса пересчитан
        val t = Codeplug.TRAILER_OFFSET
        val crc = Codeplug.dfuCrc(rdt, 0, t + 12)
        var stored = 0L
        for (i in 0 until 4) stored = stored or ((rdt[t + 12 + i].toLong() and 0xFF) shl (8 * i))
        assertEquals(crc, stored)
        // повторный разбор даёт те же значения
        val again = Codeplug.parse(rdt)
        assertEquals(2501234, again.radioId)
        assertEquals("POST-12", again.radioName)
    }

    @Test
    fun cyrillicName() {
        val cp = Codeplug(ByteArray(Codeplug.BIN_SIZE))
        cp.radioName = "Пост 5"
        assertEquals("Пост 5", cp.radioName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nameTooLong() {
        Codeplug(ByteArray(Codeplug.BIN_SIZE)).radioName = "12345678901234567"
    }

    @Test
    fun blockMapping() {
        assertEquals(2, Uv390Programmer.flashBlockNumber(0))
        assertEquals(257, Uv390Programmer.flashBlockNumber(255))
        // 256-й КБ образа -> адрес 0x110000 -> блок 0x110000/1024 + 2
        assertEquals(0x110000 / 1024 + 2, Uv390Programmer.flashBlockNumber(256))
        assertEquals(0x19FC00 / 1024 + 2, Uv390Programmer.flashBlockNumber(831))
        assertEquals(0x30000, Uv390Programmer.eraseAddress(0x30000))
        assertEquals(0x110000, Uv390Programmer.eraseAddress(0x40000))
        assertEquals(0x190000, Uv390Programmer.eraseAddress(0xC0000))
    }
}
