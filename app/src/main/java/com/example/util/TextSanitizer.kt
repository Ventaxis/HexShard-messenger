package com.example.util

/**
 * Plain-text projections of rich content for previews and search. Chat bubbles
 * render markdown, but one-line previews must not show raw **, ` or stray model
 * HTML tags like <bf4>.
 */
object TextSanitizer {
    // Stray or truncated HTML emitted by models (<bf4>, <b>, <br>, …) — never shown raw.
    private val htmlTag = Regex("</?[a-zA-Z][^>\\n]{0,120}>")
    private val link = Regex("(!?)\\[([^\\]\\n]{0,120})]\\((https?://[^)\\s\\n]{1,300})\\)")
    private val bold = Regex("\\*\\*([^*\\n]+?)\\*\\*|__([^_\\n]+?)__")
    private val inlineCode = Regex("`([^`\\n]+?)`")
    private val fence = Regex("```+[^\\n]*")
    private val header = Regex("(?m)^#{1,6}\\s+")
    private val quote = Regex("(?m)^>\\s?")
    private val bullet = Regex("(?m)^[-*+]\\s+")

    fun stripMarkdown(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace(fence, "")
            .replace(htmlTag, "")
            .replace(link, "$2")
            .replace(inlineCode, "$1")
            .replace(bold, "$1$2")
            .replace(header, "")
            .replace(quote, "")
            .replace(bullet, "• ")
            .trim()
    }
}
