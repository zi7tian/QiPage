package local.readapp.core

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Full-text search over an already-open book.
 *
 * Lives in `core` so it works against the [TextContent] / [EpubContent]
 * interfaces and stays testable on the JVM without a device.
 *
 * Matching is a plain case-insensitive substring scan. No regular expressions
 * run against book text, so a pathological query cannot blow up the reader.
 */
data class SearchHit(val locator: Locator, val chapter: String, val snippet: String)

/** Progress of a running search. [hits] grows as chapters are scanned. */
data class SearchProgress(val scanned: Int, val total: Int, val hits: List<SearchHit>, val done: Boolean)

private const val SNIPPET_PAD = 24

/**
 * A window of book text with a parallel map back to source offsets, so a match
 * found in the flattened text can still be turned into a locator.
 *
 * Chapters are flattened by concatenating their text runs with a single "\n"
 * between runs; the newline is mapped to the start of the following run.
 */
private class FlatText {
    val text = StringBuilder()
    private val flatStarts = ArrayList<Int>()
    private val docStarts = ArrayList<Int>()

    fun append(chunk: String, docOffset: Int) {
        if (chunk.isEmpty()) return
        flatStarts.add(text.length)
        docStarts.add(docOffset)
        text.append(chunk)
    }

    /** Separator between two runs; maps onto the next run's document offset. */
    fun separator(nextDocOffset: Int) {
        if (text.isEmpty()) return
        flatStarts.add(text.length)
        docStarts.add(nextDocOffset)
        text.append('\n')
    }

    /** Document offset for a flattened index, or -1 when out of range. */
    fun docOffsetAt(index: Int): Int {
        if (flatStarts.isEmpty()) return -1
        var lo = 0
        var hi = flatStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (flatStarts[mid] <= index) lo = mid else hi = mid - 1
        }
        return docStarts[lo] + (index - flatStarts[lo])
    }
}

/** Collapse whitespace and clip a window around a match for display. */
internal fun snippetAround(source: String, start: Int, length: Int): String {
    if (source.isEmpty()) return ""
    val from = (start - SNIPPET_PAD).coerceAtLeast(0)
    val to = (start + length + SNIPPET_PAD).coerceAtMost(source.length)
    val body = source.substring(from, to).replace(Regex("\\s+"), " ").trim()
    return buildString {
        if (from > 0) append('…')
        append(body)
        if (to < source.length) append('…')
    }
}

private fun htmlUnescape(value: String): String {
    if ('&' !in value) return value
    return value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}

/**
 * The sanitizer emits every text run as
 * `<span data-read="offset" id="read-offset">text</span>`, which gives us both
 * the plain text and its document offset in one pass. Match any attributes
 * between the offset and the closing bracket: the exact attribute list is the
 * sanitizer's business, not ours.
 */
