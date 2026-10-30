package io.github.arnavdugad.metropulse.update

import io.github.arnavdugad.metropulse.update.ReleaseNotes.Block
import io.github.arnavdugad.metropulse.update.ReleaseNotes.Inline
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun `parses GitHub generated notes`() {
        val md = """
            ## What's Changed
            * Add fare calculator by @alice in https://github.com/Arnav-Dugad/metropulse/pull/12
            * **Faster** routing with `RAPTOR`


            **Full Changelog**: https://github.com/Arnav-Dugad/metropulse/compare/v1.0.0...v1.1.0
        """.trimIndent()

        val blocks = ReleaseNotes.parse(md)

        assertEquals(Block.Heading(2, listOf(Inline.Text("What's Changed"))), blocks[0])
        assertEquals(
            Block.Bullet(
                listOf(
                    Inline.Text("Add fare calculator by @alice in "),
                    Inline.Link("#12", "https://github.com/Arnav-Dugad/metropulse/pull/12"),
                ),
                depth = 0,
            ),
            blocks[1],
        )
        assertEquals(
            Block.Bullet(listOf(Inline.Bold("Faster"), Inline.Text(" routing with "), Inline.Code("RAPTOR")), depth = 0),
            blocks[2],
        )
        assertEquals(
            Block.Paragraph(
                listOf(
                    Inline.Bold("Full Changelog"),
                    Inline.Text(": "),
                    Inline.Link("All changes", "https://github.com/Arnav-Dugad/metropulse/compare/v1.0.0...v1.1.0"),
                ),
            ),
            blocks[3],
        )
        assertEquals(4, blocks.size)
    }

    @Test
    fun `joins wrapped paragraph lines and reads numbered lists and links`() {
        val blocks = ReleaseNotes.parse("First line\ncontinues here.\n\n1. Step [docs](https://x.dev/a)\n  - nested")
        assertEquals(Block.Paragraph(listOf(Inline.Text("First line continues here."))), blocks[0])
        assertEquals(Block.Numbered(1, listOf(Inline.Text("Step "), Inline.Link("docs", "https://x.dev/a"))), blocks[1])
        assertEquals(Block.Bullet(listOf(Inline.Text("nested")), depth = 1), blocks[2])
    }
}
