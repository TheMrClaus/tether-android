package com.tether.app.ui.chat

import com.tether.app.ui.chat.MdInline.Code
import com.tether.app.ui.chat.MdInline.Em
import com.tether.app.ui.chat.MdInline.Link
import com.tether.app.ui.chat.MdInline.Span
import com.tether.app.ui.chat.MdInline.Strong
import com.tether.app.ui.chat.MdInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown → AST mapping, feature by feature against `components/markdown.tsx` (PARITY_BASE
 * 7d65611). Each test names the web lines it pins. The web renders React nodes; the AST here is
 * one node per React element (see MarkdownParser.kt), so the expectations read like the DOM.
 */
class MarkdownParserTest {

    private fun p(vararg lines: List<MdInline>) = MdBlock.Paragraph(lines.toList())
    private fun t(s: String) = listOf(Text(s))

    // ── Fenced code (markdown.tsx 273-298) ───────────────────────────────────────────────────

    @Test fun fenceKeepsTheRawBodyAndTheInfoString() {
        val blocks = parseMarkdown("```ts\nconst a = 1;\n  indented\n```")
        assertEquals(listOf(MdBlock.Code("const a = 1;\n  indented", "ts")), blocks)
    }

    @Test fun fenceWithoutInfoStringHasNoLang() {
        assertEquals(listOf(MdBlock.Code("x", null)), parseMarkdown("```\nx\n```"))
        assertEquals(listOf(MdBlock.Code("x", null)), parseMarkdown("```   \nx\n```"))
    }

    @Test fun anUnclosedFenceRunsToTheEndWhileStreaming() {
        assertEquals(listOf(MdBlock.Code("line 1\nline 2", "py")), parseMarkdown("```py\nline 1\nline 2"))
    }

    @Test fun aFourBacktickFenceIsClosedOnlyByFourOrMore() {
        val src = "````markdown\n```inner```\n```\nstill code\n````\nafter"
        assertEquals(
            listOf(MdBlock.Code("```inner```\n```\nstill code", "markdown"), p(t("after"))),
            parseMarkdown(src),
        )
    }

    @Test fun aCloserMayCarryWhitespaceButNoText() {
        assertEquals(listOf(MdBlock.Code("a\n``` no", null)), parseMarkdown("```\na\n``` no"))
        assertEquals(listOf(MdBlock.Code("a", null), p(t("b"))), parseMarkdown("```\na\n   ```   \nb"))
    }

    @Test fun codeInsideAFenceIsNeverParsedAsMarkdown() {
        val blocks = parseMarkdown("```\n# not a heading\n**not bold** | a | b |\n```")
        assertEquals(listOf(MdBlock.Code("# not a heading\n**not bold** | a | b |", null)), blocks)
    }

    @Test fun anIndentedFenceStillOpens() {
        assertEquals(listOf(MdBlock.Code("x", "sh")), parseMarkdown("   ```sh\nx\n```"))
    }

    // ── Blank lines, rules (300-311) ─────────────────────────────────────────────────────────

    @Test fun blankLinesSeparateBlocksAndProduceNothing() {
        assertEquals(listOf(p(t("a")), p(t("b"))), parseMarkdown("a\n\n \t\nb"))
        assertEquals(emptyList<MdBlock>(), parseMarkdown(""))
    }

    @Test fun horizontalRulesAreThreeOrMoreOfOneMarker() {
        for (rule in listOf("---", "***", "___", "- - -", " *  *  * ", "-----")) {
            assertEquals(rule, listOf(MdBlock.Rule), parseMarkdown(rule))
        }
        // Mixed markers or only two are not rules; "--" is a paragraph.
        assertEquals(listOf(p(t("-*-"))), parseMarkdown("-*-"))
        assertEquals(listOf(p(t("--"))), parseMarkdown("--"))
    }

    // ── Headings (313-321): h{min(level+2,6)} ────────────────────────────────────────────────

