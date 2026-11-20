package ua.guitar.archive

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class CategoryScr(val id: String) : Screen
    data class SongScr(val id: String) : Screen
}

class AppVm(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val player = Player()
    private val recorder = Recorder(app)
    val stack = mutableStateListOf<Screen>(Screen.Home)

    var recSongId by mutableStateOf<String?>(null); private set
    var recStartedAt by mutableLongStateOf(0L); private set
    var busy by mutableStateOf<String?>(null); private set

    // ---------- Оновлення ----------
    var update by mutableStateOf<UpdateInfo?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    private var downloaded: Pair<Int, File>? = null

    init { checkUpdates(manual = false) }

    fun checkUpdates(manual: Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() } }
            val info = r.getOrNull()
            when {
                info != null && info.versionCode > BuildConfig.VERSION_CODE -> update = info
                manual && r.isFailure -> toast("Не вдалося перевірити оновлення. Є інтернет?")
                manual -> toast("У тебе найновіша версія (${BuildConfig.VERSION_NAME})")
            }
        }
    }

    fun dismissUpdate() { update = null }

    fun installUpdate() {
        val info = update ?: return
        val app = getApplication<Application>()
        downloaded?.let { (code, f) ->
            if (code == info.versionCode && f.exists()) { Updater.install(app, f); return }
        }
        if (updateProgress != null) return
        viewModelScope.launch {
            updateProgress = 0f
            val r = withContext(Dispatchers.IO) {
                runCatching { Updater.download(app, info.apkUrl) { p -> updateProgress = p } }
            }
            updateProgress = null
            r.onSuccess { f ->
                downloaded = info.versionCode to f
                if (!Updater.install(app, f)) toast("Дозволь встановлення, повернись і натисни «Оновити» ще раз")
            }.onFailure { toast("Не вдалося завантажити оновлення") }
        }
    }

    fun open(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    fun play(path: String) {
        if (recSongId != null) { toast("Спочатку зупини запис"); return }
        try {
            player.toggle(path)
        } catch (e: Exception) {
            toast("Не вдалося відтворити файл")
        }
    }

    fun amplitude() = recorder.amplitude()

    fun startRecording(songId: String) {
        if (recSongId != null) return
        player.release()
        val f = store.newMediaFile("m4a")
        try {
            recorder.start(f)
            recSongId = songId
            recStartedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            f.delete()
            toast("Не вдалося почати запис")
        }
    }

    fun stopRecording() {
        val songId = recSongId ?: return
        val f = recorder.file
        val elapsed = SystemClock.elapsedRealtime() - recStartedAt
        val ok = recorder.stop()
        recSongId = null
        if (!ok || f == null || !f.exists()) {
            f?.delete(); toast("Запис занадто короткий"); return
        }
        val dur = durationOf(f) ?: elapsed
        store.updateSong(songId) { s ->
            s.copy(
                recordings = s.recordings + Recording(
                    id = newId(),
                    name = "Запис ${s.recordings.size + 1}",
                    file = f.name,
                    createdAt = System.currentTimeMillis(),
                    durationMs = dur,
                )
            )
        }
    }

    private fun durationOf(f: File): Long? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        null
    }

    /** Копіює вибрані файли у фоні, потім викликає [onDone] у головному потоці. */
    fun importFiles(uris: List<Uri>, onDone: (List<Imported>) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Копіюю файли…"
            val res = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { store.importUri(it) }.getOrNull() }
            }
            busy = null
            if (res.size < uris.size) toast("Деякі файли не вдалося додати")
            onDone(res)
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            busy = "Створюю резервну копію…"
            val r = withContext(Dispatchers.IO) { runCatching { store.exportTo(uri) } }
            busy = null
            toast(if (r.isSuccess) "Резервну копію збережено" else "Помилка: ${r.exceptionOrNull()?.message}")
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            busy = "Відновлюю з копії…"
            val r = withContext(Dispatchers.IO) { runCatching { store.importFrom(uri) } }
            busy = null
            toast(r.fold({ "Додано пісень: $it" }, { "Помилка: ${it.message}" }))
        }
    }

    override fun onCleared() {
        stopRecording()
        player.release()
    }
}
