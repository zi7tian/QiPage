package local.readapp.epub

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalEpubTest {
    private fun makeBook(dir: File, overrides: Map<String, String> = emptyMap()): File {
        val data = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>自制测试</dc:title></metadata><manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/><item id="n" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OPS/ch1.xhtml" to "<html><body>第一章</body></html>",
            "OPS/ch2.xhtml" to "<html><body>第二章</body></html>",
            "OPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">第一章</a><ol><li><a href="ch2.xhtml">第二章</a></li></ol></li></ol></nav></body></html>"""
        )
        data.putAll(overrides)
        val file = File(dir, "book.epub")
        ZipOutputStream(file.outputStream()).use { zip -> data.forEach { (path, value) -> zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return file
    }
    private fun withDirectory(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("readapp-epub-test").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Unsafe input accepted") } catch (_: IllegalArgumentException) {} }

    @Test fun opensSpineNestedTocAndOnlyDeclaredResources() = withDirectory { dir ->
        LocalEpub.open(makeBook(dir)).use { book ->
            assertEquals("自制测试", book.title)
            assertEquals(listOf("OPS/ch1.xhtml", "OPS/ch2.xhtml"), book.chapters.map { it.path })
            assertEquals(listOf("第一章", "第二章"), book.toc.map { it.title })
            assertEquals(listOf(0,1),book.toc.map { it.depth })
            assertTrue(String(book.resource("OPS/ch1.xhtml")!!.second).contains("第一章"))
            assertNull(book.resource("META-INF/container.xml"))
        }
    }
    @Test fun resolvesInternalParentButRejectsEscapesAndRemoteUris() {
        assertEquals("OPS/images/a.png", LocalEpub.resolve("OPS/text/ch.xhtml", "../images/a.png"))
        assertEquals("OPS/a b.xhtml", LocalEpub.resolve("OPS/ch.xhtml", "a%20b.xhtml#anchor"))
        for (bad in listOf("../../secret", "%2e%2e/%2e%2e/secret", "%252e%252e/x", "https://host/x", "file:///x", "content://x", "//host/x", "javascript:alert(1)", "a\\b", "x%00")) {
            rejected { LocalEpub.resolve("OPS/ch.xhtml", bad) }
        }
    }
    @Test fun rejectsZipTraversal() = withDirectory { dir -> rejected { LocalEpub.open(makeBook(dir, mapOf("../outside" to "bad"))) } }
    @Test fun rejectsDtdAndEntities() = withDirectory { dir ->
        rejected { LocalEpub.open(makeBook(dir, mapOf("META-INF/container.xml" to """<!DOCTYPE container [<!ENTITY x SYSTEM "file:///secret">]><container>&x;</container>"""))) }
    }
    @Test fun rejectsZipBombAtActualExpandedSize() = withDirectory { dir ->
        val file = File(dir, "bomb.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("bomb")); val block = ByteArray(8192)
            repeat(LocalEpub.MAX_ENTRY / block.size + 1) { zip.write(block) }
        }
        assertTrue(file.length() < 100000)
        rejected { LocalEpub.open(file) }
    }
    @Test fun acceptsLargeResourcesAndExpandedBookAboveOldLimit() = withDirectory { dir ->
        val additions=(0..11).associate { "image-$it.bin" to "a".repeat(9*1024*1024) }
        LocalEpub.open(makeBook(dir,additions)).use { assertEquals(2,it.chapters.size) }
    }    @Test fun rejectsEncryptedPackage() = withDirectory { dir -> rejected { LocalEpub.open(makeBook(dir, mapOf("META-INF/encryption.xml" to "<encryption/>"))) } }
}

