package ua.guitar.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Публічний GitHub-репозиторій, у Releases якого лежать нові версії APK. */
const val UPDATE_REPO = "zackkyn1-tech/moya-muzyka"

data class UpdateInfo(val versionCode: Int, val title: String, val notes: String, val apkUrl: String)

object Updater {
    /** Повертає інформацію про останній реліз або null, якщо релізів немає. Кидає виняток при помилці мережі. */
    fun latest(): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) error("GitHub відповів ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.getString("tag_name")
            val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return UpdateInfo(
                versionCode = code,
                title = o.optString("name").ifBlank { tag },
                notes = o.optString("body"),
                apkUrl = apk.getString("browser_download_url"),
            )
        } finally {
            c.disconnect()
        }
    }

    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        var u = URL(url)
        var c: HttpURLConnection
        var redirects = 0
        while (true) {
            c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                u = URL(u, c.getHeaderField("Location")); c.disconnect(); redirects++; continue
            }
            if (code != 200) error("Помилка завантаження: $code")
            break
        }
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        c.disconnect()
        return out
    }

    /** Відкриває системне встановлення. Повертає false, якщо спершу треба дати дозвіл. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(fileUri(ctx, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
