package uk.tomlowndes.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** OGG/Opus from the mic into a cache file. */
internal class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set

    fun start(): File {
        val f = File(ctx.cacheDir, "jarvis-${System.currentTimeMillis()}.ogg")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(48_000)
        r.setAudioEncodingBitRate(32_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f
        return f
    }

    /** 0..1, for the level bars. */
    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Stops and returns the file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = rec ?: return null
        rec = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return file?.takeIf { ok && it.length() > 0 }
    }

    fun cancel() { stop()?.delete(); file = null }
}
