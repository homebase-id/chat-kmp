package id.homebase.chat.widget.bench

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import id.homebase.chat.widget.ChatMarkdownParseCache
import id.homebase.chat.widget.ParsedChatMarkdown
import id.homebase.chat.widget.bubbleCodeStyle
import id.homebase.chat.widget.highlightCode
import id.homebase.chat.widget.parseChatMarkdown
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test

/** Skipped unless `CHAT_UI_BENCH=1`. See OPTIMIZE.md. */
class ChatMarkdownPerfBench {

    private val runs = System.getenv("CHAT_UI_BENCH_RUNS")?.toInt() ?: 15

    private fun requireBench() =
        assumeTrue("Set CHAT_UI_BENCH=1 to run the chat UI benchmarks", System.getenv("CHAT_UI_BENCH") == "1")

    // Rendering touches one of the two lazies; the cache keeps whichever was built.
    private fun ParsedChatMarkdown.touch(): Any? = if (hasBlockElements) blockState else inlineNode

    @Test
    fun markdownParse() {
        requireBench()
        val kinds = MarkdownFixtures.kinds()
        val conv50 = MarkdownFixtures.conversation(50, seed = 50)
        val conv100 = MarkdownFixtures.conversation(100, seed = 100)
        repeat(2_000) { i -> conv100.forEach { if (i % 20 == 0) parseChatMarkdown(it).touch() } }
        repeat(300) { conv100.forEach { parseChatMarkdown(it).touch() } }

        val rows = mutableListOf<List<String>>()
        kinds.forEach { (kind, body) ->
            val us = medianNanos(runs) { repeat(200) { parseChatMarkdown(body).touch() } } / 200 / 1_000.0
            rows += listOf("1 message: $kind (${body.length} chars)", fmt(us, "µs"), "")
        }
        for ((label, conv) in listOf("50" to conv50, "100" to conv100)) {
            val cold = medianNanos(runs) { conv.forEach { parseChatMarkdown(it).touch() } } / 1e6
            val cache = ChatMarkdownParseCache()
            conv.forEach { cache.get(it).touch() }
            val hit = medianNanos(runs) { repeat(100) { conv.forEach { cache.get(it).touch() } } } / 100 / 1e6
            rows += listOf("$label-message conversation, cold (no cache)", fmt(cold, "ms"), "${conv.sumOf { it.length }} chars")
            rows += listOf("$label-message conversation, all cache hits", fmt(hit, "ms"), "")
        }
        report("parseChatMarkdown, median of $runs runs", listOf("case", "time", "note"), rows)
    }

    @Test
    fun parseCacheMemory() {
        requireBench()
        val caches = 8
        val rows = mutableListOf<List<String>>()
        for ((label, gen) in listOf<Pair<String, (Int) -> String>>(
            "typical short messages" to { i -> MarkdownFixtures.typical(Random(i)) },
            "long messages (~4 KB)" to { i -> MarkdownFixtures.long(Random(i)) },
        )) {
            val bodies = List(caches) { c -> List(ChatMarkdownParseCache.DEFAULT_MAX_ENTRIES) { gen(c * 10_000 + it) } }
            val avgChars = bodies.flatten().map { it.length }.average()
            val samples = List(5) {
                val before = usedHeap()
                val held = List(caches) { c ->
                    ChatMarkdownParseCache().also { cache -> bodies[c].forEach { cache.get(it).touch() } }
                }
                val after = usedHeap()
                check(held.all { it.size == ChatMarkdownParseCache.DEFAULT_MAX_ENTRIES })
                (after - before) / caches
            }.sorted()
            val stringsBefore = usedHeap()
            val copies = bodies.map { list -> list.map { String(it.toCharArray()) } }
            val stringBytes = (usedHeap() - stringsBefore) / caches
            check(copies.size == caches)
            rows += listOf(
                label,
                "${"%.0f".format(avgChars)}",
                mib(samples[2]),
                "${mib(samples.first())} – ${mib(samples.last())}",
                mib(stringBytes),
            )
        }
        report(
            "ChatMarkdownParseCache retained heap, 256 entries (GC + used-heap delta, $caches caches per sample, 5 samples)",
            listOf("content", "avg chars", "per cache (median)", "range", "of which the message strings"),
            rows,
        )
    }

    @Test
    fun codeHighlight() {
        requireBench()
        val theme = bubbleCodeStyle(lightColorScheme(), Color(0xFF1B1B1F), Color(0xFFE8E0F0)).theme
        val small = MarkdownFixtures.kotlinCode(10)
        val large = MarkdownFixtures.kotlinCode(200)
        repeat(500) { highlightCode(small, "kotlin", theme); if (it % 10 == 0) highlightCode(large, "kotlin", theme) }
        val rows = mutableListOf<List<String>>()
        for ((label, code, reps) in listOf(Triple("10 lines", small, 200), Triple("200 lines", large, 10))) {
            val ms = medianNanos(runs) { repeat(reps) { highlightCode(code, "kotlin", theme) } } / reps / 1e6
            rows += listOf("$label kotlin (${code.length} chars)", fmt(ms, "ms"))
        }
        val plain = medianNanos(runs) { repeat(200) { highlightCode(large, null, theme) } } / 200 / 1e6
        rows += listOf("200 lines, no fence language (no tokenising)", fmt(plain, "ms"))
        report("highlightCode per block, median of $runs runs", listOf("block", "time"), rows)
    }
}

internal fun usedHeap(): Long {
    val rt = Runtime.getRuntime()
    var last = Long.MAX_VALUE
    repeat(6) {
        System.gc()
        Thread.sleep(40)
        val used = rt.totalMemory() - rt.freeMemory()
        if (used >= last && it >= 2) return used
        last = used
    }
    return rt.totalMemory() - rt.freeMemory()
}

