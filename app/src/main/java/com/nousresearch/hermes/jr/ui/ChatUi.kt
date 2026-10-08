package com.nousresearch.hermes.jr.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.jr.ui.theme.Accent
import com.nousresearch.hermes.jr.ui.theme.Elevated
import com.nousresearch.hermes.jr.ui.theme.Ink
import com.nousresearch.hermes.jr.ui.theme.Muted
import kotlin.math.abs

/** Code / quote surfaces, a step below the bubble. */
private val CodeBg = Color(0xFF0B0F14)
private val Stroke = Color(0xFF30363D)
/** User bubble: the gold accent, dimmed so long messages stay readable. */
private val UserBubble = Color(0xFF3A2A12)

/** Stable per-name avatar colors (the desktop derives a face color from the bot name the same way). */
private val AvatarColors = listOf(
    Color(0xFFC08532), Color(0xFF3FB950), Color(0xFF58A6FF), Color(0xFFBC8CFF),
    Color(0xFFFF7B72), Color(0xFF39C5CF), Color(0xFFD29922), Color(0xFFF778BA),
)

fun avatarColor(name: String): Color = AvatarColors[abs(name.lowercase().hashCode()) % AvatarColors.size]

@Composable
fun Avatar(name: String, size: Int = 28) {
    val color = avatarColor(name)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trimStart('@').firstOrNull()?.uppercase() ?: "?",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.45f).sp,
        )
    }
}

/** "now", "5m", "3h", "Tue 14:02", "12 Sep" — subtle, like the desktop's relative time. */
fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return ""
    val diff = (now - at).coerceAtLeast(0) / 1000
    return when {
        diff < 45 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        diff < 6 * 86_400 -> java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}

/**
 * A chat message. User: right-aligned accent bubble. Bot: left, avatar + name on the first message
 * of a run ([showHeader]); consecutive messages from the same speaker group under one header.
 */
@Composable
fun MessageBubble(
    who: String,
    text: String,
    mine: Boolean,
    showHeader: Boolean,
    time: String = "",
    pending: Boolean = false,
    markdown: Boolean = !mine,
) {
    val top = if (showHeader) 10.dp else 2.dp
    if (mine) {
        Column(Modifier.fillMaxWidth().padding(top = top), horizontalAlignment = Alignment.End) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(UserBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .alpha(if (pending) 0.6f else 1f),
            ) {
                SelectionContainer { Text(mentionText(text), color = Ink, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            val meta = if (pending) "sending…" else time
            if (meta.isNotBlank() && showMetaFooter(showHeader, pending)) Text(meta, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = top), verticalAlignment = Alignment.Top) {
        if (showHeader) Avatar(who) else Spacer(Modifier.width(28.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, color = avatarColor(who), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (time.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(time, color = Muted, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.size(2.dp))
            }
            Box(
                Modifier.widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(Elevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (markdown) Markdown(text) else SelectionContainer { Text(text, color = Ink, fontSize = 15.sp) }
            }
        }
    }
}

private fun showMetaFooter(showHeader: Boolean, pending: Boolean) = pending || showHeader

/** Subtle centered system line (room renamed, a member failed, …). */
@Composable
fun NoticeRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Elevated).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Compact tool line in a 1:1 chat. */
@Composable
fun ToolRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "⚙ " + text.lineSequence().firstOrNull().orEmpty().take(120),
            color = Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CodeBg).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "@default is thinking ●●●" — three pulsing dots after a label. */
@Composable
fun TypingIndicator(label: String, who: String = "") {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "dots")
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (who.isNotBlank()) Avatar(who, 22) else Spacer(Modifier.width(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Muted, fontSize = 12.sp, fontStyle = FontStyle.Italic)
        Spacer(Modifier.width(6.dp))
        repeat(3) { index ->
            val on = phase.toInt() == index
            Box(Modifier.padding(horizontal = 2.dp).size(5.dp).clip(CircleShape).background(if (on) Accent else Muted.copy(alpha = 0.4f)))
        }
    }
}

// ── minimal markdown ─────────────────────────────────────────────────────────
// Blocks: fenced code, headings, bullet / numbered lists, block quotes, rules, paragraphs.
// Inline: **bold**, *italic* / _italic_, `code`, ~~strike~~, [text](url), bare URLs, @mentions.
// Small on purpose: no dependency, nothing that can throw on odd input (falls back to plain text).

private sealed interface Block
private data class Para(val text: String) : Block
private data class Heading(val level: Int, val text: String) : Block
private data class Code(val lang: String, val text: String) : Block
private data class ListItem(val marker: String, val text: String, val indent: Int) : Block
private data class Quote(val text: String) : Block
private data object Rule : Block

private val bulletRe = Regex("""^(\s*)([-*+•])\s+(.*)$""")
private val numberRe = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")

private fun parseBlocks(source: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Para(para.toString().trimEnd())
        para.clear()
    }
    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                out += Code(lang, body.toString().trimEnd('\n'))
            }
            trimmed.isEmpty() -> flush()
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                out += Heading(m.groupValues[1].length, m.groupValues[2])
            }
            trimmed.matches(Regex("""^([-*_])(\s*\1){2,}\s*$""")) -> {
                flush()
                out += Rule
            }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                out += ListItem("•", m.groupValues[3], m.groupValues[1].length / 2)
            }
            numberRe.matches(line) -> {
                flush()
                val m = numberRe.find(line)!!
                out += ListItem(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> {
                flush()
                val body = StringBuilder(trimmed.removePrefix(">").trimStart())
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                    i++
                    body.append('\n').append(lines[i].trimStart().removePrefix(">").trimStart())
                }
                out += Quote(body.toString())
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline))
private val inlineRe = Regex(
    """(\*\*|__)(.+?)\1|~~(.+?)~~|`([^`\n]+)`|\[([^\]\n]+)]\((https?://[^)\s]+)\)|(https?://[^\s<>()]+[^\s<>().,;:!?'"])|(?<![\w*])[*_](?![\s*_])(.+?)(?<![\s*_])[*_](?![\w*])|(?<![\w@])@([A-Za-z0-9_.-]+)""",
)

