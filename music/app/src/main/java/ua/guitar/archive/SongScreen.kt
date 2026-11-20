@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ua.guitar.archive

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

private val tabs = listOf("🎙 Голосові", "Текст", "Акорди / таби", "Ноти", "Музика")

@Composable
fun SongScreen(vm: AppVm, lib: Library, song: Song) {
    val cat = lib.categories.find { it.id == song.categoryId }
    var tab by rememberSaveable(song.id) { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Якщо користувач виходить зі сторінки пісні під час запису — зберігаємо запис.
    DisposableEffect(song.id) { onDispose { if (vm.recSongId == song.id) vm.stopRecording() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(song.artist.ifBlank { null }, cat?.name).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(
                            sub, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Редагувати") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Видалити") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            if (song.cover != null) {
                CoverImage(
                    vm.store.file(song.cover),
                    Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)),
                    reqPx = 1024,
                )
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = pad.calculateBottomPadding())) {
                when (tab) {
                    0 -> VoiceTab(vm, song)
                    1 -> TextTab(
                        key = "lyrics-${song.id}", value = song.lyrics, label = "Текст пісні",
                        placeholder = "Встав або напиши текст пісні…", mono = false,
                    ) { t -> vm.store.updateSong(song.id) { it.copy(lyrics = t) } }
                    2 -> TextTab(
                        key = "chords-${song.id}", value = song.chords, label = "Акорди, таби, нотатки",
                        placeholder = "Am   F   C   G\ne|-----0---|\nB|---1-----|", mono = true,
                    ) { t -> vm.store.updateSong(song.id) { it.copy(chords = t) } }
                    3 -> NotesTab(vm, song)
                    4 -> MusicTab(vm, song)
                }
            }
        }
    }

    if (editing) EditItemDialog(
        title = "Редагувати пісню", nameLabel = "Назва пісні", initialName = song.title,
        secondLabel = "Виконавець", initialSecond = song.artist,
        initialCover = song.cover?.let(vm.store::file),
        onDismiss = { editing = false },
    ) { name, artist, cover, remove ->
        vm.store.updateSong(song.id) { it.copy(title = name, artist = artist) }
        applyCover(vm, song.cover, cover, remove) { f -> vm.store.updateSong(song.id) { it.copy(cover = f) } }
    }
    if (deleting) ConfirmDialog(
        "Видалити «${song.title}»?",
        "Пісню разом із усіма голосовими, нотами та файлами буде видалено назавжди.",
        onDismiss = { deleting = false },
    ) {
        if (vm.recSongId == song.id) vm.stopRecording()
        vm.player.release(); vm.store.deleteSong(song.id); vm.back()
    }
}

// ---------- Голосові ----------

