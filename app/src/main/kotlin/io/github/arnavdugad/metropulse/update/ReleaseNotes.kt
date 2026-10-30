package io.github.arnavdugad.metropulse.update

/**
 * A small Markdown subset for release notes: headings, bullet and numbered lists, paragraphs,
 * **bold**, `code` and links. GitHub's auto-generated notes are tidied up: pull-request URLs
 * become "#123" and the compare URL becomes "All changes".
 */
object ReleaseNotes {

    sealed interface Block {
        data class Heading(val level: Int, val content: List<Inline>) : Block
        data class Bullet(val content: List<Inline>, val depth: Int) : Block
        data class Numbered(val number: Int, val content: List<Inline>) : Block
        data class Paragraph(val content: List<Inline>) : Block
    }

    sealed interface Inline {
        data class Text(val text: String) : Inline
        data class Bold(val text: String) : Inline
        data class Code(val text: String) : Inline
        data class Link(val label: String, val url: String) : Inline
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((\S+?)\)|(https?://[^\s)]+)""")
    private val PULL = Regex("""^https://github\.com/[^/]+/[^/]+/(?:pull|issues)/(\d+)$""")
    private val COMPARE = Regex("""^https://github\.com/[^/]+/[^/]+/compare/\S+$""")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(inline(paragraph.toString().trim()))
            paragraph.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) || line.trim().startsWith("<!--")) {
                flush(); continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Bullet(inline(bullet.groupValues[2]), depth = (bullet.groupValues[1].length / 2).coerceAtMost(2))
                }
                numbered != null -> {
                    flush()
                    blocks += Block.Numbered(numbered.groupValues[1].toInt(), inline(numbered.groupValues[2]))
                }
                else -> paragraph.append(if (paragraph.isEmpty()) line.trim() else " ${line.trim()}")
            }
        }
        flush()
        return blocks
    }

    fun inline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out += Inline.Text(text.substring(last, m.range.first))
            out += when {
                m.groups[1] != null -> Inline.Bold(m.groupValues[1])
                m.groups[2] != null -> Inline.Code(m.groupValues[2])
                m.groups[3] != null -> Inline.Link(m.groupValues[3], m.groupValues[4])
                else -> prettyUrl(m.groupValues[5])
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Inline.Text(text.substring(last))
        return out
    }

    private fun prettyUrl(url: String): Inline.Link {
        PULL.matchEntire(url)?.let { return Inline.Link("#${it.groupValues[1]}", url) }
        if (COMPARE.matches(url)) return Inline.Link("All changes", url)
        return Inline.Link(url.removePrefix("https://").removePrefix("http://"), url)
    }
}
