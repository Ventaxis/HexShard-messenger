package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.HexShardTeal
import com.example.ui.theme.HexShardTealLight

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class ListItem(val bullet: String, val text: String) : MarkdownBlock()
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    isDark: Boolean = true
) {
    val blocks = parseMarkdownBlocks(text)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            when (block) {
                is MarkdownBlock.Header -> {
                    val fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 14.sp
                    }
                    val headerAnnotated = parseInlineMarkdown(block.text, color, isDark)
                    Text(
                        text = headerAnnotated,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        lineHeight = (fontSize.value * 1.3f).sp
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    val codeBg = if (isDark) Color(0xFF141920) else Color(0xFFF1F5F9)
                    val codeBorder = if (isDark) Color(0xFF24303E) else Color(0xFFCBD5E1)
                    val codeTxtColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(codeBg)
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = block.language.ifBlank { "code" }.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) HexShardTealLight else HexShardTeal
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("code snippet", block.code)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                Text(
                                    text = block.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = codeTxtColor,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    val quoteBarColor = if (isDark) HexShardTealLight else HexShardTeal
                    val quoteBg = if (isDark) Color(0xFF11171E) else Color(0xFFF8FAFC)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(quoteBg)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(quoteBarColor, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val quoteAnnotated = parseInlineMarkdown(block.text, color.copy(alpha = 0.9f), isDark)
                        Text(
                            text = quoteAnnotated,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = color.copy(alpha = 0.9f),
                            lineHeight = 18.sp
                        )
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.bullet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) HexShardTealLight else HexShardTeal
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val itemAnnotated = parseInlineMarkdown(block.text, color, isDark)
                        ClickableText(
                            text = itemAnnotated,
                            onClick = { offset ->
                                itemAnnotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            uriHandler.openUri(annotation.item)
                                        } catch (_: Exception) {}
                                    }
                            }
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    val paragraphAnnotated = parseInlineMarkdown(block.text, color, isDark)
                    ClickableText(
                        text = paragraphAnnotated,
                        onClick = { offset ->
                            paragraphAnnotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    try {
                                        uriHandler.openUri(annotation.item)
                                    } catch (_: Exception) {}
                                }
                        }
                    )
                }
            }
        }
    }
}

fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = input.lines()
    var i = 0
    val totalLines = lines.size

    while (i < totalLines) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < totalLines && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < totalLines && lines[i].trim().startsWith("```")) {
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            val content = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, content))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            val content = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.Blockquote(content))
            i++
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val bullet = "•"
            val content = trimmed.drop(2).trim()
            blocks.add(MarkdownBlock.ListItem(bullet, content))
            i++
            continue
        }

        val numberedMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
        if (numberedMatch != null) {
            val num = numberedMatch.groupValues[1]
            val content = numberedMatch.groupValues[2]
            blocks.add(MarkdownBlock.ListItem("$num.", content))
            i++
            continue
        }

        if (trimmed.isBlank()) {
            i++
            continue
        }

        // Collect continuous paragraph lines
        val paragraphLines = mutableListOf<String>()
        while (i < totalLines) {
            val curr = lines[i]
            val currTrimmed = curr.trim()
            if (currTrimmed.isBlank() || currTrimmed.startsWith("```") ||
                currTrimmed.startsWith("#") || currTrimmed.startsWith(">") ||
                currTrimmed.startsWith("- ") || currTrimmed.startsWith("* ") ||
                Regex("^(\\d+)\\.\\s+(.*)$").matches(currTrimmed)
            ) {
                break
            }
            paragraphLines.add(curr)
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
        }
    }

    if (blocks.isEmpty()) {
        blocks.add(MarkdownBlock.Paragraph(input))
    }

    return blocks
}

fun parseInlineMarkdown(text: String, defaultColor: Color, isDark: Boolean): AnnotatedString {
    val linkColor = if (isDark) HexShardTealLight else HexShardTeal
    val codeInlineBg = if (isDark) Color(0xFF202A36) else Color(0xFFE2E8F0)
    val codeInlineColor = if (isDark) Color(0xFF4EE2A0) else Color(0xFF03A062)

    return buildAnnotatedString {
        var cursor = 0
        val len = text.length

        while (cursor < len) {
            // Check for links [label](url)
            if (text[cursor] == '[') {
                val closeBracket = text.indexOf(']', cursor)
                if (closeBracket != -1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 1)
                    if (closeParen != -1) {
                        val label = text.substring(cursor + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        val start = length
                        append(label)
                        addStyle(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            ),
                            start = start,
                            end = length
                        )
                        addStringAnnotation(
                            tag = "URL",
                            annotation = url,
                            start = start,
                            end = length
                        )
                        cursor = closeParen + 1
                        continue
                    }
                }
            }

            // Check for inline code `code`
            if (text[cursor] == '`') {
                val nextBacktick = text.indexOf('`', cursor + 1)
                if (nextBacktick != -1) {
                    val codeContent = text.substring(cursor + 1, nextBacktick)
                    val start = length
                    append(codeContent)
                    addStyle(
                        style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeInlineBg,
                            color = codeInlineColor,
                            fontSize = 12.sp
                        ),
                        start = start,
                        end = length
                    )
                    cursor = nextBacktick + 1
                    continue
                }
            }

            // Check for bold **text** or __text__
            if ((text.startsWith("**", cursor) || text.startsWith("__", cursor)) && cursor + 2 < len) {
                val token = text.substring(cursor, cursor + 2)
                val closeIdx = text.indexOf(token, cursor + 2)
                if (closeIdx != -1) {
                    val inner = text.substring(cursor + 2, closeIdx)
                    val start = length
                    append(inner)
                    addStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor),
                        start = start,
                        end = length
                    )
                    cursor = closeIdx + 2
                    continue
                }
            }

            // Check for italic *text* or _text_
            if ((text[cursor] == '*' || text[cursor] == '_') && cursor + 1 < len && text[cursor + 1] != '*' && text[cursor + 1] != '_') {
                val token = text[cursor]
                val closeIdx = text.indexOf(token, cursor + 1)
                if (closeIdx != -1 && closeIdx > cursor + 1) {
                    val inner = text.substring(cursor + 1, closeIdx)
                    val start = length
                    append(inner)
                    addStyle(
                        style = SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor),
                        start = start,
                        end = length
                    )
                    cursor = closeIdx + 1
                    continue
                }
            }

            // Plain character
            val start = length
            append(text[cursor])
            addStyle(
                style = SpanStyle(color = defaultColor, fontSize = 14.sp),
                start = start,
                end = length
            )
            cursor++
        }
    }
}
