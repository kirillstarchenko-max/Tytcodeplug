package com.tytcp.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tytcp.app.codeplug.CodeplugEditor
import com.tytcp.app.codeplug.FieldRef
import com.tytcp.app.codeplug.Kind
import com.tytcp.app.codeplug.Option
import com.tytcp.app.codeplug.def.RecordDef

/** Экраны приложения (простая навигация стеком). */
sealed interface Screen {
    data object Main : Screen
    data object EditorHome : Screen
    data class RecordList(val type: String) : Screen
    data class RecordEdit(val type: String, val slot: Int) : Screen
}

/** Разделы редактора: тип записи -> русское название. */
private val sections = listOf(
    "GeneralSettings" to "Общие настройки",
    "Channels" to "Каналы",
    "Zones" to "Зоны",
    "Contacts" to "Контакты",
    "GroupLists" to "Группы приёма (RX Group Lists)",
    "ScanLists" to "Списки сканирования",
    "PrivacySettings" to "Шифрование (ключи)",
    "TextMessages" to "Текстовые сообщения",
    "RadioButtons" to "Боковые кнопки",
    "ButtonDefinitions" to "Длительность долгого нажатия",
    "OneTouch" to "Быстрые вызовы (One Touch)",
    "NumberKey" to "Цифровые клавиши",
    "MenuItems" to "Пункты меню",
    "GPSSystems" to "GPS-системы",
    "BasicInformation" to "Сведения о рации",
)

fun sectionTitle(type: String) = sections.firstOrNull { it.first == type }?.second ?: type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = fab,
    ) { pad -> content(Modifier.padding(pad)) }
}

