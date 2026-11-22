package com.tytcp.app.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tytcp.app.codeplug.Codeplug
import com.tytcp.app.usb.DfuDevice
import com.tytcp.app.usb.Uv390Programmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JournalEntry(val time: Long, val radioId: Int, val name: String)

data class Progress(val stage: String, val fraction: Float)

data class UiState(
    val radioPresent: Boolean = false,
    val radioPermission: Boolean = false,
    val radioInfo: String = "",
    val template: Codeplug? = null,
    val templateName: String = "",
    val idText: String = "",
    val name: String = "",
    val intro1: String = "",
    val intro2: String = "",
    val autoIncrement: Boolean = true,
    val busy: Boolean = false,
    val lastRead: Codeplug? = null,
    val lastReadNote: String = "",
    val log: List<String> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(UiState())
        private set

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private val ctx get() = getApplication<Application>()
    private val usb get() = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val templateFile = File(app.filesDir, "template.dat")
    private val journalFile = File(app.filesDir, "journal.csv")

    /** Образ, последний раз записанный в рацию — для сверки при чтении. */
    private var lastWritten: Codeplug? = null
    private var pendingExport: Codeplug? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION &&
                !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            ) log("Доступ к USB не разрешён")
            refreshRadio()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        state = state.copy(
            autoIncrement = prefs.getBoolean("autoIncrement", true),
            idText = prefs.getString("idText", "") ?: "",
            journal = loadJournal(),
        )
        if (templateFile.exists()) {
            try {
                val cp = Codeplug.parse(templateFile.readBytes())
                state = state.copy(
                    template = cp,
                    templateName = prefs.getString("templateName", "шаблон") ?: "шаблон",
                    name = prefs.getString("name", cp.radioName) ?: cp.radioName,
                    intro1 = prefs.getString("intro1", cp.introLine1) ?: cp.introLine1,
                    intro2 = prefs.getString("intro2", cp.introLine2) ?: cp.introLine2,
                )
            } catch (e: Exception) {
                log("Не удалось загрузить сохранённый шаблон: ${e.message}")
            }
        }
        refreshRadio()
    }

    override fun onCleared() {
        ctx.unregisterReceiver(usbReceiver)
    }

    // --- рация ---------------------------------------------------------------

    fun refreshRadio() {
        val dev = DfuDevice.find(usb)
        val perm = dev != null && usb.hasPermission(dev)
        val info = when {
            dev == null -> ""
            perm -> listOfNotNull(dev.manufacturerName, dev.productName).joinToString(" / ")
            else -> "нет доступа"
        }
        state = state.copy(radioPresent = dev != null, radioPermission = perm, radioInfo = info)
    }

    fun requestPermission() {
        val dev = DfuDevice.find(usb) ?: return log("Рация не найдена")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(dev, pi)
    }

    // --- поля формы ------------------------------------------------------------

    fun setIdText(v: String) {
        state = state.copy(idText = v.filter(Char::isDigit).take(8)); persistForm()
    }
    fun setName(v: String) { state = state.copy(name = v.take(Codeplug.RADIO_NAME_MAX)); persistForm() }
    fun setIntro1(v: String) { state = state.copy(intro1 = v.take(Codeplug.INTRO_MAX)); persistForm() }
    fun setIntro2(v: String) { state = state.copy(intro2 = v.take(Codeplug.INTRO_MAX)); persistForm() }
    fun setAutoIncrement(v: Boolean) { state = state.copy(autoIncrement = v); persistForm() }

    private fun persistForm() {
        prefs.edit()
            .putString("idText", state.idText)
            .putString("name", state.name)
            .putString("intro1", state.intro1)
            .putString("intro2", state.intro2)
            .putBoolean("autoIncrement", state.autoIncrement)
            .apply()
    }

    /** Ошибка валидации формы или null. */
    fun formError(): String? {
        val id = state.idText.toIntOrNull()
        return when {
            state.template == null -> "Загрузите шаблон кодплага"
            id == null || id !in 1..Codeplug.MAX_RADIO_ID -> "Radio ID: от 1 до ${Codeplug.MAX_RADIO_ID}"
            else -> Codeplug.textError(state.name, Codeplug.RADIO_NAME_MAX)?.let { "Имя: $it" }
                ?: Codeplug.textError(state.intro1, Codeplug.INTRO_MAX)?.let { "Строка 1: $it" }
                ?: Codeplug.textError(state.intro2, Codeplug.INTRO_MAX)?.let { "Строка 2: $it" }
        }
    }

    // --- шаблон ----------------------------------------------------------------

    fun loadTemplate(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
                val cp = Codeplug.parse(bytes)
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
                setTemplate(cp, name, bytes)
            } catch (e: Exception) {
                log("Ошибка загрузки: ${e.message}")
            }
        }
    }

    fun useLastReadAsTemplate() {
        val cp = state.lastRead ?: return
        val withHeader = cp.withRdtFrom(state.template)
        val bytes = if (withHeader.hasRdt) withHeader.toRdt() else withHeader.bin
        viewModelScope.launch {
            setTemplate(Codeplug.parse(bytes), "прочитано из рации ${timeFmt.format(Date())}", bytes)
        }
    }

    private suspend fun setTemplate(cp: Codeplug, name: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) { templateFile.writeBytes(bytes) }
        prefs.edit().putString("templateName", name).apply()
        state = state.copy(
            template = cp, templateName = name,
            name = cp.radioName, intro1 = cp.introLine1, intro2 = cp.introLine2,
        )
        persistForm()
        log("Шаблон: $name (ID ${cp.radioId}, «${cp.radioName}», ${if (cp.hasRdt) ".rdt" else ".bin"})")
    }

    // --- экспорт ---------------------------------------------------------------

    /** Подготовить экспорт; возвращает предлагаемое имя файла. */
    fun prepareExport(fromLastRead: Boolean): String? {
        val cp = (if (fromLastRead) state.lastRead else buildImage())?.withRdtFrom(state.template) ?: return null
        pendingExport = cp
        val id = cp.radioId
        return if (cp.hasRdt) "uv390_$id.rdt" else "uv390_$id.bin"
    }

    fun saveExport(uri: Uri) {
        val cp = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            try {
                val bytes = if (cp.hasRdt) cp.toRdt() else cp.bin
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }
                log("Файл сохранён (${bytes.size} байт)")
            } catch (e: Exception) {
                log("Ошибка сохранения: ${e.message}")
            }
        }
    }

    fun saveJournal(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = "time;radio_id;name\n" + state.journal.joinToString("\n") {
                    "${timeFmt.format(Date(it.time))};${it.radioId};${it.name}"
                } + "\n"
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                }
                log("Журнал сохранён")
            } catch (e: Exception) {
                log("Ошибка сохранения журнала: ${e.message}")
            }
        }
    }

    // --- операции с рацией ---------------------------------------------------

    private fun buildImage(): Codeplug? {
        val t = state.template ?: return null
        return t.copy().apply {
            radioId = state.idText.toInt()
            radioName = state.name
            introLine1 = state.intro1
            introLine2 = state.intro2
        }
    }

    fun readFromRadio() = runOp("Чтение") { prog ->
        val cp = Codeplug(prog.readCodeplug())
        val note = buildString {
            append("ID ${cp.radioId}, «${cp.radioName}»")
            lastWritten?.let {
                val d = cp.diffCount(it)
                append(if (d == 0) " — совпадает с последней записью ✓" else " — отличается от последней записи в $d байт")
            }
        }
        withContext(Dispatchers.Main) {
            state = state.copy(lastRead = cp, lastReadNote = note)
            log("Прочитано: $note")
        }
    }

    fun writeToRadio() {
        formError()?.let { return log(it) }
        val image = buildImage() ?: return
        runOp("Запись") { prog ->
            prog.writeCodeplug(image.bin)
            withContext(Dispatchers.Main) {
                lastWritten = image
                addJournal(JournalEntry(System.currentTimeMillis(), image.radioId, image.radioName))
                log("Записано: ID ${image.radioId}, «${image.radioName}»")
                if (state.autoIncrement && image.radioId < Codeplug.MAX_RADIO_ID) {
                    setIdText((image.radioId + 1).toString())
                }
            }
        }
    }

    private fun runOp(title: String, block: suspend (Uv390Programmer) -> Unit) {
        if (state.busy) return
        val dev = DfuDevice.find(usb) ?: return log("Рация не найдена. Подключите кабель и включите рацию.")
        if (!usb.hasPermission(dev)) return requestPermission()

        state = state.copy(busy = true)
        _progress.value = Progress(title, 0f)
        log("$title…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DfuDevice.open(usb, dev).use { dfu ->
                        dfu.init()
                        val prog = Uv390Programmer(dfu) { stage, done, total ->
                            _progress.value = Progress(stage, if (total == 0) 0f else done.toFloat() / total)
                        }
                        block(prog)
                    }
                }
            } catch (e: Exception) {
                log("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                log("Проверьте: рация включена в обычном режиме (не в режиме загрузчика), кабель вставлен до щелчка.")
            } finally {
                state = state.copy(busy = false)
                _progress.value = null
                refreshRadio()
            }
        }
    }

    // --- журнал и лог --------------------------------------------------------

    private fun loadJournal(): List<JournalEntry> = try {
        if (!journalFile.exists()) emptyList()
        else journalFile.readLines().mapNotNull { line ->
            val p = line.split(';', limit = 3)
            if (p.size < 3) null
            else JournalEntry(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: 0, p[2])
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun addJournal(e: JournalEntry) {
        journalFile.appendText("${e.time};${e.radioId};${e.name.replace('\n', ' ')}\n")
        state = state.copy(journal = state.journal + e)
    }

    fun clearJournal() {
        journalFile.delete()
        state = state.copy(journal = emptyList())
    }

    private fun log(msg: String) {
        val line = "${clockFmt.format(Date())}  $msg"
        state = state.copy(log = (state.log + line).takeLast(100))
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.tytcp.app.USB_PERMISSION"
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}
