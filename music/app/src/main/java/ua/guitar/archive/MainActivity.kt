@file:OptIn(ExperimentalMaterial3Api::class)

package ua.guitar.archive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppVm = viewModel()
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }
}

@Composable
fun App(vm: AppVm) {
    val lib by vm.store.library.collectAsState()
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    when (val s = vm.stack.last()) {
        Screen.Home -> HomeScreen(vm, lib)
        is Screen.CategoryScr -> {
            val cat = lib.categories.find { it.id == s.id }
            if (cat != null) CategoryScreen(vm, lib, cat) else LaunchedEffect(s) { vm.back() }
        }
        is Screen.SongScr -> {
            val song = lib.songs.find { it.id == s.id }
            if (song != null) SongScreen(vm, lib, song) else LaunchedEffect(s) { vm.back() }
        }
    }
    vm.busy?.let { msg ->
        Dialog(onDismissRequest = {}) {
            Card {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            }
        }
    }
}
