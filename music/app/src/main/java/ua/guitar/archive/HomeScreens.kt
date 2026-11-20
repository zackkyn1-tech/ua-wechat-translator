@file:OptIn(ExperimentalMaterial3Api::class)

package ua.guitar.archive

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Зберігає нову обкладинку (якщо вибрана) і повертає ім'я файлу, яке треба записати. */
fun applyCover(vm: AppVm, old: String?, newCover: Uri?, remove: Boolean, save: (String?) -> Unit) {
    when {
        newCover != null -> vm.importFiles(listOf(newCover)) { res ->
            res.firstOrNull()?.let { save(it.file); vm.store.deleteFiles(listOf(old)) }
        }
        remove -> {
            save(null); vm.store.deleteFiles(listOf(old))
        }
    }
}

@Composable
fun HomeScreen(vm: AppVm, lib: Library) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(vm::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::importBackup)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            query, { query = it },
                            placeholder = { Text("Пошук пісні…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Моя музика", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Пошук") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Меню") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Зберегти резервну копію") },
                                    leadingIcon = { Icon(Icons.Rounded.Upload, null) },
                                    onClick = {
                                        menu = false
                                        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                        exportLauncher.launch("music-backup-$d.zip")
                                    })
                                DropdownMenuItem(
                                    text = { Text("Відновити з копії") },
                                    leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                    onClick = {
                                        menu = false
                                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                                    })
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Перевірити оновлення") },
                                    leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                                    onClick = { menu = false; vm.checkUpdates(manual = true) })
                                DropdownMenuItem(
                                    text = { Text("Версія ${BuildConfig.VERSION_NAME}") },
                                    enabled = false,
                                    onClick = {})
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching) ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Категорія") },
            )
        },
    ) { pad ->
        when {
            searching -> SearchResults(vm, lib, query, pad)
            lib.categories.isEmpty() -> EmptyState(
                Icons.Rounded.LibraryMusic,
                "Поки що порожньо",
                "Створи першу категорію — наприклад «Рок» — і додавай туди пісні, тексти, ноти та свої записи.",
                Modifier.padding(pad).padding(top = 48.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = pad.calculateTopPadding() + 4.dp,
                    bottom = pad.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(lib.categories, key = { it.id }) { c ->
                    CategoryCard(
                        vm, c, lib.songs.count { it.categoryId == c.id },
                        onEdit = { editing = c }, onDelete = { deleting = c },
                    )
                }
            }
        }
    }

    if (adding) EditItemDialog(
        title = "Нова категорія", nameLabel = "Назва (напр. Рок)", initialName = "",
        secondLabel = null, initialSecond = "", initialCover = null,
        onDismiss = { adding = false },
    ) { name, _, cover, _ ->
        val id = newId()
        vm.store.update { it.copy(categories = it.categories + Category(id, name, null, System.currentTimeMillis())) }
        applyCover(vm, null, cover, false) { f -> vm.store.updateCategory(id) { it.copy(cover = f) } }
    }
    editing?.let { c ->
        EditItemDialog(
            title = "Редагувати категорію", nameLabel = "Назва", initialName = c.name,
            secondLabel = null, initialSecond = "", initialCover = c.cover?.let(vm.store::file),
            onDismiss = { editing = null },
        ) { name, _, cover, remove ->
            vm.store.updateCategory(c.id) { it.copy(name = name) }
            applyCover(vm, c.cover, cover, remove) { f -> vm.store.updateCategory(c.id) { it.copy(cover = f) } }
        }
    }
    deleting?.let { c ->
        ConfirmDialog(
            "Видалити «${c.name}»?",
            "Усі пісні цієї категорії разом із записами та файлами буде видалено назавжди.",
            onDismiss = { deleting = null },
        ) { vm.player.release(); vm.store.deleteCategory(c.id) }
    }
}

