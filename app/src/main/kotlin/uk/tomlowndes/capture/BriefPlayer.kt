package uk.tomlowndes.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Daily Brief's player (daily-brief-v1): the phone voice reads the Full brief chapter by chapter, or the Short one.
 * It lives for the whole app, so it keeps going when you leave the page or lock the phone; [BriefService] holds the
 * media notification and the lock-screen and Bluetooth buttons. Compose reads the state; everything runs on the main thread.
 */
object BriefPlayer {
    var doc by mutableStateOf<BriefDoc?>(null); private set
    var full by mutableStateOf(true); private set
    var speed by mutableFloatStateOf(1f); private set
    var playing by mutableStateOf(false); private set
    /** The line being read, as an index into [queue]; -1 before the first play. */
    var index by mutableIntStateOf(-1); private set
    /** (chapter index, line); the Short read uses chapter -1. */
    var queue by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var startWhenReady = false
    private var app: Context? = null
    private var onEnd: (() -> Unit)? = null

    /** The chapter being read (Full only), or -1. */
    val chapter: Int get() = queue.getOrNull(index.coerceAtLeast(0))?.first ?: -1
    val chapterTitle: String get() = doc?.chapters?.getOrNull(chapter)?.title ?: if (full) "Daily Brief" else "Short brief"
    /** Seconds read so far and in all, at the current speed. */
    val elapsed: Int get() = (DailyBrief.seconds(queue.take(index.coerceAtLeast(0)).map { it.second }) / speed).toInt()
    val total: Int get() = (DailyBrief.seconds(queue.map { it.second }) / speed).toInt()

    private fun init(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx.applicationContext) { ok ->
            ready = ok == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            t?.language = Locale.UK
            runCatching {
                t?.voices?.filter { it.locale.country == "GB" && !it.isNetworkConnectionRequired }?.maxByOrNull { it.quality }?.let { v -> t?.voice = v }
            }
            t?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { id?.toIntOrNull()?.let { index = it }; changed() } }
                override fun onDone(id: String?) { main.post { if (id?.toIntOrNull() == queue.lastIndex && playing) finished() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { playing = false; changed() } }
            })
            main.post { if (startWhenReady) { startWhenReady = false; speakFrom(index.coerceAtLeast(0)) } }
        }
        tts = t
    }

    /** Sets today's brief. A different brief (a new day, or written again) starts from the top. */
    fun load(ctx: Context, d: BriefDoc, prefs: AppPrefs, onHeard: () -> Unit) {
        init(ctx)
        onEnd = onHeard
        speed = prefs.briefSpeed
        val same = doc?.let { it.date == d.date && it.chapters == d.chapters && it.short == d.short } == true
        doc = d
        if (!same || queue.isEmpty()) { full = prefs.briefFull; rebuild(); index = -1; if (playing) stop() }
    }

    private fun rebuild() {
        val d = doc ?: return
        queue = if (full) DailyBrief.fullQueue(d) else d.shortLines.map { -1 to it }
    }

    private fun speakFrom(i: Int) {
        val t = tts ?: return
        if (!ready) { index = i; startWhenReady = true; playing = true; changed(); return }
        if (i !in queue.indices) { stop(); return }
        t.stop()
        t.setSpeechRate(speed)
        index = i
        playing = true
        queue.drop(i).forEachIndexed { n, (_, line) ->
            t.speak(line, if (n == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "${i + n}")
        }
        changed()
    }

    fun play(from: Int = index) {
        val i = if (from < 0 || from > queue.lastIndex) 0 else from
        speakFrom(i)
    }

    fun pause() { tts?.stop(); startWhenReady = false; playing = false; changed() }

    fun toggle() = if (playing) pause() else play()

    /** Stops and drops the notification. */
    fun stop() { tts?.stop(); startWhenReady = false; playing = false; app?.let { BriefService.stop(it) } }

    private fun finished() { playing = false; index = -1; onEnd?.invoke(); app?.let { BriefService.stop(it) } }

    /** Plays chapter [c] (Full) from its first line. */
    fun playChapter(c: Int) {
        if (!full) { full = true; app?.let { AppPrefs(it).briefFull = true }; rebuild() }
        queue.indexOfFirst { it.first == c }.takeIf { it >= 0 }?.let { speakFrom(it) }
    }

    fun next() {
        if (!full) { play((index + 1).coerceAtMost(queue.lastIndex)); return }
        val c = chapter
        queue.indexOfFirst { it.first > c }.takeIf { it >= 0 }?.let { speakFrom(it) } ?: stop()
    }

    fun previous() {
        if (!full) { play((index - 1).coerceAtLeast(0)); return }
        val c = chapter
        val start = queue.indexOfFirst { it.first == c }
        // Like a music player: back to the start of this chapter, or the one before if it's only just begun.
        val to = if (index - start >= 2 || c <= 0) start else queue.indexOfFirst { it.first == c - 1 }
        speakFrom(to.coerceAtLeast(0))
    }

    fun setFull(ctx: Context, on: Boolean) {
        if (on == full) return
        val was = playing
        tts?.stop(); playing = false
        full = on; AppPrefs(ctx).briefFull = on
        rebuild(); index = -1
        if (was) play(0) else changed()
    }

    fun setSpeed(ctx: Context, s: Float) {
        speed = s; AppPrefs(ctx).briefSpeed = s
        if (playing) speakFrom(index.coerceAtLeast(0)) else changed()
    }

    private fun changed() { app?.let { if (playing || index >= 0) BriefService.update(it) } }

    val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f)
    fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"
}

