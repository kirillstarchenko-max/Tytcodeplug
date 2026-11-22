package com.tytcp.app.codeplug

import java.util.zip.CRC32

class CodeplugFormatException(message: String) : Exception(message)

/**
 * Кодплаг MD-UV380 / MD-UV390.
 *
 * [bin] — «плоский» образ 0xD0000 байт (как его читает/пишет рация).
 * Если кодплаг загружен из .rdt (файл CPS), хранятся также заголовок (549 байт)
 * и DFU-суффикс (16 байт), чтобы можно было сохранить обратно в .rdt.
 *
 * Раскладка .rdt (852533 байт):
 *   [0, 549)            заголовок DfuSe
 *   [549, 262693)       bin[0x00000, 0x40000)
 *   [262693, 262709)    DFU-суффикс (CRC32 по всему, что перед ним)
 *   [262709, 852533)    bin[0x40000, 0xD0000)
 *
 * Смещения полей взяты из dalefarnsworth-dmr/codeplug (GeneralSettings, rdt 8805).
 */
class Codeplug(
    val bin: ByteArray,
    val rdtHeader: ByteArray? = null,
    val rdtTrailer: ByteArray? = null,
) {
    init {
        require(bin.size == BIN_SIZE) { "bin size ${bin.size}" }
        require(rdtHeader == null || rdtHeader.size == HEADER_SIZE)
        require(rdtTrailer == null || rdtTrailer.size == TRAILER_SIZE)
        require((rdtHeader == null) == (rdtTrailer == null))
    }

    val hasRdt: Boolean get() = rdtHeader != null

    fun copy() = Codeplug(bin.copyOf(), rdtHeader?.copyOf(), rdtTrailer?.copyOf())

    /** Если своего заголовка .rdt нет — взять его у [other] (например, у шаблона). */
    fun withRdtFrom(other: Codeplug?): Codeplug =
        if (hasRdt || other?.rdtHeader == null) this
        else Codeplug(bin, other.rdtHeader.copyOf(), other.rdtTrailer!!.copyOf())

    var radioId: Int
        get() = bin.u(OFF_RADIO_ID) or (bin.u(OFF_RADIO_ID + 1) shl 8) or (bin.u(OFF_RADIO_ID + 2) shl 16)
        set(v) {
            require(v in 1..MAX_RADIO_ID) { "Radio ID должен быть от 1 до $MAX_RADIO_ID" }
            bin[OFF_RADIO_ID] = v.toByte()
            bin[OFF_RADIO_ID + 1] = (v shr 8).toByte()
            bin[OFF_RADIO_ID + 2] = (v shr 16).toByte()
        }

    var radioName: String
        get() = readUcs2(OFF_RADIO_NAME, RADIO_NAME_BYTES)
        set(v) = writeUcs2(OFF_RADIO_NAME, RADIO_NAME_BYTES, v)

    var introLine1: String
        get() = readUcs2(OFF_INTRO_1, INTRO_BYTES)
        set(v) = writeUcs2(OFF_INTRO_1, INTRO_BYTES, v)

    var introLine2: String
        get() = readUcs2(OFF_INTRO_2, INTRO_BYTES)
        set(v) = writeUcs2(OFF_INTRO_2, INTRO_BYTES, v)

    /** Количество отличающихся байт с другим кодплагом. */
    fun diffCount(other: Codeplug): Int {
        var n = 0
        for (i in bin.indices) if (bin[i] != other.bin[i]) n++
        return n
    }

    fun toRdt(): ByteArray {
        val header = rdtHeader ?: throw IllegalStateException("Нет заголовка .rdt — загрузите шаблон .rdt")
        val out = ByteArray(RDT_SIZE)
        header.copyInto(out, 0)
        bin.copyInto(out, HEADER_SIZE, 0, SPLIT)
        rdtTrailer!!.copyInto(out, TRAILER_OFFSET)
        bin.copyInto(out, TRAILER_OFFSET + TRAILER_SIZE, SPLIT, BIN_SIZE)
        if (hasDfuSuffixSignature(out)) {
            val crc = dfuCrc(out, 0, TRAILER_OFFSET + 12)
            for (i in 0 until 4) out[TRAILER_OFFSET + 12 + i] = (crc shr (8 * i)).toByte()
        }
        return out
    }

    private fun readUcs2(off: Int, len: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < len) {
            val c = bin.u(off + i) or (bin.u(off + i + 1) shl 8)
            if (c == 0 || c == 0xFFFF) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun writeUcs2(off: Int, len: Int, s: String) {
        validateText(s, len / 2)
        for (i in 0 until len) bin[off + i] = 0
        s.forEachIndexed { idx, ch ->
            bin[off + idx * 2] = ch.code.toByte()
            bin[off + idx * 2 + 1] = (ch.code shr 8).toByte()
        }
    }

    companion object {
        const val BIN_SIZE = 0xD0000
        const val RDT_SIZE = 852533
        const val HEADER_SIZE = 549
        const val TRAILER_OFFSET = 262693
        const val TRAILER_SIZE = 16
        const val SPLIT = 0x40000
        const val MD380_RDT_SIZE = 262709

        // смещения в bin (rdt = bin + 549)
        const val OFF_INTRO_1 = 0x2040
        const val OFF_INTRO_2 = 0x2054
        const val OFF_RADIO_ID = 0x2084
        const val OFF_RADIO_NAME = 0x20B0
        const val INTRO_BYTES = 20      // 10 символов
        const val RADIO_NAME_BYTES = 32 // 16 символов

        const val MAX_RADIO_ID = 16776415

        const val RADIO_NAME_MAX = RADIO_NAME_BYTES / 2
        const val INTRO_MAX = INTRO_BYTES / 2

        fun parse(bytes: ByteArray): Codeplug = when (bytes.size) {
            RDT_SIZE -> {
                if (!bytes.startsWith("DfuSe")) throw CodeplugFormatException("Файл .rdt не начинается с «DfuSe»")
                val bin = ByteArray(BIN_SIZE)
                bytes.copyInto(bin, 0, HEADER_SIZE, HEADER_SIZE + SPLIT)
                bytes.copyInto(bin, SPLIT, TRAILER_OFFSET + TRAILER_SIZE, RDT_SIZE)
                Codeplug(
                    bin,
                    bytes.copyOfRange(0, HEADER_SIZE),
                    bytes.copyOfRange(TRAILER_OFFSET, TRAILER_OFFSET + TRAILER_SIZE),
                )
            }
            BIN_SIZE -> Codeplug(bytes.copyOf())
            MD380_RDT_SIZE -> throw CodeplugFormatException("Это кодплаг MD-380 (одним диапазоном) — не поддерживается")
            else -> throw CodeplugFormatException("Неизвестный размер файла: ${bytes.size} байт")
        }

        /** Проверка строки для поля UCS-2; возвращает null, если всё в порядке. */
        fun textError(s: String, maxChars: Int): String? = when {
            s.length > maxChars -> "не более $maxChars символов"
            s.any { it.isSurrogate() || it.code == 0 } -> "недопустимые символы"
            else -> null
        }

        private fun validateText(s: String, maxChars: Int) {
            textError(s, maxChars)?.let { throw IllegalArgumentException(it) }
        }

        /** CRC DFU-суффикса: CRC32 без финального XOR. */
        fun dfuCrc(data: ByteArray, from: Int, to: Int): Long {
            val c = CRC32()
            c.update(data, from, to - from)
            return c.value xor 0xFFFFFFFFL
        }

        private fun hasDfuSuffixSignature(rdt: ByteArray): Boolean =
            rdt[TRAILER_OFFSET + 8] == 'U'.code.toByte() &&
                rdt[TRAILER_OFFSET + 9] == 'F'.code.toByte() &&
                rdt[TRAILER_OFFSET + 10] == 'D'.code.toByte()

        private fun ByteArray.startsWith(s: String) =
            size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

        private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    }
}