@Composable
private fun CategoryCard(vm: AppVm, c: Category, count: Int, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(MaterialTheme.shapes.large)
            .clickable { vm.open(Screen.CategoryScr(c.id)) },
        shape = MaterialTheme.shapes.large,
    ) {
        Box {
            CoverImage(c.cover?.let(vm.store::file), Modifier.fillMaxSize(), icon = Icons.Rounded.LibraryMusic)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(songsCount(count), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(
                    onClick = { menu = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f)),
                    modifier = Modifier.padding(4.dp),
                ) { Icon(Icons.Rounded.MoreVert, "Меню", tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Редагувати") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text("Видалити") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SearchResults(vm: AppVm, lib: Library, query: String, pad: PaddingValues) {
    val q = query.trim().lowercase()
    val found = if (q.isEmpty()) emptyList() else lib.songs.filter {
        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.lyrics.lowercase().contains(q)
    }.sortedBy { it.title.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        if (q.isNotEmpty() && found.isEmpty()) item {
            EmptyState(Icons.Rounded.SearchOff, "Нічого не знайдено", "Спробуй іншу назву або виконавця")
        }
        items(found, key = { it.id }) { s ->
            val cat = lib.categories.find { it.id == s.categoryId }
            SongListItem(vm, s, cat, showCategory = true)
        }
    }
}

@Composable
fun SongListItem(vm: AppVm, s: Song, cat: Category?, showCategory: Boolean = false) {
    val sub = buildList {
        if (s.artist.isNotBlank()) add(s.artist)
        if (showCategory && cat != null) add(cat.name)
        if (s.recordings.isNotEmpty()) add("🎙 ${s.recordings.size}")
    }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (sub.isNotEmpty()) ({ Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
        leadingContent = {
            CoverImage(
                (s.cover ?: cat?.cover)?.let(vm.store::file),
                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), reqPx = 160,
            )
        },
        modifier = Modifier.clickable { vm.open(Screen.SongScr(s.id)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun CategoryScreen(vm: AppVm, lib: Library, cat: Category) {
    val songs = lib.songs.filter { it.categoryId == cat.id }.sortedBy { it.title.lowercase() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Редагувати") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Видалити") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Пісня") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 96.dp),
        ) {
            if (cat.cover != null) item {
                CoverImage(
                    vm.store.file(cat.cover),
                    Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)),
                    reqPx = 1024,
                )
            }
            if (songs.isEmpty()) item {
                EmptyState(Icons.Rounded.MusicNote, "Тут ще немає пісень", "Натисни «Пісня», щоб додати першу.")
            } else item {
                Text(
                    songsCount(songs.size),
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(songs, key = { it.id }) { s -> SongListItem(vm, s, cat) }
        }
    }

    if (adding) EditItemDialog(
        title = "Нова пісня", nameLabel = "Назва пісні", initialName = "",
        secondLabel = "Виконавець (необов'язково)", initialSecond = "", initialCover = null,
        onDismiss = { adding = false },
    ) { name, artist, cover, _ ->
        val id = newId()
        vm.store.update { it.copy(songs = it.songs + Song(id = id, categoryId = cat.id, title = name, artist = artist)) }
        applyCover(vm, null, cover, false) { f -> vm.store.updateSong(id) { it.copy(cover = f) } }
        vm.open(Screen.SongScr(id))
    }
    if (editing) EditItemDialog(
        title = "Редагувати категорію", nameLabel = "Назва", initialName = cat.name,
        secondLabel = null, initialSecond = "", initialCover = cat.cover?.let(vm.store::file),
        onDismiss = { editing = false },
    ) { name, _, cover, remove ->
        vm.store.updateCategory(cat.id) { it.copy(name = name) }
        applyCover(vm, cat.cover, cover, remove) { f -> vm.store.updateCategory(cat.id) { it.copy(cover = f) } }
    }
    if (deleting) ConfirmDialog(
        "Видалити «${cat.name}»?",
        "Усі пісні цієї категорії разом із записами та файлами буде видалено назавжди.",
        onDismiss = { deleting = false },
    ) { vm.player.release(); vm.store.deleteCategory(cat.id); vm.back() }
}
