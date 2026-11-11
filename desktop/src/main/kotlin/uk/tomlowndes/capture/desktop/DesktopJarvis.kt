package uk.tomlowndes.capture.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.tomlowndes.capture.Jarvis
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Jarvis on Windows (windows-jarvis-v1): the same server, workers and chat ids as the phone. Chats are kept on
 * this PC in %LOCALAPPDATA%\Vault Capture\jarvis\<worker>\<chat id>.json.
 */
object DesktopJarvis {
    fun ready(p: DesktopPrefs) = Jarvis.isDemo(p.jarvisAddress) || (p.jarvisAddress.isNotBlank() && p.jarvisToken != null)

    private fun dir(p: DesktopPrefs, worker: String) = File(p.cacheDir, "jarvis/$worker").apply { mkdirs() }

    /** This worker's chats, newest first. */
    fun chats(p: DesktopPrefs, worker: String): List<Jarvis.Chat> =
        (dir(p, worker).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { Jarvis.chatFromJson(f.readText()) }.getOrNull()?.takeIf { it.messages.isNotEmpty() } }

    fun save(p: DesktopPrefs, worker: String, chat: Jarvis.Chat) {
        if (chat.messages.isEmpty()) return
        File(dir(p, worker), "${chat.id}.json").writeText(Jarvis.chatToJson(chat))
        // Keep the last 20 chats per worker.
        (dir(p, worker).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.lastModified() }.drop(20).forEach { it.delete() }
    }

    fun title(c: Jarvis.Chat) = c.messages.firstOrNull { it.mine }?.text?.take(40) ?: "New chat"

    /** Sends typed text. Call off the UI thread. */
    fun ask(p: DesktopPrefs, worker: String, text: String, chat: String): Jarvis.Reply =
        if (Jarvis.isDemo(p.jarvisAddress)) Jarvis.demoText(worker, text)
        else Jarvis.sendText(p.jarvisAddress, p.jarvisToken ?: error("Add the Jarvis token in Settings"), text, worker, chat = chat, speak = false)

    /** Sends a recording. Call off the UI thread. */
    fun askVoice(p: DesktopPrefs, worker: String, wav: File, seconds: Double, chat: String): Jarvis.Reply =
        if (Jarvis.isDemo(p.jarvisAddress)) Jarvis.demoReply(worker, seconds)
        else Jarvis.send(p.jarvisAddress, p.jarvisToken ?: error("Add the Jarvis token in Settings"), wav, worker, chat = chat)

    /** Finished background jobs not shown yet. */
    fun newDone(p: DesktopPrefs): List<Jarvis.Done> {
        if (!ready(p) || Jarvis.isDemo(p.jarvisAddress)) return emptyList()
        val seen = p.jarvisSeenDone
        val fresh = runCatching { Jarvis.done(p.jarvisAddress, p.jarvisToken!!) }.getOrDefault(emptyList()).filter { it.id !in seen }
        if (fresh.isNotEmpty()) p.jarvisSeenDone = seen + fresh.map { it.id }
        return fresh
    }

    // Speaking replies: Windows' own voice (System.Speech), so nothing waits on the server's audio.
    private var speaking: Process? = null

    fun speak(text: String, speed: Float) {
        stopSpeaking()
        val rate = when { speed <= 0.8f -> -2; speed <= 1.05f -> 0; speed <= 1.3f -> 2; speed <= 1.6f -> 4; else -> 7 }
        val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$s.Rate = $rate; \$s.Speak([Console]::In.ReadToEnd())"
        runCatching {
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
            proc.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            speaking = proc
        }
    }

    fun stopSpeaking() { speaking?.destroy(); speaking = null }
}

/** Hold-to-talk recording from the PC's default microphone, as 16 kHz mono WAV. [level] drives the meter. */
class MicRecorder {
    var level by mutableFloatStateOf(0f)
        private set
    var recording by mutableStateOf(false)
        private set
    private var line: TargetDataLine? = null
    private val buf = ByteArrayOutputStream()
    private var startedAt = 0L
    private val format = AudioFormat(16_000f, 16, 1, true, false)

    /** False when there's no microphone or Windows refused it. */
    fun start(): Boolean {
        if (recording) return true
        val l = runCatching {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).apply { open(format); start() }
        }.getOrNull() ?: return false
        line = l; buf.reset(); recording = true; startedAt = System.currentTimeMillis()
        thread(isDaemon = true, name = "mic") {
            val chunk = ByteArray(3200)
            while (recording) {
                val n = l.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                synchronized(buf) { buf.write(chunk, 0, n) }
                var sum = 0.0
                for (i in 0 until n / 2) {
                    val s = ((chunk[2 * i + 1].toInt() shl 8) or (chunk[2 * i].toInt() and 0xFF)).toShort().toDouble()
                    sum += s * s
                }
                level = (sqrt(sum / (n / 2).coerceAtLeast(1)) / 6000.0).coerceIn(0.0, 1.0).toFloat()
            }
        }
        return true
    }

    /** Stops and writes the WAV; null when it was too short to send. Returns the file and its length in seconds. */
    fun stop(out: File): Pair<File, Double>? {
        if (!recording) return null
        recording = false
        line?.run { stop(); close() }
        line = null
        level = 0f
        val bytes = synchronized(buf) { buf.toByteArray() }
        val seconds = bytes.size / 32_000.0
        if (seconds < 0.6) return null
        out.parentFile?.mkdirs()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, (bytes.size / 2).toLong()), AudioFileFormat.Type.WAVE, out)
        return out to seconds
    }

    fun cancel() { recording = false; line?.run { stop(); close() }; line = null; level = 0f }

    val seconds: Int get() = if (recording) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0
}