@Composable
private fun VoiceTab(vm: AppVm, song: Song) {
    val ctx = LocalContext.current
    val isRec = vm.recSongId == song.id
    val otherRec = vm.recSongId != null && !isRec
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startRecording(song.id) else vm.toast("Потрібен дозвіл на мікрофон")
    }
    val view = LocalView.current
    DisposableEffect(isRec) {
        view.keepScreenOn = isRec
        onDispose { view.keepScreenOn = false }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RecorderPanel(vm, isRec, otherRec) {
                when {
                    isRec -> vm.stopRecording()
                    otherRec -> vm.toast("Зараз іде запис в іншій пісні")
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED -> vm.startRecording(song.id)
                    else -> perm.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        if (song.recordings.isEmpty()) item {
            Text(
                "Ще немає записів. Натисни на мікрофон і зіграй!",
                Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else item {
            Text(
                "Записи (${song.recordings.size})",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(song.recordings.sortedByDescending { it.createdAt }, key = { it.id }) { r ->
            val f = vm.store.file(r.file)
            AudioRow(
                vm, f, r.name, "${fmtDate(r.createdAt)} · ${fmtDur(r.durationMs)}",
                onRename = { renaming = r },
                onShare = { shareFile(ctx, f, "audio/mp4", "${song.title} — ${r.name}") },
                onDelete = { deleting = r },
            )
        }
    }

    renaming?.let { r ->
        TextInputDialog("Перейменувати запис", r.name, "Назва", { renaming = null }) { n ->
            vm.store.updateSong(song.id) { s ->
                s.copy(recordings = s.recordings.map { if (it.id == r.id) it.copy(name = n) else it })
            }
        }
    }
    deleting?.let { r ->
        ConfirmDialog("Видалити «${r.name}»?", "Запис буде видалено назавжди.", { deleting = null }) {
            val f = vm.store.file(r.file)
            if (vm.player.current == f.absolutePath) vm.player.release()
            vm.store.updateSong(song.id) { s -> s.copy(recordings = s.recordings.filter { it.id != r.id }) }
            vm.store.deleteFiles(listOf(r.file))
        }
    }
}

@Composable
private fun RecorderPanel(vm: AppVm, isRec: Boolean, otherRec: Boolean, onClick: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isRec) {
        elapsed = 0
        while (isRec) {
            elapsed = SystemClock.elapsedRealtime() - vm.recStartedAt
            level = kotlin.math.sqrt((vm.amplitude() / 32767f).coerceIn(0f, 1f))
            delay(100)
        }
        level = 0f
    }
    val scale by animateFloatAsState(1f + level * 0.45f, label = "pulse")
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                if (isRec) Box(
                    Modifier
                        .size(92.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(RecRed.copy(alpha = 0.28f), CircleShape)
                )
                FilledIconButton(
                    onClick = onClick,
                    modifier = Modifier.size(88.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (otherRec) Color.Gray else RecRed, contentColor = Color.White,
                    ),
                ) {
                    Icon(if (isRec) Icons.Rounded.Stop else Icons.Rounded.Mic, "Запис", Modifier.size(44.dp))
                }
            }
            Text(
                if (isRec) fmtDur(elapsed) else "Новий запис",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    isRec -> "Йде запис… натисни ⏹, щоб зберегти"
                    otherRec -> "Йде запис в іншій пісні"
                    else -> "Натисни на мікрофон, щоб записати голосове"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------- Текст / акорди ----------

@Composable
private fun TextTab(
    key: String,
    value: String,
    label: String,
    placeholder: String,
    mono: Boolean,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(key) { mutableStateOf(value) }
    val latest by rememberUpdatedState(text)
    val saved by rememberUpdatedState(value)
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(text) {
        if (text != saved) {
            delay(700); save(text)
        }
    }
    DisposableEffect(key) { onDispose { if (latest != saved) save(latest) } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxSize().imePadding().padding(12.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        else LocalTextStyle.current.copy(fontSize = 17.sp, lineHeight = 26.sp),
    )
}

// ---------- Ноти ----------

@Composable
private fun NotesTab(vm: AppVm, song: Song) {
    val ctx = LocalContext.current
    var viewing by remember { mutableStateOf<String?>(null) }
    var deletingImg by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Attachment?>(null) }
    var deletingFile by remember { mutableStateOf<Attachment?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        vm.importFiles(uris) { res -> vm.store.updateSong(song.id) { it.copy(noteImages = it.noteImages + res.map { r -> r.file }) } }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importFiles(uris) { res ->
            vm.store.updateSong(song.id) {
                it.copy(noteFiles = it.noteFiles + res.map { r -> Attachment(newId(), r.name, r.file, r.mime, System.currentTimeMillis()) })
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Фото нот")
                }
                FilledTonalButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.AttachFile, null); Spacer(Modifier.width(6.dp)); Text("PDF / файл")
                }
            }
        }
        if (song.noteImages.isEmpty() && song.noteFiles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(
                Icons.Rounded.LibraryMusic, "Нот ще немає",
                "Додай фото або скани нот, PDF чи файли Guitar Pro. Натисни на фото, щоб збільшити; утримуй — щоб видалити.",
            )
        }
        items(song.noteImages, key = { it }) { img ->
            Box(
                Modifier
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { viewing = img }, onLongClick = { deletingImg = img })
            ) { CoverImage(vm.store.file(img), Modifier.fillMaxSize(), reqPx = 400, icon = Icons.Rounded.Image) }
        }
        items(song.noteFiles, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { a ->
            val f = vm.store.file(a.file)
            Card(
                Modifier.combinedClickable(onClick = { openFile(ctx, f, a.mime) }, onLongClick = { renaming = a }),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, tint = Amber)
                    Text(
                        a.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { shareFile(ctx, f, a.mime, a.name) }) { Icon(Icons.Rounded.Share, "Поділитися") }
                    IconButton(onClick = { deletingFile = a }) { Icon(Icons.Rounded.Delete, "Видалити") }
                }
            }
        }
    }

    viewing?.let { ImageViewer(vm.store.file(it)) { viewing = null } }
    deletingImg?.let { img ->
        ConfirmDialog("Видалити фото?", "Фото нот буде видалено назавжди.", { deletingImg = null }) {
            vm.store.updateSong(song.id) { it.copy(noteImages = it.noteImages - img) }
            vm.store.deleteFiles(listOf(img))
        }
    }
    renaming?.let { a ->
        TextInputDialog("Перейменувати файл", a.name, "Назва", { renaming = null }) { n ->
            vm.store.updateSong(song.id) { s -> s.copy(noteFiles = s.noteFiles.map { if (it.id == a.id) it.copy(name = n) else it }) }
        }
    }
    deletingFile?.let { a ->
        ConfirmDialog("Видалити «${a.name}»?", "Файл буде видалено назавжди.", { deletingFile = null }) {
            vm.store.updateSong(song.id) { s -> s.copy(noteFiles = s.noteFiles.filter { it.id != a.id }) }
            vm.store.deleteFiles(listOf(a.file))
        }
    }
}

@Composable
private fun ImageViewer(file: File, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val bmp = rememberBitmap(file, 2048)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
        ) {
            if (bmp != null) Image(
                bmp, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            ) else CircularProgressIndicator(Modifier.align(Alignment.Center))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
            ) { Icon(Icons.Rounded.Close, "Закрити", tint = Color.White) }
        }
    }
}

