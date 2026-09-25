package com.example.smartroomdashboard.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    fontSize = (22 - (block.level - 1) * 2).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
                is MarkdownBlock.Quote -> Text(
                    text = inlineMarkdown(block.text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                        .padding(8.dp),
                    fontSize = fontSize,
                    fontStyle = FontStyle.Italic,
                    color = Color.Black,
                )
                is MarkdownBlock.Code -> Text(
                    text = block.text,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Black,
                )
                is MarkdownBlock.ListItem -> Text(
                    text = inlineMarkdown(block.bullet + block.text),
                    fontSize = fontSize,
                    color = Color.Black,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    fontSize = fontSize,
                    color = Color.Black,
                )
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(val text: String) : MarkdownBlock()
    data class ListItem(val bullet: String, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

internal fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    var inCode = false
    val code = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    source.replace("\r\n", "\n").lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
                code.clear()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            if (code.isNotEmpty()) code.append('\n')
            code.append(line)
            return@forEach
        }
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed.startsWith("### ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(3, trimmed.removePrefix("### "))
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(2, trimmed.removePrefix("## "))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(1, trimmed.removePrefix("# "))
            }
            trimmed.startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix("> "))
            }
            trimmed.startsWith("- [ ] ") -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem("☐ ", trimmed.removePrefix("- [ ] "))
            }
            trimmed.startsWith("- [x] ", ignoreCase = true) -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem("☑ ", trimmed.substringAfter("] "))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem("• ", trimmed.substring(2))
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    if (inCode) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    flushParagraph()
    return blocks
}

internal fun inlineMarkdown(text: String) = buildAnnotatedString {
    val regex = Regex("""(\*\*[^*\n]+?\*\*|\*[^*\n]+?\*|`[^`\n]+?`|\[[^\]]+\]\([^)]+\))""")
    var index = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(index, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> appendInline(token.removeSurrounding("**"), SpanStyle(fontWeight = FontWeight.Bold))
            token.startsWith("*") -> appendInline(token.removeSurrounding("*"), SpanStyle(fontStyle = FontStyle.Italic))
            token.startsWith("`") -> appendInline(
                token.removeSurrounding("`"),
                SpanStyle(fontFamily = FontFamily.Monospace),
            )
            token.startsWith("[") -> {
                val label = token.substringAfter("[").substringBefore("]")
                appendInline(label, SpanStyle(textDecoration = TextDecoration.Underline))
            }
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(value: String, style: SpanStyle) {
    val start = length
    append(value)
    addStyle(style, start, length)
}
