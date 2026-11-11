package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

// Sizes of the menu (tray-menu-v1 mockup). The window is a little bigger than the panel to fit its shadow.
private const val PANEL_W = 320
private const val HEADER_H = 34
private const val ROW_H = 38
private const val SEP_H = 11
private const val PAD = 6
private const val SHADOW = 10
private const val PANEL_H = PAD * 2 + HEADER_H + ROW_H * 5 + SEP_H + 2
private const val WIN_W = PANEL_W + SHADOW * 2
private const val WIN_H = PANEL_H + SHADOW * 2

/**
 * The tray icon. Compose's own Tray only offers the plain Windows menu, so this adds the icon with AWT
 * and opens our own styled menu (below) on right-click. [onMenu] gets the cursor position on screen.
 */
@Composable
fun AppTray(state: AppState, onMenu: (Point) -> Unit) {
    DisposableEffect(Unit) {
        if (!SystemTray.isSupported()) return@DisposableEffect onDispose { }
        val image = TrayMenuIcon::class.java.getResourceAsStream("/icon.png")!!.use { ImageIO.read(it) }
        val icon = TrayIcon(image, "Flight Deck").apply { isImageAutoSize = true }
        icon.addActionListener { EventQueue.invokeLater { state.windowVisible = true } }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
                    val at = Point(e.xOnScreen, e.yOnScreen)
                    EventQueue.invokeLater { onMenu(at) }
                }
            }
        })
        val tray = SystemTray.getSystemTray()
        runCatching { tray.add(icon) }
        state.notify = { title, body -> icon.displayMessage(title, body, TrayIcon.MessageType.NONE) }
        onDispose { tray.remove(icon) }
    }
}

private object TrayMenuIcon

/** Where the menu goes: its bottom-right corner at the cursor, kept on the screen the cursor is on. */
private fun menuPosition(at: Point): WindowPosition {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val conf = ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(at) }
        ?: ge.defaultScreenDevice.defaultConfiguration
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(conf)
    val b = conf.bounds.let { Rectangle(it.x + ins.left, it.y + ins.top, it.width - ins.left - ins.right, it.height - ins.top - ins.bottom) }
    // The panel's corner (not the shadow's) lines up with the cursor.
    var x = at.x - WIN_W + SHADOW
    var y = if (at.y - WIN_H + SHADOW >= b.y) at.y - WIN_H + SHADOW else at.y - SHADOW
    x = x.coerceIn(b.x - SHADOW, b.x + b.width - WIN_W + SHADOW)
    y = y.coerceIn(b.y - SHADOW, b.y + b.height - WIN_H + SHADOW)
    return WindowPosition(x.dp, y.dp)
}

/** The right-click menu from the tray icon, in the Flight Deck style. Goes away on Esc or a click elsewhere. */
@Composable
fun TrayMenuWindow(
    at: Point, icon: Painter, hotkeyLabel: String, onClose: () -> Unit,
    onQuick: () -> Unit, onOpen: () -> Unit, onSlip: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit,
) {
    val winState = rememberWindowState(size = DpSize(WIN_W.dp, WIN_H.dp), position = menuPosition(at))
    Window(
        onCloseRequest = onClose, state = winState, title = "Flight Deck menu", icon = icon,
        undecorated = true, transparent = true, alwaysOnTop = true, resizable = false, focusable = true,
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false }
    ) {
        DisposableEffect(window) {
            runCatching { window.type = java.awt.Window.Type.POPUP } // no taskbar button, where AWT allows it
            val l = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) = onClose()
            }
            window.addWindowFocusListener(l)
            window.toFront(); window.requestFocus()
            onDispose { window.removeWindowFocusListener(l) }
        }
        CaptureTheme {
            fun pick(action: () -> Unit) = { onClose(); action() }
            TrayMenu(
                hotkeyLabel,
                onQuick = pick(onQuick), onOpen = pick(onOpen), onSlip = pick(onSlip), onSettings = pick(onSettings), onQuit = pick(onQuit),
            )
        }
    }
}

