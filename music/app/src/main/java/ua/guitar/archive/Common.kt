@file:OptIn(ExperimentalMaterial3Api::class)

package ua.guitar.archive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Amber = Color(0xFFFFB74D)
val RecRed = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF2B1700),
            primaryContainer = Color(0xFF5C3B00),
            onPrimaryContainer = Color(0xFFFFDDB3),
            secondary = Color(0xFFE0C2A0),
            secondaryContainer = Color(0xFF4A3A2A),
            onSecondaryContainer = Color(0xFFFCE0C0),
            background = Color(0xFF141110),
            surface = Color(0xFF141110),
            surfaceVariant = Color(0xFF2E2723),
            surfaceContainerLowest = Color(0xFF0F0C0B),
            surfaceContainerLow = Color(0xFF1C1816),
            surfaceContainer = Color(0xFF211C19),
            surfaceContainerHigh = Color(0xFF2B2522),
            surfaceContainerHighest = Color(0xFF36302C),
            error = Color(0xFFFF6B5E),
        ),
        content = content,
    )
}

// ---------- Картинки ----------

private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun decodeBitmap(ctx: Context, src: Any, reqPx: Int): ImageBitmap? {
    return try {
        fun open(): InputStream? = when (src) {
            is File -> src.inputStream()
            is Uri -> ctx.contentResolver.openInputStream(src)
            else -> null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqPx && bounds.outHeight / (sample * 2) >= reqPx) sample *= 2
        val bmp = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = try {
            open()?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (_: Exception) {
            0
        }
        val out = if (rot != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } else bmp
        out.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

@Composable
fun rememberBitmap(src: Any?, reqPx: Int = 512): ImageBitmap? {
    val ctx = LocalContext.current
    val key = src?.let { "$it@$reqPx" }
    val img by produceState(key?.let { bitmapCache.get(it) }, key) {
        if (src == null || key == null) {
            value = null; return@produceState
        }
        bitmapCache.get(key)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) { decodeBitmap(ctx, src, reqPx) }?.also { bitmapCache.put(key, it) }
    }
    return img
}

@Composable
fun CoverImage(
    src: Any?,
    modifier: Modifier,
    reqPx: Int = 512,
    icon: ImageVector = Icons.Rounded.MusicNote,
) {
    val bmp = rememberBitmap(src, reqPx)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, tint = Amber.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

// ---------- Форматування ----------

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtDate(t: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("uk")).format(Date(t))

fun songsCount(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "пісня"
        m10 in 2..4 && m100 !in 12..14 -> "пісні"
        else -> "пісень"
    }
    return "$n $w"
}

// ---------- Обмін файлами ----------

fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

fun shareFile(ctx: Context, f: File, mime: String, title: String) {
    val i = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f))
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Поділитися"))
}

fun openFile(ctx: Context, f: File, mime: String) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(ctx, f), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent.createChooser(i.setDataAndType(fileUri(ctx, f), "*/*"), "Відкрити за допомогою"))
        } catch (_: Exception) {
        }
    }
}

// ---------- Діалоги ----------

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("Видалити", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()); onDismiss() }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/** Діалог для створення/редагування категорії або пісні (назва, друге поле, обкладинка). */
@Composable
fun EditItemDialog(
    title: String,
    nameLabel: String,
    initialName: String,
    secondLabel: String?,
    initialSecond: String,
    initialCover: File?,
    onDismiss: () -> Unit,
    onSave: (name: String, second: String, newCover: Uri?, removeCover: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var second by remember { mutableStateOf(initialSecond) }
    var newCover by remember { mutableStateOf<Uri?>(null) }
    var removed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            newCover = uri; removed = false
        }
    }
    val preview: Any? = newCover ?: if (removed) null else initialCover
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                ) {
                    CoverImage(preview, Modifier.fillMaxSize(), icon = Icons.Rounded.AddPhotoAlternate)
                    Text(
                        if (preview == null) "Додати обкладинку" else "Змінити обкладинку",
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(6.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    if (preview != null) {
                        IconButton(
                            onClick = { newCover = null; removed = true },
                            modifier = Modifier.align(Alignment.TopEnd),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                        ) { Icon(Icons.Rounded.Close, "Прибрати", tint = Color.White) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(nameLabel) }, singleLine = true)
                if (secondLabel != null) {
                    OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(name.trim(), second.trim(), newCover, removed); onDismiss()
            }) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = Amber.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Програвач ----------

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f)

@Composable
fun AudioRow(
    vm: AppVm,
    file: File,
    title: String,
    subtitle: String,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = vm.player
    val path = file.absolutePath
    val active = p.current == path
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.play(path) }) {
                    Icon(if (active && p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Грати")
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Меню") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Перейменувати") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Text("Поділитися") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; onShare() })
                        DropdownMenuItem(
                            text = { Text("Видалити") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (active) {
                LaunchedEffect(p.playing) {
                    while (p.playing) {
                        p.tick(); delay(200)
                    }
                }
                Slider(
                    value = p.position.toFloat().coerceAtMost(p.duration.toFloat()),
                    onValueChange = { p.seek(it.toInt()) },
                    valueRange = 0f..maxOf(1, p.duration).toFloat(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(Modifier.padding(horizontal = 12.dp)) {
                    Text(fmtDur(p.position.toLong()), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(fmtDur(p.duration.toLong()), style = MaterialTheme.typography.labelSmall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    speeds.forEach { s ->
                        FilterChip(
                            selected = p.speed == s,
                            onClick = { p.changeSpeed(s) },
                            label = { Text(if (s == 1f) "1x" else "${s}x") },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconToggleButton(checked = p.looping, onCheckedChange = { p.toggleLoop() }) {
                        Icon(
                            Icons.Rounded.Repeat, "Повтор",
                            tint = if (p.looping) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
