package com.tytcp.app.codeplug

import com.tytcp.app.codeplug.def.FieldDef
import com.tytcp.app.codeplug.def.RecordDef
import com.tytcp.app.codeplug.def.Uv380Defs
import java.util.Locale
import kotlin.math.roundToLong

/** Ссылка на конкретное значение: запись [rec] в слоте [slot], поле [f], элемент [idx]. */
data class FieldRef(val rec: RecordDef, val slot: Int, val f: FieldDef, val idx: Int = 0)

data class Option(val value: Long, val label: String)

enum class Kind { BOOL, CHOICE, INT, FREQ, TEXT, REF, HEX, PASSWORD, READONLY }

/**
 * Универсальный редактор кодплага MD-UV380/UV390 по описаниям из [Uv380Defs].
 * Кодирование значений повторяет editcp (dalefarnsworth-dmr/codeplug, field.go/misc.go).
 * Работает прямо с байтами [Codeplug.bin].
 */
class CodeplugEditor(val cp: Codeplug) {
    private val bin get() = cp.bin

    val records: List<RecordDef> get() = Uv380Defs.records
    fun record(type: String) = Uv380Defs.record(type)

    // --- адресация --------------------------------------------------------

    private fun binOffset(rdt: Int): Int =
        if (rdt < Codeplug.TRAILER_OFFSET) rdt - Codeplug.HEADER_SIZE
        else rdt - Codeplug.HEADER_SIZE - Codeplug.TRAILER_SIZE

    private fun fieldRdtOffset(r: FieldRef): Int {
        val f = r.f
        return if (f.extSize == 0 || r.idx < f.extIndex) {
            r.rec.rdtOffset + r.slot * r.rec.size + (f.bitOffset + r.idx * f.bitSize) / 8
        } else {
            f.extRdtOffset + r.slot * f.extSize + f.extBitOffset / 8 + (r.idx - f.extIndex) * f.byteSize
        }
    }

    /**
     * Где лежит поле: массив и смещение. Часть «Basic Information» (модель, диапазоны)
     * хранится только в заголовке .rdt, в памяти рации её нет — тогда null.
     */
    private fun loc(r: FieldRef): Pair<ByteArray, Int>? {
        val rdt = fieldRdtOffset(r)
        return if (rdt < Codeplug.HEADER_SIZE) cp.rdtHeader?.let { it to rdt } else bin to binOffset(rdt)
    }

    /** Доступно ли поле (false — поле из заголовка .rdt, а заголовка нет). */
    fun available(r: FieldRef) = loc(r) != null

    private fun bytes(r: FieldRef): ByteArray {
        val (a, o) = loc(r) ?: return ByteArray(r.f.byteSize)
        return a.copyOfRange(o, o + r.f.byteSize)
    }

    private fun storeBytes(r: FieldRef, b: ByteArray) {
        require(b.size == r.f.byteSize)
        val (a, o) = loc(r) ?: return
        b.copyInto(a, o)
    }