    @Test fun atxHeadingsShiftDownTwoLevels() {
        val blocks = parseMarkdown("# One\n## Two\n### Three\n#### Four\n###### Six")
        assertEquals(listOf(3, 4, 5, 6, 6), blocks.map { (it as MdBlock.Heading).tag })
        assertEquals(listOf(1, 2, 3, 4, 6), blocks.map { (it as MdBlock.Heading).level })
        assertEquals(t("One"), (blocks[0] as MdBlock.Heading).inlines)
    }

    @Test fun aHeadingNeedsASpaceAndAtMostSixHashes() {
        assertEquals(listOf(p(t("#tag"))), parseMarkdown("#tag"))
        assertEquals(listOf(p(t("####### seven"))), parseMarkdown("####### seven"))
        // Leading whitespace: not a heading (the regex is anchored at column 0).
        assertEquals(listOf(p(t(" # x"))), parseMarkdown(" # x"))
    }

    @Test fun headingTextIsInlineParsed() {
        val h = parseMarkdown("## Use `x` **now**").single() as MdBlock.Heading
        assertEquals(listOf(Text("Use "), Code("x"), Text(" "), Strong(t("now"))), h.inlines)
    }

    // ── Blockquote (323-329) ─────────────────────────────────────────────────────────────────

    @Test fun consecutiveQuoteLinesBecomeOneParagraphWithSoftBreaks() {
        val q = parseMarkdown("> one\n>two\n  >  three\nafter")
        assertEquals(
            listOf(MdBlock.Quote(p(t("one"), t("two"), t(" three"))), p(t("after"))),
            q,
        )
    }

    // ── Lists (331-349): flat, the browser numbers from 1 ────────────────────────────────────

    @Test fun orderedListItemsDropTheirNumbers() {
        assertEquals(listOf(MdBlock.OrderedList(listOf(t("a"), t("b")))), parseMarkdown("1. a\n7. b"))
    }

    @Test fun bulletsAcceptDashStarAndPlus() {
        assertEquals(listOf(MdBlock.BulletList(listOf(t("a"), t("b"), t("c")))), parseMarkdown("- a\n* b\n+ c"))
    }

    @Test fun nestedListsFlattenAndSplitAnOrderedList() {
        // The seeded web scenario: the indented bullets end the ol; "3." starts a new ol at 1.
        val blocks = parseMarkdown("1. a\n2. b\n   - x\n   - y\n3. c")
        assertEquals(
            listOf(
                MdBlock.OrderedList(listOf(t("a"), t("b"))),
                MdBlock.BulletList(listOf(t("x"), t("y"))),
                MdBlock.OrderedList(listOf(t("c"))),
            ),
            blocks,
        )
    }

    @Test fun taskListMarkersStayLiteralText() {
        // markdown.tsx has no task-list support: `[x]` / `[ ]` are part of the item's text.
        val list = parseMarkdown("- [x] done\n- [ ] todo").single() as MdBlock.BulletList
        assertEquals(listOf(t("[x] done"), t("[ ] todo")), list.items)
    }

    @Test fun aListMarkerNeedsFollowingWhitespace() {
        assertEquals(listOf(p(t("-dash"))), parseMarkdown("-dash"))
        assertEquals(listOf(p(t("1.5 litres"))), parseMarkdown("1.5 litres"))
    }

    // ── GFM tables (226-388) ─────────────────────────────────────────────────────────────────

    @Test fun tableReadsHeaderAlignmentsAndBodyRows() {
        val src = "| Area | Before | After | Delta |\n| --- | ---: | ---: | :---: |\n| Cold start | 1.8 s | 0.9 s | -50% |\n| Bundle | 412 kB | 388 kB | -6% |"
        val table = parseMarkdown(src).single() as MdBlock.Table
        assertEquals(listOf("Area", "Before", "After", "Delta"), table.headers.map { it.plainText() })
        assertEquals(listOf(null, MdAlign.Right, MdAlign.Right, MdAlign.Center), table.aligns)
        assertEquals(listOf("Cold start", "1.8 s", "0.9 s", "-50%"), table.rows[0].map { it.plainText() })
        assertEquals(2, table.rows.size)
    }