/** The media notification for Daily Brief: lock-screen controls and Bluetooth/car buttons skip chapters. */
class BriefService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "DailyBrief").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = BriefPlayer.play()
                override fun onPause() = BriefPlayer.pause()
                override fun onSkipToNext() = BriefPlayer.next()
                override fun onSkipToPrevious() = BriefPlayer.previous()
                override fun onStop() = BriefPlayer.stop()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> BriefPlayer.toggle()
            ACTION_NEXT -> BriefPlayer.next()
            ACTION_PREV -> BriefPlayer.previous()
            ACTION_STOP -> { BriefPlayer.stop(); return START_NOT_STICKY }
        }
        if (BriefPlayer.doc == null) { stopSelf(); return START_NOT_STICKY }
        channel()
        val s = session!!
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (BriefPlayer.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, BriefPlayer.elapsed * 1000L, if (BriefPlayer.playing) BriefPlayer.speed else 0f)
            .build())
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, BriefPlayer.chapterTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Daily Brief")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, BriefPlayer.total * 1000L)
            .build())
        ServiceCompat.startForeground(this, ID, notification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, BriefService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(s: MediaSession): Notification {
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("tab", "BRIEF")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(icon: Int, label: String, name: String, code: Int) = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, action(name, code)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(BriefPlayer.chapterTitle)
            .setContentText("Daily Brief · ${DailyBrief.length(BriefPlayer.queue.map { it.second }, BriefPlayer.speed)}")
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP, 44))
            .setOngoing(BriefPlayer.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous chapter", ACTION_PREV, 41))
            .addAction(if (BriefPlayer.playing) act(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 42) else act(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE, 42))
            .addAction(act(android.R.drawable.ic_media_next, "Next chapter", ACTION_NEXT, 43))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily Brief", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    companion object {
        private const val ID = 7301
        private const val CHANNEL = "daily_brief"
        private const val ACTION_TOGGLE = "brief.toggle"
        private const val ACTION_NEXT = "brief.next"
        private const val ACTION_PREV = "brief.prev"
        private const val ACTION_STOP = "brief.stop"

        fun update(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BriefService::class.java)) }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BriefService::class.java)) }
    }
}
