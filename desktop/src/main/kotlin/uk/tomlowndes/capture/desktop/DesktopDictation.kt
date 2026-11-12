package uk.tomlowndes.capture.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * Speech-to-text for the journal's 🎤 using Windows' own dictation (System.Speech), so nothing goes to the Jarvis
 * server. A PowerShell process listens and prints each phrase it hears; stopping ends it and hands back the lot.
 */
class DesktopDictation {
    var listening by mutableStateOf(false)
        private set
    var heard by mutableStateOf("")
        private set
    private var proc: Process? = null

    private val script = """
        Add-Type -AssemblyName System.Speech
        try { ${'$'}r = New-Object System.Speech.Recognition.SpeechRecognitionEngine([System.Globalization.CultureInfo]'en-GB') }
        catch { ${'$'}r = New-Object System.Speech.Recognition.SpeechRecognitionEngine }
        ${'$'}r.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
        ${'$'}r.SetInputToDefaultAudioDevice()
        [Console]::Out.WriteLine('READY'); [Console]::Out.Flush()
        while (${'$'}true) {
          ${'$'}res = ${'$'}r.Recognize([TimeSpan]::FromSeconds(20))
          if (${'$'}res -and ${'$'}res.Text) { [Console]::Out.WriteLine(${'$'}res.Text); [Console]::Out.Flush() }
        }
    """.trimIndent()

    /** False when PowerShell or a microphone isn't there. */
    fun start(): Boolean {
        if (listening) return true
        heard = ""
        val p = runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        proc = p; listening = true
        thread(isDaemon = true, name = "dictation") {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { l ->
                    val t = l.trim()
                    if (t.isNotEmpty() && t != "READY" && !t.contains("Exception") && !t.startsWith("At line")) heard = (heard + " " + t).trim()
                }
            }
            listening = false
        }
        return true
    }

    /** Stops listening and returns everything heard. */
    fun stop(): String {
        proc?.destroy(); proc = null
        listening = false
        return heard
    }
}
