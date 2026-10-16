package uk.tomlowndes.capture.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A system-wide keyboard shortcut (Win32 RegisterHotKey), so quick capture opens from any app.
 * Windows keeps Win+Space combinations for switching keyboard language, so the choices avoid the Win key.
 */
class Hotkey private constructor(private val thread: Thread, private val threadId: Int) {
    data class Choice(val label: String, val mods: Int, val vk: Int)

    fun stop() {
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        thread.join(1000)
    }

    companion object {
        private const val MOD_ALT = 0x0001
        private const val MOD_CONTROL = 0x0002
        private const val MOD_SHIFT = 0x0004
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_SPACE = 0x20
        private const val WM_HOTKEY = 0x0312

        val CHOICES = listOf(
            Choice("Ctrl + Shift + Space", MOD_CONTROL or MOD_SHIFT, VK_SPACE),
            Choice("Ctrl + Alt + Space", MOD_CONTROL or MOD_ALT, VK_SPACE),
            Choice("Alt + Shift + Space", MOD_ALT or MOD_SHIFT, VK_SPACE),
            Choice("Ctrl + Alt + N", MOD_CONTROL or MOD_ALT, 'N'.code),
            Choice("Ctrl + Shift + K", MOD_CONTROL or MOD_SHIFT, 'K'.code),
        )

        fun choice(label: String) = CHOICES.firstOrNull { it.label == label } ?: CHOICES.first()

        /** Registers [c]; [onPress] runs on the hotkey thread. Null if another app already has this shortcut. */
        fun register(c: Choice, onPress: () -> Unit): Hotkey? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            val ok = AtomicBoolean(false)
            val ready = CountDownLatch(1)
            var tid = 0
            val t = Thread({
                tid = Kernel32.INSTANCE.GetCurrentThreadId()
                // The hotkey belongs to the thread that registers it, and arrives in that thread's message queue.
                ok.set(User32.INSTANCE.RegisterHotKey(null, 1, c.mods or MOD_NOREPEAT, c.vk))
                ready.countDown()
                if (!ok.get()) return@Thread
                val msg = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_HOTKEY) runCatching(onPress)
                }
                User32.INSTANCE.UnregisterHotKey(null, 1)
            }, "quick-capture-hotkey").apply { isDaemon = true; start() }
            ready.await()
            return if (ok.get()) Hotkey(t, tid) else null
        }
    }
}
