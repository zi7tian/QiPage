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
data class SearchHit(
    val locator: Locator,
    val chapter: String,
    val snippet: String,
    /** Length of the match in characters, so the reader can highlight it. */
    val length: Int,
)

/** Progress while the index is being built. */
data class SearchProgress(val scanned: Int, val total: Int, val done: Boolean)

private const val SNIPPET_PAD = 24
private const val DEFAULT_LIMIT = 200

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

/**
 * An immutable, searchable flattening of a whole book.
 *
 * Building the index is the expensive part: for EPUB it has to sanitise every
 * spine item so that match offsets line up with the offsets the renderer uses
 * for navigation. It is therefore built once per book and reused, which is what
 * keeps a query on a two-million-character novel down to a single linear scan
 * (a few milliseconds) instead of re-parsing the book on every search.
 */
class BookSearchIndex private constructor(
    private val text: String,
    /** For each run: where it starts in [text]. */
    private val runStart: IntArray,
    /** For each run: which spine item / chapter it belongs to. */
    private val runChapter: IntArray,
    /** For each run: document offset of `text[runStart]`. */
    private val runDocBase: IntArray,
    private val chapters: List<String>,
    private val hrefs: List<String>,
    /** Indices into [text] where a paragraph or chapter ends. */
    private val breaks: IntArray,
) {
    val characterCount: Int get() = text.length
    val chapterCount: Int get() = chapters.size

    private fun runAt(index: Int): Int {
        var lo = 0
        var hi = runStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (runStart[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun isBlockBoundary(index: Int): Boolean {
        var lo = 0
        var hi = breaks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            when {
                breaks[mid] < index -> lo = mid + 1
                breaks[mid] > index -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    /** One linear scan; returns at most [limit] hits in reading order. */
    fun find(query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0 || text.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        var from = 0
        while (hits.size < limit) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            if (isBlockBoundary(at)) {
                from = at + 1
                continue
            }
            val run = runAt(at)
            val chapter = runChapter[run]
            val docOffset = runDocBase[run] + (at - runStart[run])
            hits.add(
                SearchHit(
                    locator = Locator(
                        format = if (hrefs.isEmpty() || hrefs.all { it.isEmpty() }) "txt" else "epub",
                        href = hrefs.getOrElse(chapter) { "" },
                        offset = docOffset.toLong(),
                    ),
                    chapter = chapters.getOrElse(chapter) { "" },
                    snippet = snippetAround(text, at, needle.length),
                    length = needle.length,
                )
            )
            from = at + 1
        }
        return hits
    }

    private class Builder {
        val text = StringBuilder()
        val runStart = ArrayList<Int>()
        val runChapter = ArrayList<Int>()
        val runDocBase = ArrayList<Int>()
        val breaks = ArrayList<Int>()
        val chapters = ArrayList<String>()
        val hrefs = ArrayList<String>()

        fun append(chunk: String, chapter: Int, docOffset: Int) {
            if (chunk.isEmpty()) return
            runStart.add(text.length)
            runChapter.add(chapter)
            runDocBase.add(docOffset)
            text.append(chunk)
        }

        /**
         * Paragraph boundary. A newline is appended so a query cannot match
         * across two blocks, and the index of that newline is remembered so a
         * query that literally contains a newline is rejected there too.
         */
        fun blockBreak() {
            if (text.isEmpty()) return
            breaks.add(text.length)
            text.append('\n')
        }

        fun build() = BookSearchIndex(
            text.toString(),
            runStart.toIntArray(),
            runChapter.toIntArray(),
            runDocBase.toIntArray(),
            chapters,
            hrefs,
            breaks.toIntArray(),
        )
    }

    companion object {
        /**
         * Flatten a whole TXT book.
         *
         * [TextContent.readBlock] performs blocking disk access, so call this
         * from an IO dispatcher.
         */
        suspend fun buildText(
            content: TextContent,
            onProgress: suspend (SearchProgress) -> Unit = {},
        ): BookSearchIndex {
            val builder = Builder()
            builder.chapters.add("正文")
            builder.hrefs.add("")
            val total = content.blockCount.coerceAtLeast(1)
            for (index in 0 until total) {
                coroutineContext.ensureActive()
                val block = content.readBlock(index)
                // Blocks are arbitrary read windows, not paragraphs, and the text
                // already carries real newlines, so no synthetic separator is added.
                builder.append(block.text, 0, block.start.toInt())
                if (index % 64 == 0) onProgress(SearchProgress(index + 1, total, false))
            }
            onProgress(SearchProgress(total, total, true))
            return builder.build()
        }

        /** Flatten a whole EPUB, sanitising every spine item exactly once. */
        suspend fun buildEpub(
            epub: EpubContent,
            onProgress: suspend (SearchProgress) -> Unit = {},
        ): BookSearchIndex {
            val builder = Builder()
            val spine = epub.chapters
            val titleByHref = epub.toc.associate { it.locator.href to it.title }
            spine.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                builder.chapters.add(titleByHref[item.path] ?: item.title)
                builder.hrefs.add(item.path)
                val html = runCatching { epub.chapter(item.path) }.getOrNull()
                if (html != null) {
                    var cursor = 0
                    var seenRun = false
                    for (run in TEXT_RUN.findAll(html)) {
                        val docOffset = run.groupValues[1].toIntOrNull() ?: continue
                        val plain = htmlUnescape(run.groupValues[2])
                        if (plain.isEmpty()) {
                            cursor = run.range.last + 1
                            continue
                        }
                        // Runs inside one block stay contiguous; a block boundary
                        // in between starts a new paragraph.
                        if (seenRun && BLOCK_TAG.containsMatchIn(html.substring(cursor, run.range.first))) {
                            builder.blockBreak()
                        }
                        builder.append(plain, index, docOffset)
                        seenRun = true
                        cursor = run.range.last + 1
                    }
                }
                // Never let a query run from one chapter into the next.
                builder.blockBreak()
                onProgress(SearchProgress(index + 1, spine.size, false))
            }
            onProgress(SearchProgress(spine.size, spine.size, true))
            return builder.build()
        }
    }
}

/**
 * Convenience wrappers that build a throwaway index and search it once.
 *
 * The reader holds on to a [BookSearchIndex] so it survives between queries;
 * these exist for one-off callers and tests.
 */
suspend fun searchTextContent(
    content: TextContent,
    query: String,
    limit: Int = DEFAULT_LIMIT,
    onProgress: suspend (SearchProgress) -> Unit = {},
): List<SearchHit> = BookSearchIndex.buildText(content, onProgress = onProgress).find(query, limit)

suspend fun searchEpubContent(
    epub: EpubContent,
    query: String,
    limit: Int = DEFAULT_LIMIT,
    onProgress: suspend (SearchProgress) -> Unit = {},
): List<SearchHit> = BookSearchIndex.buildEpub(epub, onProgress = onProgress).find(query, limit)