/** Inline markdown → AnnotatedString. Never throws: on any regex surprise the raw text is used. */
fun inlineMarkdown(text: String, base: SpanStyle = SpanStyle()): AnnotatedString = try {
    buildAnnotatedString {
        var last = 0
        for (m in inlineRe.findAll(text)) {
            append(text.substring(last, m.range.first))
            val g = m.groupValues
            when {
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(g[2])) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBg, color = Color(0xFFE6B673))) { append(" ${g[4]} ") }
                g[5].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], linkStyle)) { append(g[5]) }
                g[7].isNotEmpty() -> withLink(LinkAnnotation.Url(g[7], linkStyle)) { append(g[7]) }
                g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8]) }
                g[9].isNotEmpty() -> withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Medium)) { append("@" + g[9]) }
                else -> append(m.value)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
} catch (_: Exception) {
    AnnotatedString(text)
}

/** Plain text with only @mentions highlighted (user messages are not markdown-rendered). */
fun mentionText(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in Regex("""(?<![\w@])@([A-Za-z0-9_.-]+)""").findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(color = Color(0xFFF0C27B), fontWeight = FontWeight.Medium)) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun Markdown(source: String, color: Color = Ink) {
    val blocks = remember(source) { runCatching { parseBlocks(source) }.getOrElse { listOf(Para(source)) } }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is Para -> Text(inlineMarkdown(block.text), color = color, fontSize = 15.sp, lineHeight = 21.sp)
                    is Heading -> Text(
                        inlineMarkdown(block.text),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = when (block.level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.sp },
                    )
                    is ListItem -> Row(Modifier.padding(start = (block.indent * 14).dp)) {
                        Text(block.marker, color = Accent, fontSize = 15.sp, modifier = Modifier.width(if (block.marker == "•") 14.dp else 22.dp))
                        Text(inlineMarkdown(block.text), color = color, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                    is Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Accent.copy(alpha = 0.6f)))
                        Spacer(Modifier.width(8.dp))
                        Text(inlineMarkdown(block.text), color = Muted, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                    }
                    is Code -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CodeBg),
                    ) {
                        if (block.lang.isNotBlank()) {
                            Text(block.lang, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
                        }
                        Text(
                            block.text,
                            color = Color(0xFFE6EDF3),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        )
                    }
                    Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Stroke))
                }
            }
        }
    }
}