    @Test fun leftAlignAndOptionalOuterPipes() {
        val table = parseMarkdown("a | b\n:-- | -\n1 | 2").single() as MdBlock.Table
        assertEquals(listOf(MdAlign.Left, null), table.aligns)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.plainText() })
    }

    @Test fun rowsArePaddedOrCutToTheHeaderWidth() {
        val table = parseMarkdown("|a|b|c|\n|-|-|-|\n|1|\n|1|2|3|4|").single() as MdBlock.Table
        assertEquals(listOf("1", "", ""), table.rows[0].map { it.plainText() })
        assertEquals(listOf("1", "2", "3"), table.rows[1].map { it.plainText() })
    }

    @Test fun anEscapedPipeStaysInsideItsCell() {
        assertEquals(listOf("a|b", "c"), splitTableRow("| a\\|b | c |"))
        val table = parseMarkdown("|x|y|\n|-|-|\n|a\\|b|c|").single() as MdBlock.Table
        assertEquals("a|b", table.rows[0][0].plainText())
    }

    @Test fun cellsAreInlineParsed() {
        val table = parseMarkdown("|h|\n|-|\n|**b** `c`|").single() as MdBlock.Table
        assertEquals(listOf(Strong(t("b")), Text(" "), Code("c")), table.rows[0][0])
    }

    @Test fun aTableEndsAtABlankOrPipelessLine() {
        val blocks = parseMarkdown("|a|\n|-|\n|1|\nplain\n|2|")
        assertEquals(1, (blocks[0] as MdBlock.Table).rows.size)
        assertEquals(p(t("plain"), t("|2|")), blocks[1])
    }

    @Test fun aLonePipeLineOrAMissingDelimiterStaysText() {
        assertEquals(listOf(p(t("a | b"), t("c | d"))), parseMarkdown("a | b\nc | d"))
        assertFalse(isTableDelimiterRow("---"))
        assertFalse(isTableDelimiterRow("| a | - |"))
        assertTrue(isTableDelimiterRow("|:-:|"))
    }

    // ── Paragraphs (390-404) ─────────────────────────────────────────────────────────────────

    @Test fun paragraphsKeepSingleNewlinesAsSoftBreaks() {
        assertEquals(listOf(p(t("one"), t("two"))), parseMarkdown("one\ntwo"))
    }

    @Test fun aBlockStarterEndsAParagraph() {
        val blocks = parseMarkdown("text\n# H\ntext\n- item\ntext\n> q\ntext\n---\ntext\n```\nc\n```")
        assertEquals(
            listOf("Paragraph", "Heading", "Paragraph", "BulletList", "Paragraph", "Quote", "Paragraph", "Rule", "Paragraph", "Code"),
            blocks.map { it::class.simpleName },
        )
    }

    @Test fun crlfIsNormalised() {
        assertEquals(listOf(p(t("a"), t("b")), p(t("c"))), parseMarkdown("a\r\nb\r\n\r\nc"))
    }

    @Test fun aLoneCrAfterAHeadingDoesNotHang() {
        // JS `.` cannot cross "\r", so "# x\r" is not a heading, yet it starts one — the web
        // would loop forever; the port consumes it as one paragraph line.
        assertEquals(listOf(p(t("# x\r"))), parseMarkdown("# x\r"))
    }

    // ── Inline (150-213) ─────────────────────────────────────────────────────────────────────

    @Test fun boldItalicCodeAndLinks() {
        val nodes = parseInline("The **parity** pass, a *summary* with `inline code`, a [link](https://example.test/docs).")
        assertEquals(
            listOf(
                Text("The "), Strong(t("parity")), Text(" pass, a "), Em(t("summary")), Text(" with "),
                Code("inline code"), Text(", a "), Link("https://example.test/docs", t("link")), Text("."),
            ),
            nodes,
        )
    }

    @Test fun underscoreFormsAreBoldAndItalicToo() {
        assertEquals(listOf(Strong(t("b")), Text(" "), Em(t("i"))), parseInline("__b__ _i_"))
    }

    @Test fun codeWinsSoMarkersInsideItAreLiteral() {
        assertEquals(listOf(Code("**x** [a](http://b)")), parseInline("`**x** [a](http://b)`"))
    }

    @Test fun theEarliestMatchWinsAndTiesGoToTheEarlierRule() {
        // `[` at 0 beats `**` at 1; inside the label, bold is parsed recursively.
        assertEquals(listOf(Link("https://x.test", listOf(Strong(t("b"))))), parseInline("[**b**](https://x.test)"))
        // Bold vs italic at the same index: bold (rule 3) is tried before italic (rule 4).
        assertEquals(listOf(Strong(t("x"))), parseInline("**x**"))
    }

    @Test fun boldCanContainItalic() {
        assertEquals(listOf(Strong(listOf(Text("a "), Em(t("b")), Text(" c")))), parseInline("**a *b* c**"))
    }

    @Test fun unsafeLinkSchemesDegradeToTheirLabel() {
        assertEquals(listOf(Span(t("click"))), parseInline("[click](javascript:alert(1))".replace("(1)", "")))
        assertEquals(listOf(Span(t("d"))), parseInline("[d](data:text/html,x)"))
        assertEquals(listOf(Span(t("rel"))), parseInline("[rel](/docs)"))
        assertEquals(listOf(Link("MAILTO:a@b.test", t("m"))), parseInline("[m](MAILTO:a@b.test)"))
        assertEquals(listOf(Link("HTTP://X.TEST", t("u"))), parseInline("[u](HTTP://X.TEST)"))
    }

    @Test fun aLinkUrlCannotContainWhitespaceOrAClosingParen() {
        assertEquals(t("[a](http://x y)"), parseInline("[a](http://x y)"))
    }

    @Test fun strikethroughIsNotMarkdownHere() {
        // markdown.tsx has no `~~` rule: the tildes render as typed (web reference screenshot).
        assertEquals(t("~~a retired idea~~"), parseInline("~~a retired idea~~"))
    }

    @Test fun danglingMarkersStayLiteralWhileStreaming() {
        assertEquals(t("**unclosed"), parseInline("**unclosed"))
        assertEquals(t("`open"), parseInline("`open"))
        assertEquals(t("[label](http://x"), parseInline("[label](http://x"))
    }

    @Test fun htmlIsTextNeverMarkup() {
        assertEquals(t("<script>alert(1)</script> <img onerror=x>"), parseInline("<script>alert(1)</script> <img onerror=x>"))
    }

    @Test fun overTheScanLimitALineIsOneLiteralLeaf() {
        val long = "**b** " + "x".repeat(INLINE_SCAN_LIMIT)
        assertEquals(listOf(Text(long)), parseInline(long))
        val atLimit = "**b**" + "x".repeat(INLINE_SCAN_LIMIT - 5)
        assertEquals(Strong(t("b")), parseInline(atLimit).first())
    }

    @Test fun theTokenGuardStopsAt5000MatchesAndKeepsTheRestLiteral() {
        val src = "`a`".repeat(5001)
        val nodes = parseInline(src)
        assertEquals(5001, nodes.size)
        assertEquals(Text("`a`"), nodes.last())
    }

    // ── The seeded long-markdown scenario, end to end ────────────────────────────────────────

    @Test fun theSeededReleaseNotesParseLikeTheWeb() {
        val blocks = parseMarkdown(ChatFixtures.LONG_MARKDOWN)
        assertEquals(
            listOf(
                "Heading", "Paragraph", "Heading", "OrderedList", "BulletList", "OrderedList", "Heading", "BulletList",
                "Heading", "Table", "Heading", "Code", "Code", "Quote", "Rule", "Paragraph",
            ),
            blocks.map { it::class.simpleName },
        )
        val intro = (blocks[1] as MdBlock.Paragraph).lines.single()
        assertTrue(intro.contains(Text(" and ~~a retired idea~~.")))
        assertEquals(MdBlock.Code("npm run test:unit && npm run lint", "bash"), blocks[12])
        assertEquals(3, ((blocks[7]) as MdBlock.BulletList).items.size)
    }
}
