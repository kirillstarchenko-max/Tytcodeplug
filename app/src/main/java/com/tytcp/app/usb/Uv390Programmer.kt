package com.tytcp.app.usb

import com.tytcp.app.codeplug.Codeplug

/**
 * Чтение и запись кодплага MD-UV380/MD-UV390 (0xD0000 байт).
 *
 * Память рации: кодплаг занимает адреса 0x000000–0x03FFFF и 0x110000–0x19FFFF.
 * В «плоском» образе (.bin) вторая область идёт сразу после первой.
 * Последовательности команд — как в editcp (dalefarnsworth-dmr/dfu).
 */
class Uv390Programmer(
    private val dfu: DfuDevice,
    private val progress: (stage: String, done: Int, total: Int) -> Unit,
) {

    fun readCodeplug(): ByteArray {
        progress("Вход в режим программирования", 0, 1)
        listOf(0x91 to 0x01, 0xA2 to 0x02, 0xA2 to 0x02, 0xA2 to 0x03, 0xA2 to 0x04, 0xA2 to 0x07)
            .forEach { (a, b) -> dfu.md380Custom(a, b) }
        dfu.setAddress(0)

        val out = ByteArray(Codeplug.BIN_SIZE)
        for (n in 0 until BLOCK_COUNT) {
            val data = dfu.upload(flashBlockNumber(n), BLOCK_SIZE)
            if (data.size != BLOCK_SIZE) {
                throw DfuException("Блок $n: получено ${data.size} байт вместо $BLOCK_SIZE")
            }
            data.copyInto(out, n * BLOCK_SIZE)
            if (n % 8 == 0 || n == BLOCK_COUNT - 1) progress("Чтение", n + 1, BLOCK_COUNT)
        }
        progress("Перезагрузка рации", 1, 1)
        dfu.reboot()
        return out
    }

    fun writeCodeplug(bin: ByteArray) {
        require(bin.size == Codeplug.BIN_SIZE) { "Неверный размер образа: ${bin.size}" }

        progress("Вход в режим программирования", 0, 1)
        dfu.md380Custom(0x91, 0x01)
        dfu.md380Custom(0x91, 0x01)
        dfu.md380Custom(0xA2, 0x02)
        Thread.sleep(2000)
        dfu.md380Custom(0xA2, 0x02)
        dfu.md380Custom(0xA2, 0x03)
        dfu.md380Custom(0xA2, 0x04)
        dfu.md380Custom(0xA2, 0x07)

        val eraseCount = Codeplug.BIN_SIZE / ERASE_SIZE
        for (i in 0 until eraseCount) {
            progress("Стирание", i, eraseCount)
            dfu.eraseBlock(eraseAddress(i * ERASE_SIZE))
        }

        dfu.setAddress(0)
        dfu.getStatus()

        for (n in 0 until BLOCK_COUNT) {
            val chunk = bin.copyOfRange(n * BLOCK_SIZE, (n + 1) * BLOCK_SIZE)
            dfu.dnload(flashBlockNumber(n), chunk)
            var guard = 0
            while (true) {
                val s = dfu.getStatus()
                when (s.state) {
                    DfuDevice.STATE_DNLOAD_IDLE -> break
                    DfuDevice.STATE_ERROR ->
                        throw DfuException("Ошибка записи блока $n (статус ${s.status})")
                    DfuDevice.STATE_DNBUSY -> Thread.sleep(s.pollTimeoutMs.coerceIn(1, 500).toLong())
                }
                if (++guard > 5000) throw DfuException("Таймаут записи блока $n")
            }
            if (n % 8 == 0 || n == BLOCK_COUNT - 1) progress("Запись", n + 1, BLOCK_COUNT)
        }
        progress("Перезагрузка рации", 1, 1)
        dfu.reboot()
    }

    companion object {
        const val BLOCK_SIZE = 1024
        const val ERASE_SIZE = 0x10000
        const val BLOCK_COUNT = Codeplug.BIN_SIZE / BLOCK_SIZE // 832

        /**
         * Номер DFU-блока для n-го килобайта образа. Блоки нумеруются с 2;
         * с 256-го КБ (адрес 0x40000) идёт скачок на 0x110000 (+832 блока).
         */
        fun flashBlockNumber(n: Int): Int = n + 2 + if (n in 256 until 2048 - 832) 832 else 0

        /** Адрес стирания: всё, что выше 0x40000 в образе, лежит с 0x110000. */
        fun eraseAddress(a: Int): Int = if (a in 0x40000 until 0x200000 - 0xD0000) a + 0xD0000 else a
    }
}
