package com.tytcp.app.codeplug.def

/** Диапазон значений (span/spanList в editcp): хранится i, показывается i*scale. */
data class Span(val min: Int, val max: Int, val scale: Int, val interval: Int, val minString: String?)

/** Особое значение поля: число в памяти -> подпись. */
data class IdxStr(val index: Int, val s: String)

/** Признак удалённой записи: [size] байт по смещению [offset] равны [value]. */
data class DelDesc(val offset: Int, val size: Int, val value: Int)

data class FieldDef(
    val type: String,
    val name: String,
    val bitOffset: Int,
    val bitSize: Int,
    val valueType: String,
    val default: String?,
    val max: Int,
    val strings: List<String>?,
    val indexed: List<IdxStr>?,
    val span: Span?,
    val listType: String?,
    val extRdtOffset: Int,
    val extBitOffset: Int,
    val extSize: Int,
    val extIndex: Int,
) {
    val byteSize: Int get() = (bitSize + 7) / 8
}

data class RecordDef(
    val type: String,
    val name: String,
    val rdtOffset: Int,
    val size: Int,
    val max: Int,
    val del: DelDesc?,
    val names: List<String>?,
    val namePrefix: String?,
    val fields: List<FieldDef>,
) {
    val nameField: FieldDef? = fields.firstOrNull { it.valueType == "name" || it.valueType == "contactName" }
    /** Можно ли добавлять/удалять записи (у списков с признаком удаления). */
    val editableList: Boolean get() = max > 1 && del != null
    fun field(type: String) = fields.first { it.type == type }
}
