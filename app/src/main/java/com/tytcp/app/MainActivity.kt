package com.tytcp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.tytcp.app.ui.EditorHomeScreen
import com.tytcp.app.ui.MainScreen
import com.tytcp.app.ui.MainViewModel
import com.tytcp.app.ui.RecordEditScreen
import com.tytcp.app.ui.RecordListScreen
import com.tytcp.app.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.refreshRadio()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRadio()
    }

    override fun onStop() {
        super.onStop()
        vm.saveTemplateEdits()
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Main) }
    val top = stack.last()
    fun open(s: Screen) { stack.add(s) }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (stack.last() == Screen.Main) vm.saveTemplateEdits()
    }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }

    BackHandler(enabled = stack.size > 1) { back() }

    val ed = vm.editor
    val v = vm.editVersion
    if (ed == null) {
        MainScreen(vm, openEditor = { open(Screen.EditorHome) })
        return
    }
    when (top) {
        Screen.Main -> MainScreen(vm, openEditor = { open(Screen.EditorHome) })
        Screen.EditorHome -> EditorHomeScreen(ed, v, onBack = ::back, open = ::open)
        is Screen.RecordList -> RecordListScreen(ed, v, top.type, onBack = ::back, open = ::open, changed = vm::edited)
        is Screen.RecordEdit -> RecordEditScreen(
            ed, v, top.type, top.slot,
            onBack = ::back, replace = ::replace, changed = vm::edited,
        )
    }
}