@Composable
fun EditorHomeScreen(ed: CodeplugEditor, version: Int, onBack: () -> Unit, open: (Screen) -> Unit) {
    EditorScaffold("Редактор кодплага", onBack) { mod ->
        LazyColumn(mod.fillMaxSize()) {
            items(sections) { (type, title) ->
                val rec = ed.record(type)
                val sub = remember(version, type) {
                    when {
                        rec.max == 1 -> ""
                        rec.editableList -> "${ed.count(rec)} из ${rec.max}"
                        else -> "${rec.max}"
                    }
                }
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = sub.takeIf { it.isNotEmpty() }?.let { t -> @Composable { Text(t) } },
                    modifier = Modifier.clickable {
                        open(if (rec.max == 1) Screen.RecordEdit(type, 0) else Screen.RecordList(type))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun RecordListScreen(
    ed: CodeplugEditor, version: Int, type: String,
    onBack: () -> Unit, open: (Screen) -> Unit, changed: () -> Unit,
) {
    val rec = ed.record(type)
    val slots = remember(version, type) { ed.slots(rec) }
    var message by remember { mutableStateOf<String?>(null) }
    EditorScaffold(
        title = sectionTitle(type) + if (rec.editableList) " (${slots.size})" else "",
        onBack = onBack,
        fab = {
            if (rec.editableList) FloatingActionButton(onClick = {
                val s = ed.addRecord(rec, copyFrom = slots.lastOrNull())
                if (s == null) message = "Достигнут предел: ${rec.max}"
                else { changed(); open(Screen.RecordEdit(type, s)) }
            }) { Icon(Icons.Filled.Add, "Добавить") }
        },
    ) { mod ->
        Column(mod.fillMaxSize()) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (slots.isEmpty()) Text("Пусто. Нажмите «+», чтобы добавить.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(slots, key = { it }) { s ->
                    val name = remember(version, s) { ed.recordName(rec, s) }
                    val sum = remember(version, s) { ed.summary(rec, s) }
                    ListItem(
                        leadingContent = { Text("${s + 1}") },
                        headlineContent = { Text(name) },
                        supportingContent = sum.takeIf { it.isNotEmpty() }?.let { t -> @Composable { Text(t) } },
                        modifier = Modifier.clickable { open(Screen.RecordEdit(type, s)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun RecordEditScreen(
    ed: CodeplugEditor, version: Int, type: String, slot: Int,
    onBack: () -> Unit, replace: (Screen) -> Unit, changed: () -> Unit,
) {
    val rec = ed.record(type)
    var confirmDelete by remember { mutableStateOf(false) }
    val title = remember(version, slot) {
        if (rec.max == 1) sectionTitle(type) else ed.recordName(rec, slot)
    }
    EditorScaffold(
        title = title,
        onBack = onBack,
        actions = {
            if (rec.editableList) {
                TextButton(onClick = {
                    ed.addRecord(rec, copyFrom = slot)?.let { changed(); replace(Screen.RecordEdit(type, it)) }
                }) { Text("Копия") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить") }
            }
        },
    ) { mod ->
        LazyColumn(mod.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rec.fields, key = { it.type }) { f ->
                FieldEditor(ed, version, FieldRef(rec, slot, f), changed)
            }
            item { Text("", Modifier.padding(32.dp)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «$title»?") },
            text = { Text("Ссылки на эту запись в зонах, каналах и списках будут убраны.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    ed.deleteRecord(rec, slot); changed(); onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FieldEditor(ed: CodeplugEditor, version: Int, ref: FieldRef, changed: () -> Unit) {
    val f = ref.f
    val kind = ed.kind(f)
    when {
        ed.isMemberList(f) -> MemberListEditor(ed, version, ref, changed)
        f.max > 1 -> Column {
            for (i in 0 until f.max) SingleField(ed, version, ref.copy(idx = i), kind, "${ed.label(f)} #${i + 1}", changed)
        }
        else -> SingleField(ed, version, ref, kind, ed.label(f), changed)
    }
}

@Composable
private fun SingleField(ed: CodeplugEditor, version: Int, ref: FieldRef, kind: Kind, label: String, changed: () -> Unit) {
    if (!ed.available(ref)) {
        LabelValue(label, ed.display(ref)); return
    }
    when (kind) {
        Kind.BOOL -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            val checked = remember(version, ref) { ed.bool(ref) }
            Switch(checked = checked, onCheckedChange = { ed.setBool(ref, it); changed() })
        }
        Kind.CHOICE, Kind.REF -> {
            var pick by remember { mutableStateOf(false) }
            val value = remember(version, ref) { ed.display(ref) }
            LabelValue(label, value, Modifier.clickable { pick = true })
            if (pick) PickerDialog(
                title = label,
                options = remember(version, ref) { ed.options(ref) },
                onDismiss = { pick = false },
                onPick = { ed.setChoice(ref, it.value); pick = false; changed() },
            )
        }
        Kind.READONLY -> LabelValue(label, ed.display(ref))
        else -> TextFieldEditor(ed, ref, kind, label, changed)
    }
}

@Composable
private fun TextFieldEditor(ed: CodeplugEditor, ref: FieldRef, kind: Kind, label: String, changed: () -> Unit) {
    var text by remember(ref) { mutableStateOf(ed.display(ref)) }
    var error by remember(ref) { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            if (v != ed.display(ref)) {
                error = ed.setString(ref, v)
                if (error == null) changed()
            } else error = null
        },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { e -> @Composable { Text(e) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (kind) {
                Kind.FREQ -> KeyboardType.Decimal
                Kind.INT, Kind.PASSWORD -> KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MemberListEditor(ed: CodeplugEditor, version: Int, ref: FieldRef, changed: () -> Unit) {
    val f = ref.f
    val members = remember(version, ref) { ed.members(ref) }
    var pick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("${ed.label(f)} (${members.size}/${f.max})", style = MaterialTheme.typography.titleSmall)
        members.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${ed.refLabelOf(f, v)}", Modifier.weight(1f))
                IconButton(onClick = {
                    ed.setMembers(ref, members.toMutableList().also { it.removeAt(i) }); changed()
                }) { Icon(Icons.Filled.Close, "Убрать") }
            }
        }
        if (members.size < f.max) TextButton(onClick = { pick = true }) { Text("+ Добавить") }
    }
    if (pick) PickerDialog(
        title = ed.label(f),
        options = remember(version, ref) { ed.options(ref).filter { it.value != 0L && f.indexed?.none { s -> s.index.toLong() == it.value } != false } },
        onDismiss = { pick = false },
        onPick = { ed.setMembers(ref, members + it.value); pick = false; changed() },
    )
}

@Composable
private fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PickerDialog(title: String, options: List<Option>, onDismiss: () -> Unit, onPick: (Option) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.size > 12) OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown) { o ->
                        Text(
                            o.label,
                            Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
