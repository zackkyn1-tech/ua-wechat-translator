package ua.guitar.archive

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(f: File) {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(192000)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        rec = r
        file = f
    }

    fun amplitude(): Int = try {
        rec?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    /** true — якщо запис успішно збережено. */
    fun stop(): Boolean {
        val r = rec ?: return false
        rec = null
        return try {
            r.stop(); true
        } catch (_: RuntimeException) {
            false
        } finally {
            r.release()
        }
    }
}

class Player {
    var current by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var position by mutableIntStateOf(0); private set
    var duration by mutableIntStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var looping by mutableStateOf(false); private set
    private var mp: MediaPlayer? = null

    fun toggle(path: String) {
        val m = mp
        if (current == path && m != null) {
            if (playing) {
                m.pause(); playing = false
            } else {
                m.start(); applySpeed(); playing = true
            }
            return
        }
        release()
        val n = MediaPlayer()
        try {
            n.setDataSource(path)
            n.prepare()
        } catch (e: Exception) {
            n.release(); throw e
        }
        n.isLooping = looping
        n.setOnCompletionListener { playing = false; position = 0 }
        mp = n
        current = path
        duration = n.duration
        n.start()
        applySpeed()
        playing = true
    }

    private fun applySpeed() {
        mp?.let {
            try {
                it.playbackParams = it.playbackParams.setSpeed(speed)
            } catch (_: Exception) {
            }
        }
    }

    fun changeSpeed(s: Float) {
        speed = s
        if (playing) applySpeed()
    }

    fun toggleLoop() {
        looping = !looping
        mp?.isLooping = looping
    }

    fun seek(ms: Int) {
        mp?.seekTo(ms)
        position = ms
    }

    fun tick() {
        mp?.let { if (playing) position = it.currentPosition }
    }

    fun release() {
        mp?.release()
        mp = null
        current = null
        playing = false
        position = 0
        duration = 0
    }
}