    /** Сырое целое значение поля (биты MSB-first внутри байта, многобайтовые — little-endian). */
    fun raw(r: FieldRef): Long {
        val f = r.f
        val (a, o) = loc(r) ?: return 0
        if (f.bitSize < 8) {
            var v = a[o].toInt() and 0xFF
            val right = (f.bitOffset + f.bitSize) % 8
            if (right != 0) v = v shr (8 - right)
            return (v and ((1 shl f.bitSize) - 1)).toLong()
        }
        var v = 0L
        for (i in 0 until minOf(f.byteSize, 8)) v = v or ((a[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun setRaw(r: FieldRef, value: Long) {
        val f = r.f
        val (a, o) = loc(r) ?: return
        if (f.bitSize < 8) {
            var mask = (1 shl f.bitSize) - 1
            var v = value.toInt() and mask
            val right = (f.bitOffset + f.bitSize) % 8
            if (right != 0) {
                mask = mask shl (8 - right); v = v shl (8 - right)
            }
            a[o] = ((a[o].toInt() and mask.inv()) or v).toByte()
            return
        }
        for (i in 0 until f.byteSize) a[o + i] = (value shr (8 * i)).toByte()
    }

    // --- записи -----------------------------------------------------------

    fun exists(rec: RecordDef, slot: Int): Boolean {
        if (rec.max == 1) return true
        val d = rec.del
        if (d != null) {
            val o = binOffset(rec.rdtOffset + slot * rec.size + d.offset)
            if ((0 until d.size).all { (bin[o + it].toInt() and 0xFF) == d.value }) return false
        }
        val nf = rec.nameField ?: return true
        return text(FieldRef(rec, slot, nf)).isNotEmpty()
    }

    fun slots(rec: RecordDef): List<Int> = (0 until rec.max).filter { exists(rec, it) }

    fun count(rec: RecordDef): Int = slots(rec).size

    fun recordName(rec: RecordDef, slot: Int): String {
        rec.nameField?.let { return text(FieldRef(rec, slot, it)) }
        rec.names?.getOrNull(slot)?.let { return it }
        rec.namePrefix?.let { return it + (slot + 1) }
        return rec.name
    }

    /** Краткое описание записи для списка. */
    fun summary(rec: RecordDef, slot: Int): String = when (rec.type) {
        "Channels" -> {
            val mode = display(FieldRef(rec, slot, rec.field("CiChannelMode")))
            val rx = display(FieldRef(rec, slot, rec.field("CiRxFrequency")))
            val tx = display(FieldRef(rec, slot, rec.field("CiTxFrequencyOffset")))
            if (rx == tx) "$mode  $rx" else "$mode  RX $rx / TX $tx"
        }
        "Contacts" -> {
            val t = display(FieldRef(rec, slot, rec.field("DcCallType")))
            "$t  ${display(FieldRef(rec, slot, rec.field("DcCallID")))}"
        }
        "Zones" -> {
            val n = members(FieldRef(rec, slot, rec.field("ZiChannelA_uv380"))).size
            "каналов: $n"
        }
        "GroupLists" -> "контактов: ${members(FieldRef(rec, slot, rec.field("GlContact"))).size}"
        "ScanLists" -> "каналов: ${members(FieldRef(rec, slot, rec.field("SlChannel_md40"))).size}"
        else -> ""
    }

    // --- типы значений ----------------------------------------------------

    fun kind(f: FieldDef): Kind = when (f.valueType) {
        "offOn", "onOff" -> Kind.BOOL
        "iStrings", "bandwidth", "privacyNumber", "indexedStrings", "radioButton", "callType",
        "span", "spanList", "gpsReportInterval", "ctcssDcs" -> Kind.CHOICE
        "callID" -> Kind.INT
        "frequency", "frequencyOffset" -> Kind.FREQ
        "name", "contactName", "introLine", "radioName", "textMessage", "pcPassword" -> Kind.TEXT
        "listIndex", "memberListIndex", "contactListIndex", "gpsListIndex",
        "nkContactListIndex", "derefListIndex" -> Kind.REF
        "hexadecimal32", "hexadecimal4" -> Kind.HEX
        "radioPassword", "radioProgPassword" -> Kind.PASSWORD
        else -> Kind.READONLY
    }

    fun label(f: FieldDef): String =
        if (f.valueType == "frequencyOffset") "Tx Frequency (MHz)" else f.name

    fun maxChars(f: FieldDef): Int = when (f.valueType) {
        "textMessage" -> f.byteSize / 2 - 1
        "pcPassword" -> f.byteSize
        "hexadecimal32", "hexadecimal4" -> f.byteSize * 2
        "radioPassword", "radioProgPassword" -> 8
        else -> f.byteSize / 2
    }

    fun bool(r: FieldRef): Boolean = raw(r).let { if (r.f.valueType == "onOff") it == 0L else it != 0L }

    fun setBool(r: FieldRef, on: Boolean) {
        val one = if (r.f.valueType == "onOff") !on else on
        setRaw(r, if (one) 1 else 0)
    }

    /** Варианты для CHOICE и REF. */
    fun options(r: FieldRef): List<Option> {
        val base = baseOptions(r)
        if (kind(r.f) != Kind.CHOICE || !available(r)) return base
        // значение, которого нет в описании (например, новая прошивка) — сохраняем как есть
        val cur = raw(r).let { if (r.f.valueType == "radioButton" && it == 255L) 0L else it }
        return if (base.any { it.value == cur }) base
        else base + Option(cur, if (r.f.type == "CiPrivacy" && cur == 3L) "AES? (код 3)" else "код $cur (как в рации)")
    }

    private fun baseOptions(r: FieldRef): List<Option> {
        val f = r.f
        return when (f.valueType) {
            "iStrings", "bandwidth", "privacyNumber" ->
                f.strings!!.mapIndexed { i, s -> Option(i.toLong(), s) }
            "indexedStrings", "radioButton", "callType" ->
                f.indexed!!.map { Option(it.index.toLong(), it.s) }
            "span", "spanList", "gpsReportInterval" -> {
                val sp = f.span!!
                (sp.min..sp.max)
                    .filter { (it == sp.min && sp.minString != null) || it % sp.interval == 0 }
                    .map { Option(it.toLong(), spanLabel(f, it)) }
            }
            "ctcssDcs" -> Tones.options
            else -> if (kind(f) == Kind.REF) refOptions(f) else emptyList()
        }
    }

    private fun spanLabel(f: FieldDef, v: Int): String {
        val sp = f.span!!
        return if (sp.minString != null && v == sp.min) sp.minString else (v * sp.scale).toString()
    }

    private fun refOptions(f: FieldDef): List<Option> {
        val specials = f.indexed.orEmpty().map { Option(it.index.toLong(), it.s) }
        val list = record(f.listType!!)
        return specials + slots(list).map { Option((it + 1).toLong(), recordName(list, it)) }
    }

    /** Текстовое представление любого значения. */
    fun display(r: FieldRef): String {
        val f = r.f
        if (!available(r)) return "— (нет в памяти рации)"
        return when (kind(f)) {
            Kind.BOOL -> if (bool(r)) "On" else "Off"
            Kind.CHOICE -> {
                val v = raw(r)
                val v2 = if (f.valueType == "radioButton" && v == 255L) 0L else v
                if (f.valueType == "ctcssDcs") Tones.label(v2)
                else options(r).firstOrNull { it.value == v2 }?.label ?: "? ($v)"
            }
            Kind.REF -> refLabel(f, raw(r))
            Kind.INT -> raw(r).toString()
            Kind.FREQ -> freqString(bcdToLong(raw(r)))
            Kind.TEXT -> text(r)
            Kind.HEX -> bytes(r).reversedArray().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            Kind.PASSWORD -> password(r)
            Kind.READONLY -> readonly(r)
        }
    }

    private fun refLabel(f: FieldDef, v: Long): String {
        f.indexed?.firstOrNull { it.index.toLong() == v }?.let { return it.s }
        if (v == 0L) return "—"
        val list = record(f.listType!!)
        val slot = (v - 1).toInt()
        return if (slot < list.max && exists(list, slot)) recordName(list, slot) else "? (#$v)"
    }

    fun text(r: FieldRef): String {
        val b = bytes(r)
        if (r.f.valueType == "pcPassword") {
            return if (b.all { it == 0xFF.toByte() }) "" else String(b, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        }
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < b.size) {
            val c = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            if (c == 0 || c == 0xFFFF) break
            sb.append(c.toChar()); i += 2
        }
        return sb.toString()
    }

    private fun password(r: FieldRef): String {
        val v = raw(r)
        if (v == 0xFFFFFFFFL) return if (r.f.valueType == "radioPassword") "00000000" else ""
        return "%08d".format(bcdToLong(reverse4(v)))
    }

    private fun readonly(r: FieldRef): String {
        val b = bytes(r)
        return when (r.f.valueType) {
            "ascii" -> String(b.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
            "biFrequency" -> "%.1f".format(Locale.ROOT, bcdToLong(raw(r)) / 10.0)
            "timeStamp" -> b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            "cpsVersion" -> b.joinToString("") { (if ((it.toInt() and 0xFF) > 9) 0 else it.toInt()).toString() }
            else -> b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        }
    }

    // --- запись значений ----------------------------------------------------

    fun setChoice(r: FieldRef, value: Long) = setRaw(r, value)

    /** Установить значение из строки. Возвращает текст ошибки или null. */
    fun setString(r: FieldRef, s: String): String? {
        val f = r.f
        return when (kind(f)) {
            Kind.BOOL -> when (s) {
                "On" -> { setBool(r, true); null }
                "Off" -> { setBool(r, false); null }
                else -> "On или Off"
            }
            Kind.CHOICE, Kind.REF -> {
                val o = options(r).firstOrNull { it.label == s } ?: return "нет варианта «$s»"
                setRaw(r, o.value); null
            }
            Kind.INT -> {
                val v = s.trim().toLongOrNull() ?: return "нужно число"
                if (v !in 0..16777215) return "от 0 до 16777215"
                setRaw(r, v); null
            }
            Kind.FREQ -> {
                val mhz = s.trim().replace(',', '.').toDoubleOrNull() ?: return "нужна частота в МГц"
                if (mhz != 0.0 && !frequencyValid(mhz)) return "частота вне диапазона рации (${bandsText()})"
                setRaw(r, longToBcd((mhz * 100000).roundToLong())); null
            }
            Kind.TEXT -> setText(r, s)
            Kind.HEX -> {
                val n = f.byteSize * 2
                if (s.length != n || !s.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return "нужно $n hex-символов"
                val b = ByteArray(f.byteSize) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                storeBytes(r, b.reversedArray()); null
            }
            Kind.PASSWORD -> {
                if (s.isEmpty() && f.valueType == "radioProgPassword") { setRaw(r, 0xFFFFFFFFL); return null }
                if (s.length != 8 || !s.all { it in '0'..'9' }) return "8 цифр"
                val v = s.toLong()
                if (v == 0L && raw(r) == 0L) return null // «нет пароля» уже записан нулями — не трогаем
                setRaw(r,if (v == 0L) 0xFFFFFFFFL else reverse4(longToBcd(v))); null
            }
            Kind.READONLY -> "только чтение"
        }
    }

    private fun setText(r: FieldRef, s: String): String? {
        val f = r.f
        val max = maxChars(f)
        if (s.length > max) return "не более $max символов"
        if (f.valueType == "pcPassword") {
            if (s.isEmpty()) { storeBytes(r, ByteArray(8) { 0xFF.toByte() }); return null }
            if (s.length != 8 || !s.all { it.code in 0x21..0x7E }) return "8 латинских символов или пусто"
            storeBytes(r, s.lowercase(Locale.ROOT).toByteArray(Charsets.ISO_8859_1)); return null
        }
        if (s.any { it.isSurrogate() || it.code == 0 }) return "недопустимые символы"
        if (s.isEmpty() && (f.valueType == "name" || f.valueType == "contactName")) return "имя не может быть пустым"
        val b = ByteArray(f.byteSize)
        s.forEachIndexed { i, c -> b[2 * i] = c.code.toByte(); b[2 * i + 1] = (c.code shr 8).toByte() }
        storeBytes(r, b); return null
    }

    // --- списки-члены (каналы зоны, контакты группы, каналы сканирования) ----

    fun isMemberList(f: FieldDef) = f.max > 1 && kind(f) == Kind.REF

    fun members(r: FieldRef): List<Long> =
        (0 until r.f.max).map { raw(r.copy(idx = it)) }.filter { it != 0L }

    fun setMembers(r: FieldRef, values: List<Long>) {
        require(values.size <= r.f.max) { "не более ${r.f.max}" }
        for (i in 0 until r.f.max) setRaw(r.copy(idx = i), values.getOrElse(i) { 0L })
    }

    fun refLabelOf(f: FieldDef, v: Long) = refLabel(f, v)

    // --- добавление и удаление записей ------------------------------------

    private fun extBlocks(rec: RecordDef) =
        rec.fields.filter { it.extSize > 0 }.map { it.extRdtOffset to it.extSize }.distinct()

    private fun copySlot(rec: RecordDef, from: Int, to: Int) {
        val a = binOffset(rec.rdtOffset + from * rec.size)
        val b = binOffset(rec.rdtOffset + to * rec.size)
        bin.copyInto(bin, b, a, a + rec.size)
        for ((off, size) in extBlocks(rec)) {
            val ea = binOffset(off + from * size)
            val eb = binOffset(off + to * size)
            bin.copyInto(bin, eb, ea, ea + size)
        }
    }

    private fun markDeleted(rec: RecordDef, slot: Int) {
        val d = rec.del ?: return
        val o = binOffset(rec.rdtOffset + slot * rec.size + d.offset)
        for (i in 0 until d.size) bin[o + i] = d.value.toByte()
    }

    /** Добавить запись (копию [copyFrom] или со значениями по умолчанию). Возвращает слот или null. */
    fun addRecord(rec: RecordDef, copyFrom: Int? = null): Int? {
        if (!rec.editableList) return null
        val slot = (slots(rec).maxOrNull() ?: -1) + 1
        if (slot >= rec.max) return null
        if (copyFrom != null) {
            copySlot(rec, copyFrom, slot)
        } else {
            val o = binOffset(rec.rdtOffset + slot * rec.size)
            bin.fill(0xFF.toByte(), o, o + rec.size)
            for ((off, size) in extBlocks(rec)) {
                val e = binOffset(off + slot * size); bin.fill(0, e, e + size)
            }
            for (f in rec.fields) {
                if (isMemberList(f)) { setMembers(FieldRef(rec, slot, f), emptyList()); continue }
                val def = f.default ?: if (kind(f) == Kind.REF) f.indexed?.firstOrNull()?.s else null
                for (i in 0 until f.max) {
                    if (def != null) setString(FieldRef(rec, slot, f, i), def)
                    else if (kind(f) == Kind.REF) setRaw(FieldRef(rec, slot, f, i), 0)
                }
            }
        }
        rec.nameField?.let { nf ->
            val base = if (copyFrom != null) recordName(rec, copyFrom) else (nf.default ?: rec.name)
            setString(FieldRef(rec, slot, nf), uniqueName(rec, base, maxChars(nf)))
        }
        return slot
    }

    private fun uniqueName(rec: RecordDef, base: String, max: Int): String {
        val names = slots(rec).map { recordName(rec, it) }.toSet()
        if (base !in names) return base.take(max)
        val stem = base.trimEnd { it.isDigit() }.trimEnd()
        for (n in 2..rec.max + 1) {
            val suffix = " $n"
            val cand = stem.take(max - suffix.length) + suffix
            if (cand !in names) return cand
        }
        return base.take(max)
    }

    /** Удалить запись со сдвигом следующих и исправлением всех ссылок на этот список. */
    fun deleteRecord(rec: RecordDef, slot: Int) {
        if (!rec.editableList) return
        val last = slots(rec).maxOrNull() ?: return
        for (s in slot until last) copySlot(rec, s + 1, s)
        markDeleted(rec, last)
        remapRefs(rec.type, (slot + 1).toLong())
    }

    private fun remapRefs(listType: String, removed: Long) {
        for (rr in records) for (ff in rr.fields) {
            if (ff.listType != listType || kind(ff) != Kind.REF) continue
            val special = ff.indexed.orEmpty().map { it.index.toLong() }.toSet()
            val slotsToFix = if (rr.max == 1) listOf(0) else slots(rr)
            for (s in slotsToFix) {
                val ref = FieldRef(rr, s, ff)
                if (isMemberList(ff)) {
                    val vals = members(ref).filter { it != removed }
                        .map { if (it !in special && it > removed) it - 1 else it }
                    setMembers(ref, vals)
                } else for (i in 0 until ff.max) {
                    val r = ref.copy(idx = i)
                    val v = raw(r)
                    if (v in special || v == 0L) continue
                    when {
                        v == removed -> setRaw(r, ff.indexed?.firstOrNull { it.s == "None" }?.index?.toLong() ?: 0L)
                        v > removed -> setRaw(r, v - 1)
                    }
                }
            }
        }
    }

    // --- частоты ----------------------------------------------------------

    private fun band(low: String, high: String): ClosedFloatingPointRange<Double>? {
        val bi = record("BasicInformation")
        val lo = bcdToLong(raw(FieldRef(bi, 0, bi.field(low)))) / 10.0
        val hi = bcdToLong(raw(FieldRef(bi, 0, bi.field(high)))) / 10.0
        return if (lo > 0 && hi > lo) lo..hi else null
    }

    private fun bands() = listOfNotNull(
        band("BiLowFrequencyA", "BiHighFrequencyA"),
        band("BiLowFrequencyB", "BiHighFrequencyB"),
    )

    fun frequencyValid(mhz: Double): Boolean = bands().let { b -> b.isEmpty() || b.any { mhz in it } }

    private fun bandsText() = bands().joinToString(", ") { "%.0f–%.0f".format(Locale.ROOT, it.start, it.endInclusive) }

    companion object {
        fun freqString(hz10: Long): String = "%.5f".format(Locale.ROOT, hz10 / 100000.0)

        fun bcdToLong(bcd: Long): Long {
            var v = bcd; var out = 0L; var mult = 1L
            repeat(8) {
                val d = v and 0xF
                if (d > 9) return -1
                out += d * mult; v = v shr 4; mult *= 10
            }
            return out
        }

        fun longToBcd(n: Long): Long {
            var v = n; var out = 0L
            for (i in 0 until 8) { out = out or ((v % 10) shl (4 * i)); v /= 10 }
            return out
        }

        fun reverse4(v: Long): Long =
            ((v and 0xFF) shl 24) or ((v and 0xFF00) shl 8) or ((v shr 8) and 0xFF00) or ((v shr 24) and 0xFF)
    }
}

/** CTCSS/DCS: кодирование как в editcp (BCD, тип в старших 2 битах). */
object Tones {
    private val ctcss = intArrayOf(
        670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974,
        1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462,
        1514, 1567, 1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862,
        1899, 1928, 1966, 1995, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418,
        2503, 2541,
    )
    private val dcs = intArrayOf(
        23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72,
        73, 74, 114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155,
        156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246,
        251, 252, 255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325, 331,
        332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432,
        445, 446, 452, 454, 455, 462, 464, 465, 466, 503, 506, 516, 523, 526,
        532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712,
        723, 731, 732, 734, 743, 754,
    )

    val options: List<Option> = buildList {
        add(Option(0xFFFF, "None"))
        ctcss.forEach { add(Option(CodeplugEditor.longToBcd(it.toLong()), "%d.%d".format(it / 10, it % 10))) }
        dcs.forEach { add(Option(CodeplugEditor.longToBcd(it.toLong()) or 0x8000, "D%03dN".format(it))) }
        dcs.forEach { add(Option(CodeplugEditor.longToBcd(it.toLong()) or 0xC000, "D%03dI".format(it))) }
    }

    fun label(v: Long): String {
        if (v == 0L || v == 0xFFFFL) return "None"
        return options.firstOrNull { it.value == v }?.label ?: "? (%04X)".format(v)
    }
}