// ---------- Музика ----------

@Composable
private fun MusicTab(vm: AppVm, song: Song) {
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<Attachment?>(null) }
    var deleting by remember { mutableStateOf<Attachment?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importFiles(uris) { res ->
            vm.store.updateSong(song.id) {
                it.copy(audio = it.audio + res.map { r ->
                    Attachment(newId(), r.name.substringBeforeLast('.'), r.file, r.mime, System.currentTimeMillis())
                })
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FilledTonalButton(onClick = { pick.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Додати аудіофайл (оригінал, мінус…)")
            }
        }
        if (song.audio.isEmpty()) item {
            EmptyState(
                Icons.Rounded.Album, "Музики ще немає",
                "Додай оригінал пісні або мінусовку, щоб грати під неї. Можна сповільнювати та ставити на повтор.",
            )
        }
        items(song.audio, key = { it.id }) { a ->
            val f = vm.store.file(a.file)
            AudioRow(
                vm, f, a.name, fmtDate(a.addedAt),
                onRename = { renaming = a },
                onShare = { shareFile(ctx, f, a.mime, a.name) },
                onDelete = { deleting = a },
            )
        }
    }
    renaming?.let { a ->
        TextInputDialog("Перейменувати", a.name, "Назва", { renaming = null }) { n ->
            vm.store.updateSong(song.id) { s -> s.copy(audio = s.audio.map { if (it.id == a.id) it.copy(name = n) else it }) }
        }
    }
    deleting?.let { a ->
        ConfirmDialog("Видалити «${a.name}»?", "Файл буде видалено назавжди.", { deleting = null }) {
            val f = vm.store.file(a.file)
            if (vm.player.current == f.absolutePath) vm.player.release()
            vm.store.updateSong(song.id) { s -> s.copy(audio = s.audio.filter { it.id != a.id }) }
            vm.store.deleteFiles(listOf(a.file))
        }
    }
}
