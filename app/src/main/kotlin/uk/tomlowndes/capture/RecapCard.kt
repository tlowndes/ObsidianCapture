package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RecapTop = Color(0xFF2B2A6B)
private val RecapBottom = Color(0xFF1A1F3A)
private val RecapTile = Color(0x33FFFFFF)

/** The shareable recap at the top of the weekly review (build-queue O4). */
@Composable
internal fun RecapCard(r: Recap, onSave: () -> Unit, onShare: () -> Unit) {
    val dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(RecapTop, RecapBottom))).border(1.dp, NextBorder, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("YOUR WEEK · ${r.from.dayOfMonth}–${r.to.format(dm).uppercase(Locale.UK)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = Purple)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
            Text("${r.tasksDone}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(" tasks done", fontSize = 15.sp, color = Primary, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Recaps.hours(r.focusMin), "focus", Modifier.weight(1f))
            Tile("${r.bestStreak} 🔥", "best streak", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Tile("${r.jobsApplied}", "jobs applied", Modifier.weight(1f))
            Tile("${r.habitsHit}%", "habits hit", Modifier.weight(1f))
        }
        val max = r.perDay.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        val best = r.best?.first
        Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            r.perDay.forEach { (d, n) ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    val f = (n.toFloat() / max).coerceIn(0.05f, 1f)
                    if (f < 1f) Box(Modifier.weight(1f - f))
                    Box(Modifier.fillMaxWidth().weight(f).clip(RoundedCornerShape(4.dp)).background(if (d == best) Green else Primary))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.perDay.forEach { (d, _) -> Text(d.dayOfWeek.name.take(1), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        (listOfNotNull(Recaps.bestLine(r), r.mood)).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save to vault", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onSave).padding(vertical = 9.dp))
            Text("Share image", fontSize = 13.sp, color = OnPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).background(Primary).clickable(onClick = onShare).padding(vertical = 9.dp))
        }
    }
}

@Composable
private fun Tile(big: String, small: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(RecapTile).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(big, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(small, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

/** Draws the recap as a PNG and opens the share sheet. */
internal fun shareRecap(ctx: Context, r: Recap) {
    val w = 1080; val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(0xFF1A1F3A.toInt())
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2B2A6B.toInt(), 0xFF1A1F3A.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    fun text(s: String, x: Float, y: Float, size: Float, colour: Int, bold: Boolean = false, mono: Boolean = false) {
        p.color = colour; p.textSize = size
        p.typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(s, x, y, p)
    }
    val dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    text("YOUR WEEK · ${r.from.format(dm)} – ${r.to.format(dm)}", 80f, 140f, 36f, 0xFFB39DFF.toInt(), mono = true)
    text("${r.tasksDone}", 80f, 300f, 160f, 0xFFE8ECF5.toInt(), bold = true)
    text("tasks done", 80f + p.measureText("${r.tasksDone}") + 30f, 300f, 54f, 0xFF7AA2FF.toInt())
    val tiles = listOf(Recaps.hours(r.focusMin) to "focus", "${r.bestStreak}" to "best streak", "${r.jobsApplied}" to "jobs applied", "${r.habitsHit}%" to "habits hit")
    tiles.forEachIndexed { i, (big, small) ->
        val x = 80f + (i % 2) * 470f; val y = 360f + (i / 2) * 200f
        p.color = 0x33FFFFFF; c.drawRoundRect(RectF(x, y, x + 440f, y + 170f), 32f, 32f, p)
        text(big, x + 36f, y + 90f, 64f, 0xFFE8ECF5.toInt(), bold = true)
        text(small, x + 36f, y + 140f, 32f, 0xFF8A93A8.toInt(), mono = true)
    }
    val max = (r.perDay.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    val best = r.best?.first
    r.perDay.forEachIndexed { i, (d, n) ->
        val x = 80f + i * 132f
        val bh = 260f * n / max
        p.color = if (d == best) 0xFF5FD39A.toInt() else 0xFF7AA2FF.toInt()
        c.drawRoundRect(RectF(x, 1050f - bh.coerceAtLeast(8f), x + 100f, 1050f), 14f, 14f, p)
        text(d.dayOfWeek.name.take(1), x + 36f, 1100f, 32f, 0xFF8A93A8.toInt(), mono = true)
    }
    Recaps.bestLine(r)?.let { text(it, 80f, 1190f, 44f, 0xFFE8ECF5.toInt()) }
    text("Flight Deck", 80f, 1280f, 32f, 0xFF8A93A8.toInt(), mono = true)
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val f = File(dir, "week-recap.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share your week").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