internal inline fun medianNanos(runs: Int, block: () -> Unit): Double {
    val samples = LongArray(runs) {
        val t = System.nanoTime()
        block()
        System.nanoTime() - t
    }
    samples.sort()
    return samples[runs / 2].toDouble()
}

private fun fmt(v: Double, unit: String) = "%.3f %s".format(v, unit)
private fun mib(bytes: Long) = "%.2f MiB".format(bytes / 1_048_576.0)

internal fun report(title: String, header: List<String>, rows: List<List<String>>) {
    val sb = StringBuilder("\n### $title\n")
    sb.append("JVM ${System.getProperty("java.vm.vendor")} ${System.getProperty("java.version")}, ")
        .append("${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ")
        .append("${Runtime.getRuntime().availableProcessors()} cpus\n\n")
    sb.append(header.joinToString(" | ", "| ", " |\n"))
    sb.append(header.joinToString("|", "|", "|\n") { "---" })
    rows.forEach { sb.append(it.joinToString(" | ", "| ", " |\n")) }
    println(sb)
}

internal object MarkdownFixtures {
    private val words = ("ok sure lunch tomorrow sounds good meeting later the build is green again " +
        "did you see that thanks will check send me the file when you can on my way").split(' ')
    private val emoji = listOf("😀", "👍", "🎉", "❤️", "😂", "🙏", "🔥", "👨‍👩‍👧")
    private val handles = listOf("@frodo.baggins.demo.rocks", "@samwise.gamgee.demo.rocks", "@merry.demo.rocks")

    private fun Random.sentence(n: Int) = List(n) { words[nextInt(words.size)] }.joinToString(" ")

    fun plain(r: Random) = r.sentence(r.nextInt(2, 12))
    fun emojiMsg(r: Random) = "${r.sentence(r.nextInt(2, 6))} ${emoji[r.nextInt(emoji.size)]}${emoji[r.nextInt(emoji.size)]}"
    fun mention(r: Random) = "${handles[r.nextInt(handles.size)]} ${r.sentence(r.nextInt(3, 9))}"
    fun link(r: Random) = "${r.sentence(3)} https://example.com/path/${r.nextInt(1000)}?q=${r.nextInt()} ${r.sentence(2)}"
    fun inlineFormat(r: Random) = "**${r.sentence(2)}** and _${r.sentence(2)}_ with `code` ${r.sentence(3)}"
    fun list(r: Random) = "${r.sentence(3)}:\n" + List(r.nextInt(3, 7)) { "- ${r.sentence(r.nextInt(2, 6))}" }.joinToString("\n")
    fun multiline(r: Random) = List(r.nextInt(2, 5)) { r.sentence(r.nextInt(3, 8)) }.joinToString("\n")
    fun code(r: Random) = "${r.sentence(3)}\n```kotlin\n${kotlinCode(r.nextInt(5, 15))}\n```"
    fun table(r: Random) = "| name | status | count |\n|---|---|---|\n" +
        List(r.nextInt(3, 7)) { "| ${words[r.nextInt(words.size)]} | ${words[r.nextInt(words.size)]} | ${r.nextInt(100)} |" }
            .joinToString("\n")

    fun kinds(): List<Pair<String, String>> {
        val r = Random(7)
        return listOf(
            "plain short" to plain(r), "emoji" to emojiMsg(r), "mention" to mention(r), "link" to link(r),
            "inline bold/italic/code" to inlineFormat(r), "multi-line" to multiline(r), "bullet list" to list(r),
            "code block (~10 lines)" to code(r), "table" to table(r), "long (~4 KB)" to long(r),
        )
    }

    // Weights: mostly short text, a few block messages, as a chat log looks.
    fun typical(r: Random): String = when (r.nextInt(100)) {
        in 0..44 -> plain(r)
        in 45..54 -> emojiMsg(r)
        in 55..62 -> mention(r)
        in 63..70 -> link(r)
        in 71..77 -> inlineFormat(r)
        in 78..86 -> multiline(r)
        in 87..92 -> list(r)
        in 93..96 -> code(r)
        else -> table(r)
    }

    fun conversation(n: Int, seed: Int): List<String> {
        val r = Random(seed)
        return List(n) { typical(r) }
    }

    fun long(r: Random): String {
        val sb = StringBuilder()
        while (sb.length < 4_000) {
            sb.append(
                when (r.nextInt(6)) {
                    0 -> "## ${r.sentence(3)}\n\n"
                    1 -> list(r) + "\n\n"
                    2 -> code(r) + "\n\n"
                    3 -> "${inlineFormat(r)} ${link(r)}\n\n"
                    else -> "${r.sentence(r.nextInt(15, 40))}\n\n"
                }
            )
        }
        return sb.toString().trimEnd()
    }

    fun kotlinCode(lines: Int): String {
        val pool = listOf(
            "fun load(id: String): Result<User> {",
            "    val user = repository.find(id) ?: return Result.failure(NotFound(id))",
            "    // cache the lookup for the next screen",
            "    cache[id] = user.copy(lastSeen = Clock.System.now())",
            "    return Result.success(user)",
            "}",
            "private const val MAX_RETRIES = 3",
            "data class User(val id: String, val name: String, val lastSeen: Instant?)",
            "if (attempts > MAX_RETRIES) error(\"gave up after \$attempts tries\")",
            "listOf(1, 2, 3).map { it * 2 }.filter { it > 2 }.forEach(::println)",
        )
        return List(lines) { pool[it % pool.size] }.joinToString("\n")
    }
}
