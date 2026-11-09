package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Plan today in the morning, Shut down in the evening (build-queue T1). Opens the Plan screen. */
@Composable
internal fun PlanPromptCard(evening: Boolean, left: Int, onOpen: () -> Unit) {
    DeckCard(border = NextBorder, bg = NextBg, modifier = Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (evening) "🌙 SHUT DOWN" else "☀️ PLAN TODAY", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Primary)
                Text(
                    if (evening) "$left still open today. Move, date or drop each." else "Pick up to ${DayPlan.PICK_MAX} for today.",
                    fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)
                )
            }
            Text(if (evening) "Shut down ›" else "Plan ›", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One-tap mood (build-queue T5): five faces in the evening, and this week's faces underneath. */
@Composable
internal fun MoodCard(n: HabitsNote, today: LocalDate, onPick: (Int) -> Unit) {
    val mine = Mood.of(n.day(today))
    DeckCard {
        CardHead("How was today?", if (mine != null) "logged ✓" else "one tap")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Mood.FACES.forEachIndexed { i, f ->
                val on = mine == i + 1
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (on) TaskTint else Surface2)
                        .border(if (on) 2.dp else 1.dp, if (on) Green else Outline, CircleShape).clickable { onPick(i + 1) },
                    contentAlignment = Alignment.Center
                ) { Text(f, fontSize = 22.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            n.week(today).forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Mood.face(Mood.of(n.day(d))) ?: "·", fontSize = 14.sp, color = Muted)
                    Text(d.dayOfWeek.name.take(1), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }
    }
}

/** Routines (build-queue T6): each routine with its length; tap to play it step by step. */
@Composable
internal fun RoutinesCard(list: List<Routine>, onPlay: (Routine) -> Unit) {
    if (list.isEmpty()) return
    DeckCard {
        CardHead("Routines")
        list.forEach { r ->
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable { onPlay(r) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.steps.firstOrNull()?.emoji?.ifEmpty { null } ?: "▶", fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 13.5.sp)
                    Text("${r.steps.size} steps · ${TaskRules.minutesLabel(r.minutes)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
                Text("▶ Start", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** Daily highlights (build-queue T4): a passage from your reading, with Open note, ♡ Keep and Next. */
@Composable
internal fun HighlightsCard(p: Passage, index: Int, count: Int, onOpen: () -> Unit, onKeep: () -> Unit, onNext: () -> Unit) {
    DeckCard {
        CardHead("From your reading", "${index + 1} of $count")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Text("“${p.text}”", fontSize = 15.sp, fontStyle = FontStyle.Italic, lineHeight = 22.sp,
                fontFamily = FontFamily.Serif)
            Text(p.title, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckButton("Open note", primary = false, modifier = Modifier.weight(1f), onClick = onOpen)
                DeckButton("♡ Keep", primary = false, modifier = Modifier.weight(1f), onClick = onKeep)
                DeckButton("Next", primary = true, modifier = Modifier.weight(1f), onClick = onNext)
            }
        }
    }
}

/** The small "Break it down" link under Next step (build-queue T3). */
@Composable
internal fun BreakItDownLink(onClick: () -> Unit) {
    Text("🌶 Too big? Break it down", fontSize = 12.5.sp, color = Primary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp))
}
