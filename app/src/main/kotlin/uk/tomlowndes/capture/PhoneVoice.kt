package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android's own speech-to-text for hold to talk, so a message doesn't wait on Whisper on the box. The recogniser stops
 * at each pause, so while the button is held (or locked) it's restarted and the pieces are joined. Main thread only.
 */
class PhoneSpeech(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private val heard = StringBuilder()
    private var partial = ""
    private var listening = false
    private var finishing = false
    private var onFinal: ((String) -> Unit)? = null

    /** 0..1 from the recogniser's loudness, for the level bars. */
    @Volatile var level = 0f; private set

    /** What's been heard so far, for a live line under the timer. */
    val soFar: String get() = (heard.toString() + " " + partial).trim()

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
    }

    private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { best(partialResults).takeIf { it.isNotBlank() }?.let { partial = it } }
        override fun onResults(results: Bundle?) {
            val s = best(results).ifBlank { partial }
            if (s.isNotBlank()) heard.append(if (heard.isEmpty()) "" else " ").append(s.trim())
            partial = ""
            if (finishing) deliver() else if (listening) main.post { begin() }
        }
        override fun onError(error: Int) {
            if (finishing) { deliver(); return }
            // A pause with nothing said ends a session; keep listening while the button is down.
            if (listening && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) main.post { begin() }
            else if (listening && error != SpeechRecognizer.ERROR_CLIENT) main.postDelayed({ if (listening) begin() }, 300)
        }
    }

    private fun begin() {
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener); startListening(intent) }
    }

    fun start() {
        heard.clear(); partial = ""; level = 0f
        listening = true; finishing = false; onFinal = null
        begin()
    }

    /** Stops listening and hands over everything heard (empty if nothing). */
    fun stop(onText: (String) -> Unit) {
        if (!listening) { onText(""); return }
        finishing = true; onFinal = onText
        sr?.stopListening()
        // Some recognisers never send results after stopListening: don't leave the message hanging.
        main.postDelayed({ if (finishing) deliver() }, 2500)
    }

    private fun deliver() {
        val f = onFinal ?: return
        val text = (heard.toString() + " " + partial).trim()
        onFinal = null; finishing = false; listening = false
        sr?.destroy(); sr = null; level = 0f
        f(text)
    }

    fun cancel() {
        listening = false; finishing = false; onFinal = null
        runCatching { sr?.cancel(); sr?.destroy() }
        sr = null; level = 0f
    }

    companion object {
        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)
    }
}

/** Android's own voice for Jarvis's replies. [onSpeaking] says when it starts and stops (any thread). */
class PhoneVoiceOut(context: Context, private val onSpeaking: (String?) -> Unit) {
    private var ready = false
    private var waiting: Triple<String, String, Float>? = null
    private lateinit var tts: TextToSpeech

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.UK
            // An offline UK voice when there is one, so replies don't wait on the network.
            runCatching {
                tts.voices?.filter { it.locale.country == "GB" && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }?.let { tts.voice = it }
            }
            waiting?.let { (id, t, r) -> speak(id, t, r) }; waiting = null
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { onInit(it) }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { onSpeaking(utteranceId) }
            override fun onDone(utteranceId: String?) { onSpeaking(null) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onSpeaking(null) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { onSpeaking(null) }
        })
    }

    /** Reads [text] out (Markdown stripped); [id] comes back through onSpeaking. */
    fun speak(id: String, text: String, rate: Float = 1f) {
        if (!ready) { waiting = Triple(id, text, rate); return }
        tts.setSpeechRate(rate)
        tts.speak(PhoneBrain.plainMd(text.replace(Regex("(?m)^#{1,6}\\s+|^\\s*[-*+]\\s+"), "")), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { waiting = null; if (ready) tts.stop() }

    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
