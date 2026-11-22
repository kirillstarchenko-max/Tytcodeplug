package com.tytcp.app.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

class DfuException(message: String) : IOException(message)

/**
 * Низкоуровневый USB DFU для рации TYT (STM32, VID 0x0483 / PID 0xDF11) с
 * нестандартными командами TYT. Порт логики из md380tools (DFU.py) и
 * dalefarnsworth-dmr/dfu (dfu.go).
 *
 * Все методы блокирующие: вызывать только не из UI-потока.
 */
class DfuDevice private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
) : Closeable {

    data class Status(val status: Int, val pollTimeoutMs: Int, val state: Int)

    private val ifaceNumber = iface.id

    // --- базовые DFU-запросы -------------------------------------------------

    private fun out(request: Int, value: Int, data: ByteArray?) {
        val len = data?.size ?: 0
        val r = conn.controlTransfer(REQ_OUT, request, value, ifaceNumber, data, len, TIMEOUT_MS)
        if (r < 0 || r != len) {
            throw DfuException("USB OUT req=$request wValue=$value: ошибка передачи ($r)")
        }
    }

    private fun input(request: Int, value: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        val r = conn.controlTransfer(REQ_IN, request, value, ifaceNumber, buf, length, TIMEOUT_MS)
        if (r < 0) throw DfuException("USB IN req=$request wValue=$value: ошибка передачи ($r)")
        return if (r == length) buf else buf.copyOf(r)
    }

    fun getStatus(): Status {
        val b = input(GETSTATUS, 0, 6)
        if (b.size < 6) throw DfuException("GETSTATUS: короткий ответ (${b.size} байт)")
        val timeout = (b[1].u()) or (b[2].u() shl 8) or (b[3].u() shl 16)
        return Status(b[0].u(), timeout, b[4].u())
    }

    fun getState(): Int {
        val b = input(GETSTATE, 0, 1)
        if (b.isEmpty()) throw DfuException("GETSTATE: пустой ответ")
        return b[0].u()
    }

    fun clrStatus() = out(CLRSTATUS, 0, null)
    fun abort() = out(ABORT, 0, null)
    fun detach() = out(DETACH, 0, null)
    fun dnload(block: Int, data: ByteArray) = out(DNLOAD, block, data)
    fun upload(block: Int, length: Int): ByteArray = input(UPLOAD, block, length)

    // --- состояние -----------------------------------------------------------

    /** Довести конечный автомат DFU до dfuIDLE (как enter_dfu_mode в md380tools). */
    fun enterDfuMode() {
        repeat(300) {
            when (getState()) {
                STATE_DFU_IDLE -> return
                STATE_DNLOAD_SYNC, STATE_DNLOAD_IDLE, STATE_MANIFEST_SYNC,
                STATE_UPLOAD_IDLE, STATE_MANIFEST -> abort()
                STATE_ERROR -> clrStatus()
                STATE_APP_IDLE -> detach()
                else -> Thread.sleep(100) // appDETACH, dfuDNBUSY, MANIFEST_WAIT_RESET
            }
        }
        throw DfuException("Не удалось перевести рацию в состояние dfuIDLE")
    }

    /** Опрашивать GETSTATUS, пока рация занята (dfuDNBUSY). */
    fun pollWhileBusy(): Status {
        var s = getStatus()
        var guard = 0
        while (s.state == STATE_DNBUSY) {
            Thread.sleep(s.pollTimeoutMs.coerceIn(5, 1000).toLong())
            s = getStatus()
            if (++guard > 2000) throw DfuException("Рация слишком долго занята")
        }
        return s
    }

    /**
     * Служебная команда через DNLOAD в блок 0. Первый GETSTATUS запускает
     * выполнение, второй читает итоговое состояние; ждём dfuDNLOAD_IDLE.
     */
    private fun controlCommand(payload: ByteArray, delayMs: Long, what: String) {
        dnload(0, payload)
        getStatus() // этот вызов переключает состояние
        if (delayMs > 0) Thread.sleep(delayMs)
        val s = pollWhileBusy()
        if (s.state != STATE_DNLOAD_IDLE) {
            throw DfuException("$what: неожиданное состояние ${stateName(s.state)}, статус ${s.status}")
        }
        enterDfuMode()
    }

    /** Секретные команды TYT (0x91 xx, 0xA2 xx). */
    fun md380Custom(a: Int, b: Int) =
        controlCommand(byteArrayOf(a.toByte(), b.toByte()), 100, "Команда %02X %02X".format(a, b))

    fun setAddress(address: Int) =
        controlCommand(byteArrayOf(0x21) + le32(address), 0, "Установка адреса 0x%X".format(address))

    fun eraseBlock(address: Int) =
        controlCommand(byteArrayOf(0x41) + le32(address), 0, "Стирание 0x%X".format(address))

    /** Перезагрузка рации в обычный режим (0x91 0x05). Ошибки после команды игнорируются. */
    fun reboot() {
        enterDfuMode()
        dnload(0, byteArrayOf(0x91.toByte(), 0x05))
        try {
            getStatus()
        } catch (_: Exception) {
            // рация уходит в перезагрузку и может не ответить
        }
    }

    /** Начальная синхронизация после открытия устройства. */
    fun init() {
        val s = getStatus()
        if (s.state == STATE_ERROR) clrStatus()
        enterDfuMode()
    }

    override fun close() {
        try {
            conn.releaseInterface(iface)
        } finally {
            conn.close()
        }
    }

    companion object {
        const val VID = 0x0483
        const val PID = 0xDF11

        private const val REQ_OUT = 0x21 // host->device, class, interface
        private const val REQ_IN = 0xA1  // device->host, class, interface
        private const val TIMEOUT_MS = 6000

        const val DETACH = 0
        const val DNLOAD = 1
        const val UPLOAD = 2
        const val GETSTATUS = 3
        const val CLRSTATUS = 4
        const val GETSTATE = 5
        const val ABORT = 6

        const val STATE_APP_IDLE = 0
        const val STATE_APP_DETACH = 1
        const val STATE_DFU_IDLE = 2
        const val STATE_DNLOAD_SYNC = 3
        const val STATE_DNBUSY = 4
        const val STATE_DNLOAD_IDLE = 5
        const val STATE_MANIFEST_SYNC = 6
        const val STATE_MANIFEST = 7
        const val STATE_MANIFEST_WAIT_RESET = 8
        const val STATE_UPLOAD_IDLE = 9
        const val STATE_ERROR = 10

        fun stateName(s: Int) = when (s) {
            STATE_APP_IDLE -> "appIDLE"
            STATE_APP_DETACH -> "appDETACH"
            STATE_DFU_IDLE -> "dfuIDLE"
            STATE_DNLOAD_SYNC -> "dfuDNLOAD_SYNC"
            STATE_DNBUSY -> "dfuDNBUSY"
            STATE_DNLOAD_IDLE -> "dfuDNLOAD_IDLE"
            STATE_MANIFEST_SYNC -> "dfuMANIFEST_SYNC"
            STATE_MANIFEST -> "dfuMANIFEST"
            STATE_MANIFEST_WAIT_RESET -> "dfuMANIFEST_WAIT_RESET"
            STATE_UPLOAD_IDLE -> "dfuUPLOAD_IDLE"
            STATE_ERROR -> "dfuERROR"
            else -> "state$s"
        }

        fun isRadio(d: UsbDevice) = d.vendorId == VID && d.productId == PID

        fun find(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull(::isRadio)

        /** Открыть устройство и захватить DFU-интерфейс (класс 0xFE, подкласс 0x01). */
        fun open(um: UsbManager, device: UsbDevice): DfuDevice {
            val iface = (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { it.interfaceClass == 0xFE && it.interfaceSubclass == 0x01 }
                ?: device.getInterface(0)
            val conn = um.openDevice(device) ?: throw DfuException("Не удалось открыть USB-устройство")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw DfuException("Не удалось захватить USB-интерфейс")
            }
            return DfuDevice(conn, iface)
        }

        private fun Byte.u() = toInt() and 0xFF
        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }
}