@Composable
private fun TrayMenu(hotkeyLabel: String, onQuick: () -> Unit, onOpen: () -> Unit, onSlip: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxSize().padding(SHADOW.dp)) {
        Column(
            Modifier.fillMaxSize().shadow(SHADOW.dp, shape).clip(shape).background(Surface1)
                .border(1.dp, Outline, shape).padding(PAD.dp)
        ) {
            Row(Modifier.height(HEADER_H.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF9BB8FF), Color(0xFF4A6FD6))))
                )
                Spacer(Modifier.width(9.dp))
                Text("Flight Deck", color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            MenuRow("Quick capture", DrawScope::plusIcon, keys = hotkeyLabel.split(" + "), onClick = onQuick)
            MenuRow("Open Flight Deck", DrawScope::windowIcon, onClick = onOpen)
            MenuRow("Daily Slip", DrawScope::slipIcon, onClick = onSlip)
            Box(Modifier.fillMaxWidth().height(SEP_H.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
            }
            MenuRow("Settings", DrawScope::gearIcon, onClick = onSettings)
            MenuRow("Quit", DrawScope::powerIcon, quiet = true, onClick = onQuit)
        }
    }
}

@Composable
private fun MenuRow(
    label: String, glyph: DrawScope.(Color) -> Unit, keys: List<String> = emptyList(),
    quiet: Boolean = false, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val text = if (hovered) Primary else if (quiet) Muted else TextMain
    val iconColor = if (hovered) Primary else Muted
    Row(
        Modifier.fillMaxWidth().height(ROW_H.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) PrimaryTint else Color.Transparent)
            .hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick).hand()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) { glyph(iconColor) }
        Spacer(Modifier.width(12.dp))
        Text(label, color = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        if (keys.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            keys.forEach { KeyChip(it, hovered) }
        }
    }
}

@Composable
private fun KeyChip(text: String, hovered: Boolean) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        color = if (hovered) Primary else Muted,
        modifier = Modifier.clip(shape)
            .background(if (hovered) Primary.copy(alpha = 0.08f) else Surface2)
            .border(1.dp, if (hovered) Primary.copy(alpha = 0.27f) else Outline, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// Small line icons, drawn so they look the same on every Windows font setup.

private fun DrawScope.plusIcon(c: Color) {
    val w = 1.8.dp.toPx(); val m = size.width / 2; val i = size.width * 0.15f
    drawLine(c, Offset(m, i), Offset(m, size.height - i), w, StrokeCap.Round)
    drawLine(c, Offset(i, m), Offset(size.width - i, m), w, StrokeCap.Round)
}

private fun DrawScope.windowIcon(c: Color) {
    val w = 1.5.dp.toPx(); val i = size.width * 0.12f
    drawRoundRect(c, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(w))
    drawLine(c, Offset(size.width / 2, i), Offset(size.width / 2, size.height - i), w)
}

private fun DrawScope.slipIcon(c: Color) {
    val w = 1.5.dp.toPx(); val l = size.width * 0.22f; val r = size.width * 0.78f; val t = size.height * 0.08f; val b = size.height * 0.92f
    val zig = androidx.compose.ui.graphics.Path().apply {
        moveTo(l, t); lineTo(r, t); lineTo(r, b)
        val step = (r - l) / 4
        for (k in 1..4) lineTo(r - step * k + step / 2, b - size.height * 0.08f).also { lineTo(r - step * k, b) }
        close()
    }
    drawPath(zig, c, style = Stroke(w, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    for (k in 0..2) { val y = size.height * (0.3f + k * 0.17f); drawLine(c, Offset(l + w * 2, y), Offset(r - w * 2, y), w * 0.9f, StrokeCap.Round) }
}

private fun DrawScope.gearIcon(c: Color) {
    val w = 1.5.dp.toPx(); val ctr = center; val r = size.width * 0.22f
    drawCircle(c, r, ctr, style = Stroke(w))
    for (k in 0 until 8) {
        val a = Math.toRadians(k * 45.0)
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        drawLine(c, Offset(ctr.x + dx * size.width * 0.32f, ctr.y + dy * size.width * 0.32f),
            Offset(ctr.x + dx * size.width * 0.44f, ctr.y + dy * size.width * 0.44f), w * 1.3f, StrokeCap.Round)
    }
}

private fun DrawScope.powerIcon(c: Color) {
    val w = 1.6.dp.toPx(); val i = size.width * 0.14f
    drawArc(c, -60f, 300f, false, Offset(i, i + 1f), Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(w, cap = StrokeCap.Round))
    drawLine(c, Offset(size.width / 2, size.height * 0.06f), Offset(size.width / 2, size.height * 0.48f), w, StrokeCap.Round)
}
