package com.tytcp.app.codeplug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeplugEditorTest {

    private fun emptyEditor(): CodeplugEditor {
        val ed = CodeplugEditor(Codeplug(ByteArray(Codeplug.BIN_SIZE)))
        // пустой кодплаг: помечаем все записи списков удалёнными
        for (rec in ed.records) {
            val d = rec.del ?: continue
            for (s in 0 until rec.max) {
                val bin = ed.cp.bin
                val rdt = rec.rdtOffset + s * rec.size + d.offset
                val o = if (rdt < Codeplug.TRAILER_OFFSET) rdt - 549 else rdt - 565
                for (i in 0 until d.size) bin[o + i] = d.value.toByte()
            }
        }
        return ed
    }

    @Test
    fun channelFieldsRoundTrip() {
        val ed = emptyEditor()
        val ch = ed.record("Channels")
        val s = ed.addRecord(ch)!!
        fun ref(t: String) = FieldRef(ch, s, ch.field(t))
        assertNull(ed.setString(ref("CiName"), "Пост 1"))
        assertNull(ed.setString(ref("CiRxFrequency"), "439.12500"))
        assertNull(ed.setString(ref("CiTxFrequencyOffset"), "431.52500"))
        assertNull(ed.setString(ref("CiChannelMode"), "Digital"))
        assertNull(ed.setString(ref("CiColorCode"), "7"))
        assertNull(ed.setString(ref("CiCtcssEncode"), "D023N"))
        assertNull(ed.setString(ref("CiTot"), "180"))
        assertEquals("Пост 1", ed.recordName(ch, s))
        assertEquals("439.12500", ed.display(ref("CiRxFrequency")))
        assertEquals("431.52500", ed.display(ref("CiTxFrequencyOffset")))
        assertEquals("Digital", ed.display(ref("CiChannelMode")))
        assertEquals("7", ed.display(ref("CiColorCode")))
        assertEquals("D023N", ed.display(ref("CiCtcssEncode")))
        assertEquals("180", ed.display(ref("CiTot")))
        // BCD little-endian: 439.12500 -> 43912500 -> 00 25 91 43
        val o = 0x40000 + s * 64 + 16
        assertEquals(0x00, ed.cp.bin[o].toInt() and 0xFF)
        assertEquals(0x25, ed.cp.bin[o + 1].toInt() and 0xFF)
        assertEquals(0x91, ed.cp.bin[o + 2].toInt() and 0xFF)
        assertEquals(0x43, ed.cp.bin[o + 3].toInt() and 0xFF)
    }

    @Test
    fun deleteChannelRemapsZonesAndScanLists() {
        val ed = emptyEditor()
        val ch = ed.record("Channels")
        val names = listOf("A", "B", "C", "D")
        for (n in names) {
            val s = ed.addRecord(ch)!!
            ed.setString(FieldRef(ch, s, ch.field("CiName")), n)
        }
        val zone = ed.record("Zones")
        val z = ed.addRecord(zone)!!
        val zA = FieldRef(zone, z, zone.field("ZiChannelA_uv380"))
        // 20 каналов в зоне, чтобы задеть расширенную область (после 16-го)
        val members = List(20) { (it % 4 + 1).toLong() }
        ed.setMembers(zA, members)
        assertEquals(members, ed.members(zA))

        ed.deleteRecord(ch, 1) // удалить "B"
        assertEquals(listOf("A", "C", "D"), ed.slots(ch).map { ed.recordName(ch, it) })
        val expected = members.filter { it != 2L }.map { if (it > 2) it - 1 else it }
        assertEquals(expected, ed.members(zA))
        assertEquals(listOf("A", "C", "D"), ed.members(zA).take(3).map { ed.refLabelOf(zA.f, it) })
    }

    @Test
    fun duplicateGetsUniqueName() {
        val ed = emptyEditor()
        val ct = ed.record("Contacts")
        val a = ed.addRecord(ct)!!
        ed.setString(FieldRef(ct, a, ct.field("DcName")), "Штаб")
        ed.setString(FieldRef(ct, a, ct.field("DcCallID")), "9")
        val b = ed.addRecord(ct, copyFrom = a)!!
        assertEquals("Штаб 2", ed.recordName(ct, b))
        assertEquals("9", ed.display(FieldRef(ct, b, ct.field("DcCallID"))))
        assertEquals(2, ed.count(ct))
    }

    @Test
    fun introScreenBit() {
        val ed = emptyEditor()
        val gs = ed.record("GeneralSettings")
        val r = FieldRef(gs, 0, gs.field("GsIntroScreen"))
        assertNull(ed.setString(r, "Picture"))
        // bitOffset 531 -> байт 0x2040+0x42, маска 0x10
        assertTrue((ed.cp.bin[0x2082].toInt() and 0x10) != 0)
        assertNull(ed.setString(r, "Character String"))
        assertEquals(0, ed.cp.bin[0x2082].toInt() and 0x10)
    }
}
