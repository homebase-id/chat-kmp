package id.homebase.api.client.drives.files

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.markdownToPlainPreview
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextToMarkdownTest {

    private fun leaf(text: String, bold: Boolean? = null, italic: Boolean? = null, strike: Boolean? = null,
                     code: Boolean? = null, underline: Boolean? = null, kbd: Boolean? = null) =
        RichTextNode(text = text, bold = bold, italic = italic, strikethrough = strike, code = code,
            underline = underline, kbd = kbd)

    private fun el(type: String, vararg children: RichTextNode, url: String? = null, value: String? = null) =
        RichTextNode(type = type, children = children.toList(), url = url, value = value)

    private fun p(vararg children: RichTextNode) = el("p", *children)
    private fun p(text: String) = p(leaf(text))

    @Test
    fun empty_array_is_empty_string() {
        assertEquals("", richTextToMarkdown(emptyList()))
    }

    @Test
    fun paragraphs_are_separated_by_blank_line() {
        assertEquals("one\n\ntwo", richTextToMarkdown(listOf(p("one"), p("two"))))
    }

    @Test
    fun headings() {
        assertEquals("# Big\n\n## Medium", richTextToMarkdown(listOf(el("h1", leaf("Big")), el("h2", leaf("Medium")))))
    }

    @Test
    fun marks() {
        assertEquals("**b**", richTextToMarkdown(listOf(p(leaf("b", bold = true)))))
        assertEquals("*i*", richTextToMarkdown(listOf(p(leaf("i", italic = true)))))
        assertEquals("~~s~~", richTextToMarkdown(listOf(p(leaf("s", strike = true)))))
        assertEquals("`c`", richTextToMarkdown(listOf(p(leaf("c", code = true)))))
        assertEquals("`k`", richTextToMarkdown(listOf(p(leaf("k", kbd = true)))))
        assertEquals("***bi***",richTextToMarkdown(listOf(p(leaf("bi", bold = true, italic = true)))))
    }

    @Test
    fun underline_is_dropped_text_kept() {
        assertEquals("under", richTextToMarkdown(listOf(p(leaf("under", underline = true)))))
    }

    @Test
    fun inline_code_with_backtick_uses_longer_run() {
        assertEquals("``a`b``", richTextToMarkdown(listOf(p(leaf("a`b", code = true)))))
    }

    @Test
    fun whitespace_moves_outside_marks() {
        assertEquals("a **b** c", richTextToMarkdown(listOf(p(leaf("a "), leaf(" b ", bold = true), leaf("c")))).replace("  ", " "))
    }

    @Test
    fun adjacent_same_marks_merge() {
        assertEquals("**ab**", richTextToMarkdown(listOf(p(leaf("a", bold = true), leaf("b", bold = true)))))
    }

    @Test
    fun blockquote_prefixes_every_line() {
        assertEquals("> one\n>\n> two", richTextToMarkdown(listOf(el("blockquote", p("one"), p("two")))))
    }

    @Test
    fun code_block_joins_lines_without_escaping() {
        val block = el("code_block", el("code_line", leaf("val a = *b*")), el("code_line", leaf("x_y")))
        assertEquals("```\nval a = *b*\nx_y\n```", richTextToMarkdown(listOf(block)))
    }

    @Test
    fun code_block_containing_fence_gets_longer_fence() {
        val block = el("code_block", el("code_line", leaf("```")))
        assertEquals("````\n```\n````", richTextToMarkdown(listOf(block)))
    }

    @Test
    fun bullet_and_ordered_lists() {
        val ul = el("ul", el("li", el("lic", leaf("a"))), el("li", el("lic", leaf("b"))))
        val ol = el("ol", el("li", el("lic", leaf("a"))), el("li", el("lic", leaf("b"))))
        assertEquals("- a\n- b", richTextToMarkdown(listOf(ul)))
        assertEquals("1. a\n2. b", richTextToMarkdown(listOf(ol)))
    }

    @Test
    fun nested_lists_are_indented() {
        val nested = el(
            "ul",
            el("li", el("lic", leaf("top")), el("ul", el("li", el("lic", leaf("inner"))))),
            el("li", el("lic", leaf("next"))),
        )
        assertEquals("- top\n  - inner\n- next", richTextToMarkdown(listOf(nested)))
    }

    @Test
    fun nested_ordered_list_aligns_to_marker_width() {
        val nested = el("ol", el("li", el("lic", leaf("top")), el("ul", el("li", el("lic", leaf("inner"))))))
        assertEquals("1. top\n   - inner", richTextToMarkdown(listOf(nested)))
    }

    @Test
    fun link_and_a_nodes() {
        assertEquals("[site](https://a.example/x)", richTextToMarkdown(listOf(p(el("a", leaf("site"), url = "https://a.example/x")))))
        assertEquals("[site](https://a.example)", richTextToMarkdown(listOf(p(el("link", leaf("site"), url = "https://a.example")))))
    }

    @Test
    fun mark_spanning_a_link() {
        val md = richTextToMarkdown(
            listOf(p(leaf("see ", bold = true), el("a", leaf("here", bold = true), url = "https://a.example"), leaf(" now", bold = true)))
        )
        assertEquals("**see** [**here**](https://a.example) **now**", md)
    }

    @Test
    fun mention_stays_plain_at_odin_id() {
        val md = richTextToMarkdown(listOf(p(leaf("hi "), el("mention", leaf(""), value = "a.b"), leaf(" there"))))
        assertEquals("hi @a.b there", md)
        assertEquals("hi @a.b there", markdownToPlainPreview(md, 100))
    }

    @Test
    fun local_image_dropped_and_unknown_keeps_children_text() {
        assertEquals("after", richTextToMarkdown(listOf(el("local_image", leaf("")), p("after"))))
        assertEquals("kept", richTextToMarkdown(listOf(p(el("weird", leaf("kept"))))))
    }

    @Test
    fun literal_markdown_characters_round_trip_to_same_plain_text() {
        val samples = listOf(
            "*not bold*", "snake_case_name", "a `tick` b", "~~no~~", "[x](y)", "# not heading",
            "> not quote", "- not list", "1. not list", "cost \$5 and \$6", "back\\slash",
        )
        for (s in samples) {
            val md = richTextToMarkdown(listOf(p(s)))
            assertEquals(s, markdownToPlainPreview(md, 200), "markdown was: $md")
        }
    }

    @Test
    fun plate_json_fixture_decodes_and_converts() {
        val json = """
        [
          {"type":"h1","children":[{"text":"Trip plan"}]},
          {"type":"p","children":[{"text":"Hi "},{"type":"mention","value":"sam.gamgee.example","children":[{"text":""}]},{"text":", see "},{"type":"a","url":"https://shire.example/map","children":[{"text":"the map","bold":true}]},{"text":" and ","italic":true},{"text":"don't","underline":true},{"text":" forget","strikethrough":true}]},
          {"type":"ul","children":[{"type":"li","children":[{"type":"lic","children":[{"text":"bread"}]}]},{"type":"li","children":[{"type":"lic","children":[{"text":"cheese"}]},{"type":"ul","children":[{"type":"li","children":[{"type":"lic","children":[{"text":"aged"}]}]}]}]}]},
          {"type":"blockquote","children":[{"text":"quote me"}]},
          {"type":"code_block","children":[{"type":"code_line","children":[{"text":"run()"}]}]},
          {"type":"local_image","fileKey":"abc","children":[{"text":""}]}
        ]
        """.trimIndent()
        val nodes = OdinSystemSerializer.json.decodeFromJsonElement<RichText>(Json.parseToJsonElement(json))
        assertEquals(
            "# Trip plan\n\n" +
                "Hi @sam.gamgee.example, see [**the map**](https://shire.example/map) *and* don't ~~forget~~\n\n" +
                "- bread\n- cheese\n  - aged\n\n> quote me\n\n```\nrun()\n```",
            richTextToMarkdown(nodes),
        )
    }

    @Test
    fun bold_then_bold_italic_keeps_bold_open() {
        assertEquals(
            "**a*b***",
            richTextToMarkdown(listOf(p(leaf("a", bold = true), leaf("b", bold = true, italic = true)))),
        )
    }

    @Test
    fun bold_code_then_bold_keeps_bold_open() {
        assertEquals(
            "**`x`y**",
            richTextToMarkdown(listOf(p(leaf("x", bold = true, code = true), leaf("y", bold = true)))),
        )
    }

    @Test
    fun italic_inside_a_word_uses_asterisks() {
        assertEquals("un*bel*ievable", richTextToMarkdown(listOf(p(leaf("un"), leaf("bel", italic = true), leaf("ievable")))))
    }

    @Test
    fun setext_underline_and_table_rows_are_escaped() {
        assertEquals("title\n\\---", richTextToMarkdown(listOf(p("title\n---"))))
        assertEquals("title\n\\===", richTextToMarkdown(listOf(p("title\n==="))))
        assertEquals("\\| a \\|\n\\| --- \\|", richTextToMarkdown(listOf(p("| a |\n| --- |"))))
    }

    @Test
    fun line_start_survives_a_whitespace_run_with_other_marks() {
        assertEquals("\\# x", richTextToMarkdown(listOf(p(leaf(" ", bold = true), leaf("# x")))))
        assertEquals("**a**\n \\- b", richTextToMarkdown(listOf(p(leaf("a", bold = true), leaf("\n ", italic = true), leaf("- b")))))
    }

    @Test
    fun html_and_entity_text_is_escaped() {
        assertEquals("\\<br\\> \\&amp;", richTextToMarkdown(listOf(p("<br> &amp;"))))
        assertEquals("<br> &amp;", markdownToPlainPreview(richTextToMarkdown(listOf(p("<br> &amp;"))), 100))
    }

    @Test
    fun bang_before_a_link_does_not_make_an_image() {
        assertEquals(
            "Wow\\![site](https://a.example)",
            richTextToMarkdown(listOf(p(leaf("Wow!"), el("a", leaf("site"), url = "https://a.example")))),
        )
    }

    @Test
    fun heading_ending_in_hashes_keeps_them() {
        assertEquals("# Issue \\#", richTextToMarkdown(listOf(el("h1", leaf("Issue #")))))
        assertEquals("# C#", richTextToMarkdown(listOf(el("h1", leaf("C#")))))
    }

    @Test
    fun newline_inside_inline_code_becomes_space() {
        assertEquals("`a # b`", richTextToMarkdown(listOf(p(leaf("a\n# b", code = true)))))
    }
}
