package com.tytcp.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tytcp.app.codeplug.Codeplug
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val s = vm.state
    val progress by vm.progress.collectAsState()
    var confirmWrite by remember { mutableStateOf(false) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadTemplate)
    }
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(vm::saveExport) }
    val saveJournal = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::saveJournal)
    }

    // не гасить экран во время чтения/записи
    val view = LocalView.current
    DisposableEffect(s.busy) {
        view.keepScreenOn = s.busy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("MD-UV390 кодплаг") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- рация ---
            Section("Рация") {
                val text = when {
                    !s.radioPresent -> "Не подключена"
                    !s.radioPermission -> "Подключена, нет доступа"
                    else -> "Подключена: ${s.radioInfo}"
                }
                Text(text, color = if (s.radioPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.radioPresent && !s.radioPermission) {
                    Button(onClick = vm::requestPermission) { Text("Разрешить доступ") }
                }
            }

            // --- шаблон ---
            Section("Шаблон кодплага") {
                val t = s.template
                Text(
                    if (t == null) "Не загружен. Откройте общий .rdt из CPS или прочитайте рацию."
                    else "${s.templateName}\nID ${t.radioId}, «${t.radioName}», ${if (t.hasRdt) ".rdt" else ".bin"}"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = !s.busy) { Text("Открыть файл") }
                    OutlinedButton(
                        onClick = { vm.prepareExport(false)?.let { saveFile.launch(it) } },
                        enabled = !s.busy && t != null && vm.formError() == null,
                    ) { Text("Сохранить с полями") }
                }
            }

            // --- параметры ---
            Section("Параметры рации") {
                OutlinedTextField(
                    value = s.idText, onValueChange = vm::setIdText,
                    label = { Text("Radio ID (DMR ID)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = s.name, onValueChange = vm::setName,
                    label = { Text("Имя рации (до ${Codeplug.RADIO_NAME_MAX})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.intro1, onValueChange = vm::setIntro1,
                    label = { Text("Заставка, строка 1 (до ${Codeplug.INTRO_MAX})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.intro2, onValueChange = vm::setIntro2,
                    label = { Text("Заставка, строка 2 (до ${Codeplug.INTRO_MAX})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.autoIncrement, onCheckedChange = vm::setAutoIncrement)
                    Text("  ID +1 после успешной записи")
                }
            }

            val err = vm.formError()
            if (err != null) Text(err, color = MaterialTheme.colorScheme.error)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirmWrite = true },
                    enabled = !s.busy && s.radioPresent && err == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Записать в рацию") }
                OutlinedButton(
                    onClick = vm::readFromRadio,
                    enabled = !s.busy && s.radioPresent,
                    modifier = Modifier.weight(1f),
                ) { Text("Прочитать") }
            }

            progress?.let { p ->
                Text("${p.stage}: ${(p.fraction * 100).toInt()}%")
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            }

            // --- прочитанное ---
            s.lastRead?.let {
                Section("Прочитано из рации") {
                    Text(s.lastReadNote)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::useLastReadAsTemplate, enabled = !s.busy) { Text("Сделать шаблоном") }
                        OutlinedButton(
                            onClick = { vm.prepareExport(true)?.let { saveFile.launch(it) } },
                            enabled = !s.busy,
                        ) { Text("Сохранить файл") }
                    }
                }
            }

            // --- журнал ---
            Section("Журнал записи (${s.journal.size})") {
                s.journal.takeLast(15).reversed().forEach {
                    Text(
                        "${MainViewModel.timeFmt.format(Date(it.time))}   ${it.radioId}   ${it.name}",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saveJournal.launch("journal.csv") }, enabled = s.journal.isNotEmpty()) {
                        Text("Экспорт CSV")
                    }
                    TextButton(onClick = vm::clearJournal, enabled = s.journal.isNotEmpty()) { Text("Очистить") }
                }
            }

            // --- лог ---
            Section("Лог") {
                s.log.takeLast(20).reversed().forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }

    if (confirmWrite) {
        AlertDialog(
            onDismissRequest = { confirmWrite = false },
            title = { Text("Записать кодплаг?") },
            text = {
                Text(
                    "Radio ID: ${s.idText}\nИмя: ${s.name}\n\nНе отключайте кабель до окончания записи " +
                        "(около минуты)."
                )
            },
            confirmButton = {
                Button(onClick = { confirmWrite = false; vm.writeToRadio() }) { Text("Записать") }
            },
            dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
