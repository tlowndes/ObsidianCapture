package uk.tomlowndes.capture.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val INSTANCE_PORT = 47615

fun main(args: Array<String>) {
    // A second launch (Start menu, or the autostart entry) just brings the running one forward.
    val server = runCatching { ServerSocket(INSTANCE_PORT, 4, InetAddress.getLoopbackAddress()) }.getOrNull()
    if (server == null) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT).use { it.getOutputStream().write("show\n".toByteArray()) } }
        exitProcess(0)
    }
    val startHidden = "--hidden" in args
    val state = AppState(DesktopPrefs()).apply { windowVisible = !startHidden }
    thread(isDaemon = true, name = "single-instance") {
        while (true) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { BufferedReader(InputStreamReader(s.getInputStream())).readLine() }
            s.close()
            EventQueue.invokeLater { state.windowVisible = true; state.changed() }
        }
    }

    application {
        val icon = painterResource("icon.png")

        // The global shortcut: registered again whenever it's changed in Settings.
        val hotkeyLabel = state.hotkeyLabel
        DisposableEffect(hotkeyLabel) {
            val hk = Hotkey.register(Hotkey.choice(hotkeyLabel)) { EventQueue.invokeLater { state.quickVisible = true } }
            state.hotkeyFailed = hk == null
            onDispose { hk?.stop() }
        }

        // Our own tray icon and styled right-click menu (tray-menu-v1), not the plain Windows one.
        var menuAt by remember { mutableStateOf<java.awt.Point?>(null) }
        AppTray(state) { at -> menuAt = at }
        menuAt?.let { at ->
            androidx.compose.runtime.key(at) {
                TrayMenuWindow(
                    at, icon, hotkeyLabel, onClose = { menuAt = null },
                    onQuick = { state.quickVisible = true },
                    onOpen = { state.windowVisible = true },
                    onSlip = { state.tab = Tab.DECK; state.slipOpen = true; state.windowVisible = true },
                    onSettings = { state.tab = Tab.SETTINGS; state.windowVisible = true },
                    onQuit = ::exitApplication,
                )
            }
        }

        val winState = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = { state.windowVisible = false },
            visible = state.windowVisible,
            state = winState,
            title = "Flight Deck",
            icon = icon,
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyUp && jarvisKeyUp(state, e.key)) true
                else if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) false
                else when (e.key) {
                    Key.N -> { state.quickVisible = true; true }
                    Key.K -> { state.tab = Tab.FIND; true }
                    Key.R -> { state.changed(); true }
                    Key.P -> if (state.slipOpen) { state.slipPrint++; true } else if (state.tab == Tab.DECK) { state.slipOpen = true; true } else false
                    else -> false
                }
            }
        ) {
            remember(window) { window.minimumSize = java.awt.Dimension(980, 640) }
            LaunchedEffect(state.windowVisible) { if (state.windowVisible) { darkTitleBar(window); window.toFront() } }
            DisposableEffect(window) {
                val l = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = state.changed()
                }
                window.addWindowFocusListener(l)
                onDispose { window.removeWindowFocusListener(l) }
            }
            CaptureTheme { MainWindow(state) }
        }

        if (state.quickVisible) QuickCaptureWindow(state, icon)

        // Reminders (windows-reminders-v1): checked every 30 s while the app runs; cards pop up above the clock.
        LaunchedEffect(Unit) { DesktopReminders.run(state) }
        if (DesktopReminders.showing.isNotEmpty()) ReminderWindow(state, icon)
    }
}

private interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: IntByReference, size: Int): Int
}

/** Windows 10/11 draws the title bar dark to match the app (DWMWA_USE_IMMERSIVE_DARK_MODE). */
private fun darkTitleBar(w: java.awt.Window) {
    runCatching {
        val hwnd = WinDef.HWND(Native.getWindowPointer(w))
        Native.load("dwmapi", Dwm::class.java).DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4)
    }
}
