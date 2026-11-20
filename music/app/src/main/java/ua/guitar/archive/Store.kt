package ua.guitar.archive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Imported(val file: String, val name: String, val mime: String)

/** Зберігає бібліотеку в JSON, а медіафайли — у внутрішній папці додатку. */
class Store(private val ctx: Context) {
    val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
    private val dataFile = File(ctx.filesDir, "library.json")
    private val _lib = MutableStateFlow(load())
    val library: StateFlow<Library> = _lib.asStateFlow()
    val current: Library get() = _lib.value

    fun file(name: String) = File(mediaDir, name)

    private fun load(): Library {
        if (!dataFile.exists()) return Library()
        return try {
            LibraryJson.decode(dataFile.readText())
        } catch (e: Exception) {
            dataFile.copyTo(File(ctx.filesDir, "library-broken-${System.currentTimeMillis()}.json"), true)
            Library()
        }
    }

    fun update(f: (Library) -> Library) = synchronized(this) {
        val n = f(_lib.value)
        _lib.value = n
        val tmp = File(ctx.filesDir, "library.json.tmp")
        tmp.writeText(LibraryJson.encode(n))
        if (!tmp.renameTo(dataFile)) {
            dataFile.writeText(tmp.readText()); tmp.delete()
        }
    }

    fun updateSong(id: String, f: (Song) -> Song) =
        update { l -> l.copy(songs = l.songs.map { if (it.id == id) f(it) else it }) }

    fun updateCategory(id: String, f: (Category) -> Category) =
        update { l -> l.copy(categories = l.categories.map { if (it.id == id) f(it) else it }) }

    fun newMediaFile(ext: String) = File(mediaDir, "${newId()}.$ext")

    fun deleteFiles(names: Collection<String?>) {
        names.filterNotNull().forEach { file(it).delete() }
    }

    fun deleteSong(id: String) {
        val s = current.songs.find { it.id == id } ?: return
        update { it.copy(songs = it.songs.filter { x -> x.id != id }) }
        deleteFiles(s.allFiles())
    }

    fun deleteCategory(id: String) {
        val c = current.categories.find { it.id == id } ?: return
        val songs = current.songs.filter { it.categoryId == id }
        update { l ->
            l.copy(
                categories = l.categories.filter { it.id != id },
                songs = l.songs.filter { it.categoryId != id },
            )
        }
        deleteFiles(listOf(c.cover) + songs.flatMap { it.allFiles() })
    }

    /** Копіює вибраний користувачем файл у сховище додатку. */
    fun importUri(uri: Uri): Imported {
        val cr = ctx.contentResolver
        var name = "Файл"
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (_: Exception) {
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..6 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val f = newMediaFile(ext)
        (cr.openInputStream(uri) ?: error("Не вдалося відкрити файл")).use { i ->
            f.outputStream().use { i.copyTo(it) }
        }
        return Imported(f.name, name, mime)
    }

    fun exportTo(uri: Uri) {
        val os = ctx.contentResolver.openOutputStream(uri) ?: error("Не вдалося створити файл")
        ZipOutputStream(BufferedOutputStream(os)).use { z ->
            z.putNextEntry(ZipEntry("library.json"))
            z.write(LibraryJson.encode(current).toByteArray())
            z.closeEntry()
            mediaDir.listFiles()?.forEach { f ->
                z.putNextEntry(ZipEntry("media/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Додає пісні з резервної копії (існуючі не перезаписуються). Повертає кількість нових пісень. */
    fun importFrom(uri: Uri): Int {
        var imported: Library? = null
        val extracted = mutableListOf<File>()
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Не вдалося відкрити файл")
        ZipInputStream(BufferedInputStream(input)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == "library.json") {
                    imported = LibraryJson.decode(z.readBytes().toString(Charsets.UTF_8))
                } else if (e.name.startsWith("media/")) {
                    val f = File(mediaDir, File(e.name).name)
                    if (!f.exists()) {
                        f.outputStream().use { z.copyTo(it) }
                        extracted += f
                    }
                }
            }
        }
        val lib = imported ?: run {
            extracted.forEach { it.delete() }
            error("Це не резервна копія «Моя музика»")
        }
        var added = 0
        update { cur ->
            val catIds = cur.categories.map { it.id }.toSet()
            val songIds = cur.songs.map { it.id }.toSet()
            val newSongs = lib.songs.filter { it.id !in songIds }
            added = newSongs.size
            cur.copy(
                categories = cur.categories + lib.categories.filter { it.id !in catIds },
                songs = cur.songs + newSongs,
            )
        }
        return added
    }
}