private val TEXT_RUN = Regex("<span data-read=\"(\\d+)\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)

/**
 * Block-level tags. Two text runs separated by one of these are in different
 * blocks and must not be glued together, or a query would match across a
 * paragraph boundary. Runs separated only by inline markup (the gap is just
 * `</strong>`, `</em>` and friends) are genuinely contiguous text.
 */
private val BLOCK_TAG = Regex(
    "</?(?:p|div|h[1-6]|li|dt|dd|blockquote|section|article|table|ul|ol|dl|tr|td|th|pre|figure|figcaption)\\b",
    RegexOption.IGNORE_CASE,
)

/** Flatten sanitized chapter markup into searchable text plus an offset map. */
private fun flattenChapter(html: String): FlatText {
    val flat = FlatText()
    var cursor = 0
    for (run in TEXT_RUN.findAll(html)) {
        val docOffset = run.groupValues[1].toIntOrNull() ?: continue
        val plain = htmlUnescape(run.groupValues[2])
        if (plain.isNotEmpty()) {
            if (flat.text.isNotEmpty() && BLOCK_TAG.containsMatchIn(html.substring(cursor, run.range.first))) {
                flat.separator(docOffset)
            }
            flat.append(plain, docOffset)
        }
        cursor = run.range.last + 1
    }
    return flat
}

/**
 * Search the whole of a TXT book.
 *
 * [TextContent.readBlock] performs blocking disk access, so call this from an
 * IO dispatcher.
 */
suspend fun searchTextContent(
    content: TextContent,
    query: String,
    limit: Int = 200,
    onProgress: suspend (SearchProgress) -> Unit = {},
): List<SearchHit> {
    val needle = query.trim()
    if (needle.isEmpty() || limit <= 0) return emptyList()

    val hits = ArrayList<SearchHit>()
    val total = content.blockCount.coerceAtLeast(1)
    val title = "正文"
    // Keep the tail of the previous block so a phrase spanning a block boundary
    // is still found; matches are de-duplicated by absolute offset.
    val carryLength = (needle.length - 1).coerceAtLeast(0)
    var carry = ""
    var carryStart = 0L
    var lastEmitted = -1L

    for (index in 0 until total) {
        coroutineContext.ensureActive()
        val block = content.readBlock(index)
        val base = if (carry.isEmpty()) block.start else carryStart
        val haystack = carry + block.text
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val absolute = base + at
            if (absolute > lastEmitted) {
                lastEmitted = absolute
                hits.add(
                    SearchHit(
                        locator = Locator(format = "txt", offset = absolute),
                        chapter = title,
                        snippet = snippetAround(haystack, at, needle.length),
                    )
                )
                if (hits.size >= limit) {
                    onProgress(SearchProgress(index + 1, total, hits.toList(), true))
                    return hits
                }
            }
            from = at + 1
        }
        if (carryLength > 0) {
            // Tail of everything read so far, so a phrase longer than one block is
            // still found when blocks are shorter than the query.
            carry = if (haystack.length > carryLength) haystack.takeLast(carryLength) else haystack
            carryStart = base + haystack.length - carry.length
        }
        onProgress(SearchProgress(index + 1, total, hits.toList(), false))
    }
    onProgress(SearchProgress(total, total, hits.toList(), true))
    return hits
}

/**
 * Search the whole of an EPUB.
 *
 * Every spine item is sanitised and flattened, which is why results are
 * streamed through [onProgress]: on a thousand-chapter book this takes a
 * noticeable amount of time and the user must be able to read hits, see
 * progress and cancel.
 */
suspend fun searchEpubContent(
    epub: EpubContent,
    query: String,
    limit: Int = 200,
    onProgress: suspend (SearchProgress) -> Unit = {},
): List<SearchHit> {
    val needle = query.trim()
    val spine = epub.chapters
    if (needle.isEmpty() || limit <= 0 || spine.isEmpty()) return emptyList()

    val hits = ArrayList<SearchHit>()
    val titles = epub.toc.associate { it.locator.href to it.title }

    spine.forEachIndexed { index, item ->
        coroutineContext.ensureActive()
        val html = runCatching { epub.chapter(item.path) }.getOrNull()
        if (html != null) {
            val flat = flattenChapter(html)
            val haystack = flat.text.toString()
            var from = 0
            while (true) {
                val at = haystack.indexOf(needle, from, ignoreCase = true)
                if (at < 0) break
                val docOffset = flat.docOffsetAt(at)
                if (docOffset >= 0) {
                    hits.add(
                        SearchHit(
                            locator = Locator(format = "epub", href = item.path, offset = docOffset.toLong()),
                            chapter = titles[item.path] ?: item.title,
                            snippet = snippetAround(haystack, at, needle.length),
                        )
                    )
                }
                if (hits.size >= limit) {
                    onProgress(SearchProgress(index + 1, spine.size, hits.toList(), true))
                    return hits
                }
                from = at + 1
            }
        }
        onProgress(SearchProgress(index + 1, spine.size, hits.toList(), false))
    }
    onProgress(SearchProgress(spine.size, spine.size, hits.toList(), true))
    return hits
}
